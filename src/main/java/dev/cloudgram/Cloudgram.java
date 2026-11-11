package dev.cloudgram;

import dev.cloudgram.core.PackOptions;
import dev.cloudgram.core.SnapshotRequest;
import dev.cloudgram.core.SnapshotResult;
import dev.cloudgram.core.SnapshotService;
import dev.cloudgram.core.WorldIdentity;
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

	@Override
	public void onInitialize() {
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
	 */
	private static void requestSnapshot(Path worldDir, SnapshotRequest request) {
		Path key = worldDir.toAbsolutePath().normalize();
		if (!SNAPSHOTS_IN_FLIGHT.add(key)) {
			LOG.warn("Cloudgram is still packing '{}'; skipping this exit", request.worldName());
			return;
		}

		LOG.info("Cloudgram snapshot queued for '{}' at {}", request.worldName(), key);
		snapshotExecutor().execute(() -> {
			try {
				SnapshotService service = new SnapshotService(vaultDir(), PackOptions.defaults());
				SnapshotResult result = service.capture(key, request);
				LOG.info(
						"Cloudgram snapshot {} complete: {} packs, {} MiB, {} files -> {}",
						result.manifest().snapshot().id(),
						result.packs().size(),
						String.format(Locale.ROOT, "%.1f", result.totalPackBytes() / 1048576.0),
						result.manifest().stats().files(),
						result.directory());
			} catch (Exception e) {
				LOG.error("Cloudgram snapshot for '{}' failed", request.worldName(), e);
			} finally {
				SNAPSHOTS_IN_FLIGHT.remove(key);
			}
		});
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

	private static Path vaultDir() {
		return FabricLoader.getInstance().getGameDir().resolve(MOD_ID).resolve("vault");
	}

	private static String deviceName() {
		String name = System.getenv("COMPUTERNAME");
		if (name == null || name.isBlank()) {
			name = System.getenv("HOSTNAME");
		}
		return name == null || name.isBlank() ? "unknown" : name;
	}

	private static String modVersion() {
		return FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}
}
