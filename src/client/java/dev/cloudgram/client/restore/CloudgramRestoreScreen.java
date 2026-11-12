package dev.cloudgram.client.restore;

import dev.cloudgram.Cloudgram;
import dev.cloudgram.core.catalog.Catalog;
import dev.cloudgram.core.config.CloudgramConfig;
import dev.cloudgram.core.config.ConfigStore;
import dev.cloudgram.core.crypto.CryptoException;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.restore.RestoreException;
import dev.cloudgram.core.restore.RestoreResult;
import dev.cloudgram.core.restore.RestoreService;
import dev.cloudgram.core.telegram.HttpTelegramTransport;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the snapshots in the pinned Telegram catalog and restores one as a new save.
 *
 * <p>The screen never touches an existing world: the restore service builds the snapshot in a
 * temporary directory below {@code saves/} and moves it to a fresh name only after every hash and
 * the file count match the manifest.
 */
public final class CloudgramRestoreScreen extends Screen {
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_BODY = 0xFFA0A0A0;
	private static final int COLOR_DIM = 0xFF707070;
	private static final int COLOR_OK = 0xFF55FF55;
	private static final int COLOR_WARN = 0xFFFFAA00;
	private static final int COLOR_ERROR = 0xFFFF5555;

	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_HEIGHT = 30;
	private static final int PAGE_SIZE = 5;

