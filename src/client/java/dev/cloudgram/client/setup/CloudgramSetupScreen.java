package dev.cloudgram.client.setup;

import com.mojang.blaze3d.Blaze3D;
import dev.cloudgram.Cloudgram;
import dev.cloudgram.client.restore.CloudgramRestoreScreen;
import dev.cloudgram.core.config.CloudgramConfig;
import dev.cloudgram.core.config.ConfigStore;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.setup.Binding;
import dev.cloudgram.core.setup.SetupException;
import dev.cloudgram.core.setup.TelegramSetup;
import dev.cloudgram.core.telegram.BotInfo;
import dev.cloudgram.core.telegram.BotTokens;
import dev.cloudgram.core.telegram.HttpTelegramTransport;
import dev.cloudgram.core.telegram.TelegramException;
import dev.cloudgram.core.telegram.TelegramTransport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first-run wizard: paste a BotFather token, verify it, press Start in the bot chat, bind.
 *
 * <p>All network and file work happens on a daemon thread; results are marshalled back to the
 * client thread before any widget is touched. The screen never writes a binding before Telegram
 * has actually seen the user's {@code /start}, so a cancelled wizard leaves the previous config
 * untouched.
 */
public final class CloudgramSetupScreen extends Screen {
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_BODY = 0xFFA0A0A0;
	private static final int COLOR_OK = 0xFF55FF55;
	private static final int COLOR_WARN = 0xFFFFAA00;
	private static final int COLOR_ERROR = 0xFFFF5555;

