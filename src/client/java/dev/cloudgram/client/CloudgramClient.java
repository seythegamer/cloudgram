package dev.cloudgram.client;

import dev.cloudgram.Cloudgram;
import dev.cloudgram.client.restore.CloudgramImportPromptScreen;
import dev.cloudgram.client.restore.CloudgramRestoreScreen;
import dev.cloudgram.client.setup.CloudgramSetupScreen;
import dev.cloudgram.core.catalog.Catalog;
import dev.cloudgram.core.config.CloudgramConfig;
import dev.cloudgram.core.config.ConfigStore;
import dev.cloudgram.core.crypto.CryptoException;
import dev.cloudgram.core.crypto.EncryptionKey;
import dev.cloudgram.core.crypto.PayloadCipher;
import dev.cloudgram.core.restore.RestoreService;
import dev.cloudgram.core.telegram.HttpTelegramTransport;
import dev.cloudgram.core.telegram.TelegramException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudgramClient implements ClientModInitializer {
	private static final AtomicBoolean DEV_SETUP_OPENED = new AtomicBoolean();
	private static final AtomicBoolean DEV_RESTORE_OPENED = new AtomicBoolean();
	private static final AtomicBoolean IMPORT_CHECK_STARTED = new AtomicBoolean();

	@Override
	public void onInitializeClient() {
		Cloudgram.LOG.info("Cloudgram client initialised");

		ConfigStore configStore = ConfigStore.inDirectory(FabricLoader.getInstance().getConfigDir());
		registerTitleScreenButton(configStore);
		registerSelfTestHook();
	}

	/** Adds a small Cloudgram button to the title screen, independent of the vanilla menu layout. */
	private static void registerTitleScreenButton(ConfigStore configStore) {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof TitleScreen titleScreen)) {
				return;
			}

			Button button = Button.builder(Component.literal("Cloudgram"),
							pressed -> client.gui.setScreen(new CloudgramSetupScreen(titleScreen, configStore)))
					.bounds(Math.max(4, scaledWidth - 108), 6, 100, 20)
					.build();
			Screens.getWidgets(screen).add(button);

			if (maybeOpenRestoreForSelfTest(client, titleScreen, configStore)) {
				return;
			}
			if (maybeOpenSetupForSelfTest(client, titleScreen, configStore)) {
				return;
			}
			maybeOfferImport(client, titleScreen, configStore);
		});
	}

	/**
	 * Offers, once per installation, to import the snapshots that already sit in the bound chat.
	 *
	 * <p>The check runs on a daemon thread so a slow or offline Telegram never delays the title
	 * screen. It only remembers that the prompt was shown; the restore list itself stays available
	 * from the Cloudgram button at any time.
	 */
	private static void maybeOfferImport(Minecraft client, TitleScreen screen, ConfigStore configStore) {
		if (!IMPORT_CHECK_STARTED.compareAndSet(false, true)) {
			return;
		}

		CloudgramConfig config;
		try {
			config = configStore.load();
		} catch (IOException e) {
			Cloudgram.LOG.warn("Cloudgram could not read {}", configStore.file(), e);
			return;
		}
		if (!config.isBound() || !config.hasBot() || !config.hasEncryptionKey() || config.importPrompted()) {
			return;
		}

		String token = config.botToken();
		long chatId = config.chatId();
		String keyValue = config.encryptionKey();

		Thread thread = new Thread(() -> {
			try {
				PayloadCipher cipher = new PayloadCipher(EncryptionKey.fromBase64(keyValue));
				Catalog catalog = new RestoreService(new HttpTelegramTransport(), cipher)
						.fetchCatalog(token, chatId);
				int snapshots = catalog.snapshots().size();
				if (snapshots == 0) {
					return;
				}

				Cloudgram.LOG.info("Cloudgram found {} snapshot(s) in the bound chat; offering to import them",
						snapshots);
				client.execute(() -> {
					try {
						configStore.save(configStore.load().withImportPrompted(true));
					} catch (IOException e) {
						Cloudgram.LOG.warn("Cloudgram could not remember the import prompt", e);
					}
					if (client.gui.screen() == screen) {
						client.gui.setScreen(new CloudgramImportPromptScreen(screen, configStore, snapshots));
					}
				});
			} catch (TelegramException | CryptoException | IOException e) {
				// Try again next launch; the message is enough detail for a log.
				Cloudgram.LOG.warn("Cloudgram could not check the chat for existing snapshots: {}",
						e.getMessage());
			} catch (RuntimeException e) {
				Cloudgram.LOG.warn("Cloudgram could not check the chat for existing snapshots", e);
			}
		}, "cloudgram-import-check");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Development-only hook that opens the restore list on the title screen.
	 *
	 * <p>Inert outside {@code fabric.development=true} and only active with
	 * {@code -Dcloudgram.selftest.openRestore=true}.
	 */
	private static boolean maybeOpenRestoreForSelfTest(
			Minecraft client, TitleScreen screen, ConfigStore configStore) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return false;
		}
		String raw = System.getProperty("cloudgram.selftest.openRestore");
		if (raw == null || !Boolean.parseBoolean(raw.trim())) {
			return false;
		}
		if (!DEV_RESTORE_OPENED.compareAndSet(false, true)) {
			return true;
		}

		Cloudgram.LOG.info("Cloudgram self-test: opening the restore screen");
		client.execute(() -> {
			if (client.gui.screen() == screen) {
				client.gui.setScreen(new CloudgramRestoreScreen(screen, configStore));
			}
		});
		return true;
	}

	/**
	 * Development-only hook that opens the wizard as soon as the title screen appears.
	 *
	 * <p>Inert outside {@code fabric.development=true} and only active with
	 * {@code -Dcloudgram.selftest.openSetup}.
	 */
	private static boolean maybeOpenSetupForSelfTest(
			Minecraft client, TitleScreen screen, ConfigStore configStore) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return false;
		}
		String raw = System.getProperty("cloudgram.selftest.openSetup");
		if (raw == null || !Boolean.parseBoolean(raw.trim())) {
			return false;
		}
		if (!DEV_SETUP_OPENED.compareAndSet(false, true)) {
			return true;
		}

		Cloudgram.LOG.info("Cloudgram self-test: opening the setup wizard");
		client.execute(() -> {
			if (client.gui.screen() == screen) {
				client.gui.setScreen(new CloudgramSetupScreen(screen, configStore));
			}
		});
		return true;
	}

	/**
	 * Development-only hook that leaves the current world the same way the pause menu does, so the
	 * exit path can be exercised without clicking through screens.
	 *
	 * <p>Disabled outside {@code fabric.development=true} and inert unless
	 * {@code -Dcloudgram.selftest.leaveAfterTicks=<n>} is set.
	 */
	private static void registerSelfTestHook() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}

		String raw = System.getProperty("cloudgram.selftest.leaveAfterTicks");
		if (raw == null || raw.isBlank()) {
			return;
		}

		int ticks;
		try {
			ticks = Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			Cloudgram.LOG.warn("Cloudgram self-test: ignoring invalid leaveAfterTicks='{}'", raw);
			return;
		}
		if (ticks < 0) {
			Cloudgram.LOG.warn("Cloudgram self-test: ignoring negative leaveAfterTicks={}", ticks);
			return;
		}

		AtomicInteger remaining = new AtomicInteger(ticks);
		AtomicBoolean requested = new AtomicBoolean();
		Cloudgram.LOG.info("Cloudgram self-test: leaving the world after {} ticks", ticks);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null || requested.get()) {
				return;
			}
			if (remaining.decrementAndGet() > 0) {
				return;
			}
			requested.set(true);
			Cloudgram.LOG.info("Cloudgram self-test: leaving the world now");
			Minecraft minecraft = client;
			client.execute(() -> minecraft.disconnect(new TitleScreen(), true));
		});
	}
}