	private static final DateTimeFormatter DATE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "cloudgram-restore");
		thread.setDaemon(true);
		return thread;
	});

	private final Screen parent;
	private final ConfigStore configStore;
	private final TelegramTransport transport;

	private String token;
	private long chatId;
	private PayloadCipher cipher;
	private String keyValue;

	private Catalog catalog;
	private int page;
	private boolean configLoaded;
	private boolean fetchAttempted;
	private boolean keyEditing;

	private String status = "";
	private int statusColor = COLOR_BODY;
	private boolean busy;
	private volatile boolean closed;

	private EditBox keyBox;
	private int contentWidth;
	private int left;
	private int titleY;
	private int subtitleY;
	private int statusY;
	private int rowsY;
	private int navY;

	public CloudgramRestoreScreen(Screen parent, ConfigStore configStore) {
		this(parent, configStore, new HttpTelegramTransport());
	}

	public CloudgramRestoreScreen(Screen parent, ConfigStore configStore, TelegramTransport transport) {
		super(Component.literal("Cloudgram restore"));
		if (configStore == null) {
			throw new IllegalArgumentException("configStore is required");
		}
		if (transport == null) {
			throw new IllegalArgumentException("transport is required");
		}
		this.parent = parent;
		this.configStore = configStore;
		this.transport = transport;
	}

	@Override
	protected void init() {
		super.init();
		computeLayout();

		if (!configLoaded) {
			configLoaded = true;
			loadConfig();
		}

		if (keyEditing) {
			buildKeyEditor();
		} else {
			buildList();
		}

		if (cipher != null && catalog == null && !busy && !fetchAttempted) {
			fetchAttempted = true;
			loadCatalog();
		}
	}

	private void computeLayout() {
		contentWidth = Math.min(300, Math.max(200, width - 40));
		left = width / 2 - contentWidth / 2;
		int top = Math.max(16, height / 2 - 130);
		titleY = top;
		subtitleY = top + 14;
		statusY = top + 30;
		rowsY = top + 68;
		navY = rowsY + PAGE_SIZE * ROW_HEIGHT + 4;
	}

	private void loadConfig() {
		try {
			CloudgramConfig config = configStore.load();
			keyValue = config.encryptionKey();
			if (!config.hasBot() || !config.isBound()) {
				setStatus("Connect Cloudgram to your bot chat first: close this screen and finish setup.",
						COLOR_WARN);
				return;
			}
			token = config.botToken();
			chatId = config.chatId();
			if (config.hasEncryptionKey()) {
				try {
					EncryptionKey key = EncryptionKey.fromBase64(config.encryptionKey());
					cipher = new PayloadCipher(key);
					setStatus("Reading the pinned catalog from Telegram...", COLOR_BODY);
				} catch (IllegalArgumentException e) {
					keyEditing = true;
					setStatus("The saved recovery key is unreadable. Paste the key from your other PC.",
							COLOR_ERROR);
				}
			} else {
				keyEditing = true;
				setStatus("This PC has no recovery key. Paste the key from the PC that made these backups.",
						COLOR_WARN);
			}
		} catch (IOException e) {
			Cloudgram.LOG.warn("Cloudgram could not read {}", configStore.file(), e);
			setStatus("Could not read the config: " + e.getMessage(), COLOR_ERROR);
		}
	}

	private void buildList() {
		int pageCount = pageCountForButtons();
		boolean paged = pageCount > 1;

		if (cipher != null && catalog != null) {
			List<Catalog.Snapshot> snapshots = sortedSnapshots();
			page = Math.max(0, Math.min(page, pageCount - 1));

			int from = page * PAGE_SIZE;
			int to = Math.min(snapshots.size(), from + PAGE_SIZE);
			for (int i = from; i < to; i++) {
				Catalog.Snapshot snapshot = snapshots.get(i);
				int y = rowsY + (i - from) * ROW_HEIGHT;
				Button restore = Button.builder(Component.literal("Restore"),
								button -> restore(snapshot))
						.bounds(left + contentWidth - 78, y + 4, 74, BUTTON_HEIGHT)
						.build();
				restore.active = !busy;
				addRenderableWidget(restore);
			}
		}

		if (cipher != null) {
			int third = (contentWidth - 16) / 3;
			if (paged) {
				Button previous = Button.builder(Component.literal("Back"), button -> {
							page--;
							refresh();
						})
						.bounds(left, navY, third, BUTTON_HEIGHT)
						.build();
				previous.active = !busy && page > 0;
				addRenderableWidget(previous);
			}

			Button refresh = Button.builder(Component.literal("Refresh"), button -> {
						catalog = null;
						fetchAttempted = false;
						refresh();
					})
					.bounds(paged ? left + third + 8 : left, navY,
							paged ? contentWidth - 2 * third - 16 : contentWidth, BUTTON_HEIGHT)
					.build();
			refresh.active = !busy;
			addRenderableWidget(refresh);

			if (paged) {
				Button next = Button.builder(Component.literal("Next"), button -> {
							page++;
							refresh();
						})
						.bounds(left + contentWidth - third, navY, third, BUTTON_HEIGHT)
						.build();
				next.active = !busy && page < pageCount - 1;
				addRenderableWidget(next);
			}

			Button changeKey = Button.builder(Component.literal("Change key..."), button -> {
						keyEditing = true;
						refresh();
					})
					.bounds(left, navY + 24, contentWidth, BUTTON_HEIGHT)
					.build();
			changeKey.active = !busy;
			addRenderableWidget(changeKey);
		}

		Button done = Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 - 50, height - 28, 100, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(done);
	}

	private void buildKeyEditor() {
		keyBox = new EditBox(font, left, statusY + 34, contentWidth, BUTTON_HEIGHT,
				Component.literal("Recovery key"));
		keyBox.setMaxLength(200);
		keyBox.setHint(Component.literal("Paste the recovery key"));
		keyBox.setValue("");
		addRenderableWidget(keyBox);

		Button save = Button.builder(Component.literal("Save key"), button -> saveKey())
				.bounds(left, statusY + 60, contentWidth, BUTTON_HEIGHT)
				.build();
		save.active = !busy;
		addRenderableWidget(save);

		if (keyValue != null && !keyValue.isBlank()) {
			Button cancel = Button.builder(Component.literal("Cancel"), button -> {
						keyEditing = false;
						refresh();
					})
					.bounds(left, statusY + 84, contentWidth, BUTTON_HEIGHT)
					.build();
			addRenderableWidget(cancel);
		}

		Button done = Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 - 50, height - 28, 100, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(done);

		setInitialFocus(keyBox);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.centeredText(font, Component.literal("Cloudgram restore"), width / 2, titleY, COLOR_TITLE);
		graphics.centeredText(font, Component.literal(subtitle()), width / 2, subtitleY, COLOR_BODY);

		if (!status.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(status), left, statusY, contentWidth, statusColor);
		}

		if (keyEditing || cipher == null || catalog == null) {
			return;
		}

		List<Catalog.Snapshot> snapshots = sortedSnapshots();
		int from = page * PAGE_SIZE;
		int to = Math.min(snapshots.size(), from + PAGE_SIZE);
		for (int i = from; i < to; i++) {
			Catalog.Snapshot snapshot = snapshots.get(i);
			int y = rowsY + (i - from) * ROW_HEIGHT;
			graphics.text(font, Component.literal(snapshot.worldName() + "   " + snapshot.restoreCode()),
					left + 2, y, COLOR_TITLE);
			graphics.text(font, Component.literal(detail(snapshot)), left + 2, y + 11, COLOR_DIM);
		}
	}

	private String subtitle() {
		int pageCount = pageCountForButtons();
		if (pageCount > 1) {
			return "Page " + (page + 1) + " of " + pageCount + " - never overwrites a world";
		}
		return "Downloads are verified and never overwrite a world";
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	@Override
	public void onClose() {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	private void loadCatalog() {
		if (busy || cipher == null || token == null) {
			return;
		}
		busy = true;
		setStatus("Reading the pinned catalog from Telegram...", COLOR_BODY);
		refresh();

		IO.execute(() -> {
			try {
				Catalog fetched = new RestoreService(transport, cipher).fetchCatalog(token, chatId);
				Cloudgram.LOG.info("Cloudgram restore: catalog lists {} snapshots", fetched.snapshots().size());
				onClient(() -> {
					busy = false;
					catalog = fetched;
					page = 0;
					setStatus(fetched.snapshots().isEmpty()
							? "No snapshots yet. Leave a world on any device to add one."
							: "Choose a snapshot. It will be restored as a new save.", COLOR_BODY);
					refresh();
				});
			} catch (TelegramException e) {
				onClient(() -> fail("Telegram: " + messageFor(e)));
			} catch (CryptoException e) {
				onClient(() -> fail("The catalog cannot be decrypted with this key."
						+ " Use Change key... and paste the recovery key from the PC that made the backups."));
			} catch (IOException e) {
				onClient(() -> fail("Could not read the catalog: " + e.getMessage()));
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram could not read the catalog", e);
				onClient(() -> fail("Could not read the catalog: " + e.getMessage()));
			}
		});
	}

	private void saveKey() {
		if (busy) {
			return;
		}
		String raw = keyBox == null ? "" : keyBox.getValue().trim();
		try {
			EncryptionKey key = EncryptionKey.fromBase64(raw);
			CloudgramConfig config = configStore.load().withEncryptionKey(key.toBase64());
			configStore.save(config);

			cipher = new PayloadCipher(key);
			keyValue = key.toBase64();
			keyEditing = false;
			catalog = null;
			fetchAttempted = false;
			setStatus("Recovery key saved. Reading the catalog...", COLOR_OK);
			refresh();
		} catch (IllegalArgumentException e) {
			fail("That is not a recovery key: " + e.getMessage());
		} catch (IOException e) {
			fail("Could not save the key: " + e.getMessage());
		}
	}

	private void restore(Catalog.Snapshot snapshot) {
		if (busy || cipher == null || token == null) {
			return;
		}
		busy = true;
		setStatus("Restoring " + snapshot.worldName() + "...", COLOR_BODY);
		refresh();

		Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
		IO.execute(() -> {
			try {
				RestoreResult result = new RestoreService(transport, cipher).restore(
						token, snapshot, saves,
						(stage, completed, total) -> onClient(() ->
								setProgress(stage, completed, total)));
				onClient(() -> {
					busy = false;
					Cloudgram.LOG.info("Cloudgram restore: wrote {} files to {}", result.files(), result.directory());
					setStatus("Restored \"" + result.worldName() + "\" (" + result.files()
							+ " files) as a new save. Nothing was overwritten.", COLOR_OK);
					refresh();
				});
			} catch (RestoreException e) {
				onClient(() -> fail(e.getMessage()));
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram restore failed", e);
				onClient(() -> fail("Restore failed: " + e.getMessage()));
			}
		});
	}

	private void setProgress(String stage, int completed, int total) {
		// The screen redraws every frame, so updating the text is enough; do not rebuild widgets.
		setStatus(stage + " " + completed + "/" + total, COLOR_BODY);
	}

	private List<Catalog.Snapshot> sortedSnapshots() {
		if (catalog == null) {
			return List.of();
		}
		List<Catalog.Snapshot> snapshots = new ArrayList<>(catalog.snapshots());
		snapshots.sort(Comparator.comparingLong(Catalog.Snapshot::createdAt).reversed());
		return snapshots;
	}

	private int pageCount(int size) {
		return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	private int pageCountForButtons() {
		return catalog == null ? 1 : pageCount(catalog.snapshots().size());
	}

	private static String detail(Catalog.Snapshot snapshot) {
		String date = snapshot.createdAt() > 0
				? DATE.format(Instant.ofEpochSecond(snapshot.createdAt()))
				: "unknown date";
		return date + "   " + snapshot.manifest().stats().files() + " files   "
				+ String.format(Locale.ROOT, "%.1f", snapshot.manifest().stats().packBytes() / 1048576.0)
				+ " MiB   " + snapshot.snapshotId();
	}

	private void fail(String message) {
		busy = false;
		statusColor = COLOR_ERROR;
		status = message;
		refresh();
	}

	private void setStatus(String message, int color) {
		status = message;
		statusColor = color;
	}

	private static String messageFor(TelegramException e) {
		if (e.isUnauthorized()) {
			return "Telegram refused the bot token. Revoke it in BotFather and reconnect.";
		}
		if (e.isRateLimited()) {
			return "Telegram is rate limiting requests. Try again in " + e.retryAfterSeconds() + " s.";
		}
		return e.getMessage();
	}

	private void onClient(Runnable action) {
		if (closed || minecraft == null) {
			return;
		}
		minecraft.execute(() -> {
			if (!closed) {
				action.run();
			}
		});
	}

	/** Defers widget rebuilds so they never mutate the list while a click is being dispatched. */
	private void refresh() {
		if (closed || minecraft == null) {
			return;
		}
		minecraft.execute(() -> {
			if (!closed) {
				rebuildWidgets();
			}
		});
	}
}