	private static final int CONTENT_WIDTH = 240;
	private static final int BUTTON_HEIGHT = 20;

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "cloudgram-setup");
		thread.setDaemon(true);
		return thread;
	});

	private final Screen parent;
	private final ConfigStore configStore;
	private final TelegramSetup setup;

	private String tokenValue = "";
	private boolean tokenFromConfig;
	private String botUsername;
	private boolean verified;
	private long cursor;
	private Binding binding;
	private String recoveryKey;

	private String status = "";
	private int statusColor = COLOR_BODY;
	private boolean busy;
	private volatile boolean closed;

	private boolean configLoaded;

	private EditBox tokenBox;
	private Button verifyButton;
	private int left;
	private int titleY;
	private int subtitleY;
	private int tokenLabelY;
	private int tokenRowY;
	private int verifyY;
	private int statusY;
	private int actionsY;

	public CloudgramSetupScreen(Screen parent, ConfigStore configStore) {
		this(parent, configStore, new HttpTelegramTransport());
	}

	public CloudgramSetupScreen(Screen parent, ConfigStore configStore, TelegramTransport transport) {
		super(Component.literal("Cloudgram"));
		if (configStore == null) {
			throw new IllegalArgumentException("configStore is required");
		}
		this.parent = parent;
		this.configStore = configStore;
		this.setup = new TelegramSetup(transport);
	}

	@Override
	protected void init() {
		super.init();

		if (!configLoaded) {
			configLoaded = true;
			loadConfig();
		}

		computeLayout();

		boolean canEdit = !busy && binding == null;

		tokenBox = new EditBox(font, left, tokenRowY, 160, BUTTON_HEIGHT, Component.literal("Bot token"));
		tokenBox.setMaxLength(200);
		tokenBox.setHint(Component.literal(tokenFromConfig
				? "Saved token (paste new)"
				: "123456789:AA..."));
		tokenBox.setEditable(canEdit);
		// A token loaded from disk is never painted into the field: the screen can be streamed or
		// screenshotted, and the saved value is only needed to re-verify it.
		tokenBox.setValue(tokenFromConfig ? "" : tokenValue);
		tokenBox.setResponder(value -> {
			tokenValue = value;
			tokenFromConfig = false;
			if (verifyButton != null) {
				verifyButton.active = !busy && binding == null && !value.isBlank();
			}
		});
		addRenderableWidget(tokenBox);

		Button paste = Button.builder(Component.literal("Paste"), button -> pasteToken())
				.bounds(left + 164, tokenRowY, 36, BUTTON_HEIGHT)
				.build();
		paste.active = canEdit;
		addRenderableWidget(paste);

		Button help = Button.builder(Component.literal("Help"), button -> openUri(TelegramSetup.BOTFATHER_URL))
				.bounds(left + 204, tokenRowY, 36, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(help);

		Button verify = Button.builder(Component.literal(busy ? "Please wait..." : "Verify token"), button -> verifyToken())
				.bounds(left, verifyY, CONTENT_WIDTH, BUTTON_HEIGHT)
				.build();
		verify.active = canEdit && !tokenValue.isBlank();
		verifyButton = verify;
		addRenderableWidget(verify);

		buildActionButtons();

		Button done = Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 - 50, height - 28, 100, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(done);

		if (canEdit) {
			setInitialFocus(tokenBox);
		}
	}

	private void buildActionButtons() {
		int half = (CONTENT_WIDTH - 8) / 2;

		if (binding != null) {
			if (botUsername != null && !botUsername.isBlank()) {
				Button openBot = Button.builder(Component.literal("Open bot chat"),
								button -> openUri(TelegramSetup.botUrl(botUsername)))
						.bounds(left, actionsY, half, BUTTON_HEIGHT)
						.build();
				addRenderableWidget(openBot);

				Button replace = Button.builder(Component.literal("Replace bot..."), button -> replaceBot())
						.bounds(left + half + 8, actionsY, half, BUTTON_HEIGHT)
						.build();
				addRenderableWidget(replace);
			} else {
				Button replace = Button.builder(Component.literal("Replace bot..."), button -> replaceBot())
						.bounds(left, actionsY, CONTENT_WIDTH, BUTTON_HEIGHT)
						.build();
				addRenderableWidget(replace);
			}

			Button restore = Button.builder(Component.literal("Restore from Telegram..."),
							button -> openRestore())
					.bounds(left, actionsY + 24, CONTENT_WIDTH, BUTTON_HEIGHT)
					.build();
			restore.active = !busy;
			addRenderableWidget(restore);

			Button copyKey = Button.builder(Component.literal("Copy recovery key"), button -> copyRecoveryKey())
					.bounds(left, actionsY + 48, CONTENT_WIDTH, BUTTON_HEIGHT)
					.build();
			addRenderableWidget(copyKey);
			return;
		}

		if (botUsername == null || botUsername.isBlank()) {
			return;
		}

		Button openBot = Button.builder(Component.literal("Open bot chat"),
						button -> openUri(TelegramSetup.botUrl(botUsername)))
				.bounds(left, actionsY, verified ? half : CONTENT_WIDTH, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(openBot);

		if (verified) {
			Button bind = Button.builder(Component.literal("I pressed Start"), button -> bindChat())
					.bounds(left + half + 8, actionsY, half, BUTTON_HEIGHT)
					.build();
			bind.active = !busy;
			addRenderableWidget(bind);
		}
	}

	private void computeLayout() {
		left = width / 2 - CONTENT_WIDTH / 2;
		int top = Math.max(18, height / 2 - 100);
		titleY = top;
		subtitleY = top + 14;
		tokenLabelY = top + 32;
		tokenRowY = top + 44;
		verifyY = top + 70;
		statusY = top + 98;
		actionsY = top + 126;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.centeredText(font, Component.literal("Cloudgram"), width / 2, titleY, COLOR_TITLE);
		graphics.centeredText(font, Component.literal("Back up worlds to your own Telegram bot"),
				width / 2, subtitleY, COLOR_BODY);
		graphics.text(font, Component.literal("Bot token"), left, tokenLabelY, COLOR_BODY);

		if (!status.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(status), left, statusY, CONTENT_WIDTH, statusColor);
		}

		if (binding != null) {
			graphics.text(font, Component.literal("Recovery key: copy it for other PCs"),
					left, actionsY + 70, COLOR_WARN);
		}
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	@Override
	public void onClose() {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(parent != null ? parent : new TitleScreen());
		}
	}

	private void loadConfig() {
		try {
			CloudgramConfig config = configStore.load();
			tokenValue = config.botToken() == null ? "" : config.botToken();
			tokenFromConfig = config.hasBot();
			botUsername = config.botUsername();
			recoveryKey = config.encryptionKey();
			if (config.isBound()) {
				binding = new Binding(config.chatId(), config.chatTitle(), "private", config.boundAt());
				setStatus("Connected to " + config.chatTitle() + ". Backups go to this chat.",
						COLOR_OK);
			} else if (config.hasBot()) {
				setStatus("Token saved for @" + config.botUsername() + ". Click Verify to continue.", COLOR_BODY);
			} else {
				setStatus("Create a bot with @BotFather, copy its token, and paste it here.", COLOR_BODY);
			}
		} catch (IOException e) {
			Cloudgram.LOG.warn("Cloudgram could not read {}", configStore.file(), e);
			setStatus("Could not read the config: " + e.getMessage(), COLOR_ERROR);
		}
	}

	private void pasteToken() {
		if (minecraft == null || minecraft.keyboardHandler == null) {
			return;
		}
		String clipboard = minecraft.keyboardHandler.getClipboard();
		if (clipboard == null || clipboard.isBlank()) {
			setStatus("The clipboard is empty.", COLOR_WARN);
			return;
		}
		tokenValue = clipboard.trim();
		tokenFromConfig = false;
		if (tokenBox != null) {
			tokenBox.setValue(tokenValue);
		}
		setStatus("Token pasted. Click Verify token.", COLOR_BODY);
		refresh();
	}

	private void verifyToken() {
		if (busy || binding != null) {
			return;
		}

		final String token;
		try {
			token = BotTokens.normalize(tokenValue);
		} catch (IllegalArgumentException e) {
			setStatus(e.getMessage(), COLOR_ERROR);
			refresh();
			return;
		}

		busy = true;
		setStatus("Checking the token with Telegram...", COLOR_BODY);
		refresh();

		IO.execute(() -> {
			try {
				BotInfo bot = setup.verify(token);
				long startCursor = setup.startCursor(token);
				CloudgramConfig config = loadForUpdate()
						.withBot(token, bot.username());
				configStore.save(config);

				onClient(() -> {
					botUsername = bot.username();
					verified = true;
					cursor = startCursor;
					tokenValue = token;
					tokenFromConfig = true;
					busy = false;
					setStatus("Token verified: " + bot.displayName()
							+ ". Open the bot chat, press Start, then click I pressed Start.", COLOR_OK);
					refresh();
				});
			} catch (TelegramException e) {
				onClient(() -> fail(messageFor(e)));
			} catch (IOException e) {
				onClient(() -> fail("Could not save the token: " + e.getMessage()));
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram setup failed", e);
				onClient(() -> fail("Unexpected error: " + e.getMessage()));
			}
		});
	}

	private void bindChat() {
		if (busy || binding != null || botUsername == null) {
			return;
		}

		final String token = tokenValue;
		final String username = botUsername;
		busy = true;
		setStatus("Waiting for Start in the bot chat...", COLOR_BODY);
		refresh();

		IO.execute(() -> {
			try {
				Binding bound = setup.bind(token, username, cursor);
				CloudgramConfig config = loadForUpdate()
						.withBot(token, username)
						.withBinding(bound.chatId(), bound.title(), bound.boundAt());
				if (!config.hasEncryptionKey()) {
					// Generate before the binding is saved: a chat without a key could never upload.
					config = config.withEncryptionKey(EncryptionKey.generate().toBase64());
				}
				configStore.save(config);
				String key = config.encryptionKey();

				String warning = null;
				try {
					setup.announceBinding(token, bound, Cloudgram.deviceName(), Cloudgram.modVersion());
				} catch (TelegramException e) {
					warning = " The confirmation message failed: " + messageFor(e);
				}

				String extra = warning;
				onClient(() -> {
					binding = bound;
					recoveryKey = key;
					busy = false;
					setStatus("Connected to " + bound.title() + ". Backups will be sent to this chat."
							+ (extra == null ? "" : extra), extra == null ? COLOR_OK : COLOR_WARN);
					refresh();
				});
			} catch (SetupException e) {
				onClient(() -> fail(e.getMessage()));
			} catch (TelegramException e) {
				onClient(() -> fail(messageFor(e)));
			} catch (IOException e) {
				onClient(() -> fail("Could not save the chat binding: " + e.getMessage()));
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram setup failed", e);
				onClient(() -> fail("Unexpected error: " + e.getMessage()));
			}
		});
	}

	private void replaceBot() {
		if (busy) {
			return;
		}
		binding = null;
		verified = false;
		botUsername = null;
		cursor = 0;
		tokenValue = "";
		tokenFromConfig = false;
		if (tokenBox != null) {
			tokenBox.setValue("");
		}
		setStatus("Paste the token of the bot you want to use.", COLOR_BODY);
		refresh();
	}

	private void openRestore() {
		if (minecraft == null || minecraft.gui == null || busy) {
			return;
		}
		minecraft.gui.setScreen(new CloudgramRestoreScreen(this, configStore));
	}

	private void copyRecoveryKey() {
		if (minecraft == null || minecraft.keyboardHandler == null) {
			return;
		}
		if (recoveryKey != null && !recoveryKey.isBlank()) {
			minecraft.keyboardHandler.setClipboard(recoveryKey);
			setStatus("Recovery key copied. Keep it private; it is required to restore on another PC.",
					COLOR_OK);
			refresh();
			return;
		}

		setStatus("Creating a recovery key...", COLOR_BODY);
		refresh();
		IO.execute(() -> {
			try {
				CloudgramConfig config = loadForUpdate();
				String key = config.encryptionKey();
				if (key == null || key.isBlank()) {
					key = EncryptionKey.generate().toBase64();
					configStore.save(config.withEncryptionKey(key));
				}
				String saved = key;
				onClient(() -> {
					recoveryKey = saved;
					if (minecraft != null && minecraft.keyboardHandler != null) {
						minecraft.keyboardHandler.setClipboard(saved);
					}
					setStatus("Recovery key copied. Keep it private; it is required to restore on another PC.",
							COLOR_OK);
					refresh();
				});
			} catch (IOException e) {
				onClient(() -> fail("Could not save the recovery key: " + e.getMessage()));
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram could not create a recovery key", e);
				onClient(() -> fail("Could not create the recovery key: " + e.getMessage()));
			}
		});
	}

	private CloudgramConfig loadForUpdate() {
		try {
			return configStore.load();
		} catch (IOException e) {
			// The wizard is replacing a broken config; keeping the user blocked helps nobody.
			Cloudgram.LOG.warn("Cloudgram is replacing an unreadable config at {}", configStore.file(), e);
			return CloudgramConfig.empty();
		}
	}

	private void openUri(String url) {
		try {
			Blaze3D.openUri(URI.create(url));
		} catch (RuntimeException e) {
			Cloudgram.LOG.warn("Cloudgram could not open {}", url, e);
			setStatus("Could not open the link: " + url, COLOR_ERROR);
			refresh();
		}
	}

	private void fail(String message) {
		busy = false;
		setStatus(message, COLOR_ERROR);
		refresh();
	}

	private void setStatus(String message, int color) {
		status = message;
		statusColor = color;
	}

	private static String messageFor(TelegramException e) {
		if (e.isUnauthorized()) {
			return "Telegram refused this token. Create a new token in BotFather and try again.";
		}
		if (e.isRateLimited()) {
			return "Telegram is rate limiting requests. Try again in "
					+ e.retryAfterSeconds() + " seconds.";
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
