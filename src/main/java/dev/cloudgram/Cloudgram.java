package dev.cloudgram;

import dev.cloudgram.core.PackOptions;
import dev.cloudgram.core.SnapshotRequest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.SnapshotService;
import dev.cloudgram.core.WorldIdentity;
import dev.cloudgram.core.config.CloudgramConfig;
import dev.cloudgram.core.config.ConfigStore;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.telegram.HttpTelegramTransport;
import dev.cloudgram.core.upload.SnapshotUploader;
import dev.cloudgram.core.upload.UploadException;
import dev.cloudgram.core.upload.UploadRecord;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class Cloudgram implements ModInitializer {
	public static final String MOD_ID = "cloudgram";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static final Set<Path> SNAPSHOTS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
	private static final AtomicReference<ExecutorService> SNAPSHOT_EXECUTOR = new AtomicReference<>();
	private static final AtomicReference<ExecutorService> UPLOAD_EXECUTOR = new AtomicReference<>();

	@Override
	public void onInitialize() {
		ensureEncryptionKey();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			try {
				WorldIdentity identity = WorldIdentity.loadOrCreate(worldDir);
				LOG.info("Cloudgram tracking world '{}' ({})", server.getWorldData().getLevelName(), identity.id());
			} catch (IOException e) {
				LOG.warn("Cloudgram could not read or create the world identity in {}", worldDir, e);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// flush = true: wait until pending chunk writes hit disk before we ever read the folder.
			boolean saved = server.saveEverything(false, true, true);
			LOG.info("Cloudgram forced world save (ok={})", saved);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			SnapshotRequest request = new SnapshotRequest(
					server.getWorldData().getLevelName(),
					SharedConstants.getCurrentVersion().name(),
					server.getWorldData().getVersion(),
					deviceName(),
					modVersion());
			requestSnapshot(worldDir, request);
		});
	}

	/**
	 * Packs the closed world on a background thread.
	 *
	 * <p>The server thread must not be blocked by hashing gigabytes, and the client keeps running
	 * after leaving a world. The thread is a daemon: quitting the game mid-pack abandons that run
	 * (a persistent journal and resume come with the upload queue). Packing the same world twice at
	 * once is refused because the folder may already be open for writing again.
	 *
	 * <p>Uploading uses a second single-thread executor: a slow network must not delay the snapshot
	 * of the next world the user opens, while uploads still run one at a time.
	 */
	private static void requestSnapshot(Path worldDir, SnapshotRequest request) {
		Path key = worldDir.toAbsolutePath().normalize();
		if (!SNAPSHOTS_IN_FLIGHT.add(key)) {
			LOG.warn("Cloudgram is still packing '{}'; skipping this exit", request.worldName());
			return;
		}

		LOG.info("Cloudgram snapshot queued for '{}' at {}", request.worldName(), key);
		snapshotExecutor().execute(() -> {
			SnapshotResult result;
			try {
				SnapshotService service = new SnapshotService(vaultDir(), PackOptions.defaults());
				result = service.capture(key, request);
			} catch (Exception e) {
				LOG.error("Cloudgram snapshot for '{}' failed", request.worldName(), e);
				return;
			} finally {
				SNAPSHOTS_IN_FLIGHT.remove(key);
			}

			LOG.info(
					"Cloudgram snapshot {} complete: {} packs, {} MiB, {} files -> {}",
					result.manifest().snapshot().id(),
					result.packs().size(),
					String.format(Locale.ROOT, "%.1f", result.totalPackBytes() / 1048576.0),
					result.manifest().stats().files(),
					result.directory());
			uploadExecutor().execute(() -> uploadIfConfigured(result));
		});
	}

	/**
	 * Creates the recovery key on first launch and persists it before any world can be uploaded.
	 *
	 * <p>Generation happens here, not lazily at upload time: a key that cannot be saved must disable
	 * uploads instead of producing a snapshot encrypted with a key that is then lost.
	 */
	private static void ensureEncryptionKey() {
		try {
			ConfigStore store = ConfigStore.inDirectory(FabricLoader.getInstance().getConfigDir());
			CloudgramConfig config = store.load();
			if (config.hasEncryptionKey()) {
				return;
			}
			EncryptionKey key = EncryptionKey.generate();
			store.save(config.withEncryptionKey(key.toBase64()));
			LOG.info("Cloudgram created a backup key (fingerprint {})", key.fingerprint());
		} catch (IOException e) {
			LOG.warn("Cloudgram could not create its backup key; uploads stay disabled until it can", e);
		}
	}

	/** Uploads the finished snapshot when a bot chat is bound; never touches the local copy. */
	private static void uploadIfConfigured(SnapshotResult snapshot) {
		CloudgramConfig config;
		try {
			config = ConfigStore.inDirectory(FabricLoader.getInstance().getConfigDir()).load();
		} catch (IOException e) {
			LOG.warn("Cloudgram could not read its config; the snapshot stays local", e);
			return;
		}
		if (!config.backupOnExit()) {
			LOG.info("Cloudgram upload skipped: backups on exit are disabled");
			return;
		}
		if (!config.hasBot() || !config.isBound()) {
			LOG.info("Cloudgram upload skipped: no bot chat is bound yet");
			return;
		}
		if (!config.hasEncryptionKey()) {
			LOG.warn("Cloudgram upload skipped: no backup key is stored; open Cloudgram setup to create one");
			return;
		}

		EncryptionKey key;
		try {
			key = EncryptionKey.fromBase64(config.encryptionKey());
		} catch (IllegalArgumentException e) {
			LOG.warn("Cloudgram upload skipped: the stored backup key is invalid ({})", e.getMessage());
			return;
		}

		try {
			UploadRecord record = new SnapshotUploader(
					new HttpTelegramTransport(), new PayloadCipher(key))
					.upload(config.botToken(), config.chatId(), snapshot);
			int parts = record.packs().stream().mapToInt(pack -> pack.parts().size()).sum();
			LOG.info(
					"Cloudgram uploaded snapshot {} ({} packs, {} parts, restore code {})",
					snapshot.manifest().snapshot().id(),
					record.packs().size(),
					parts,
					record.restoreCode());
			if (record.warning() != null) {
				LOG.warn("Cloudgram: {}", record.warning());
			}
		} catch (UploadException e) {
			LOG.warn(
					"Cloudgram upload of {} failed; the local vault copy is intact",
					snapshot.manifest().snapshot().id(),
					e);
		} catch (RuntimeException e) {
			LOG.error(
					"Cloudgram upload of {} failed unexpectedly; the local vault copy is intact",
					snapshot.manifest().snapshot().id(),
					e);
		}
	}

	private static ExecutorService snapshotExecutor() {
		ExecutorService existing = SNAPSHOT_EXECUTOR.get();
		if (existing != null) {
			return existing;
		}
		ExecutorService created = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cloudgram-snapshot");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		if (SNAPSHOT_EXECUTOR.compareAndSet(null, created)) {
			return created;
		}
		created.shutdownNow();
		return SNAPSHOT_EXECUTOR.get();
	}

	private static ExecutorService uploadExecutor() {
		ExecutorService existing = UPLOAD_EXECUTOR.get();
		if (existing != null) {
			return existing;
		}
		ExecutorService created = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cloudgram-upload");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		if (UPLOAD_EXECUTOR.compareAndSet(null, created)) {
			return created;
		}
		created.shutdownNow();
		return UPLOAD_EXECUTOR.get();
	}

	private static Path vaultDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("vault");
	}

	/** A human-readable device name, used only in the snapshot manifest and the setup greeting. */
	public static String deviceName() {
		String name = System.getenv("COMPUTERNAME");
		if (name == null || name.isBlank()) {
			name = System.getenv("HOSTNAME");
		}
		return name == null || name.isBlank() ? "unknown" : name;
	}

	/** The mod version from {@code fabric.mod.json}, or {@code dev} when unavailable. */
	public static String modVersion() {
		return FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}
}
