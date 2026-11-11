package dev.cloudgram.client;

import dev.cloudgram.Cloudgram;
import dev.cloudgram.client.setup.CloudgramSetupScreen;
import dev.cloudgram.core.config.ConfigStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudgramClient implements ClientModInitializer {
	private static final AtomicBoolean DEV_SETUP_OPENED = new AtomicBoolean();

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

			maybeOpenSetupForSelfTest(client, titleScreen, configStore);
		});
	}

	/**
	 * Development-only hook that opens the wizard as soon as the title screen appears.
	 *
	 * <p>Inert outside {@code fabric.development=true} and only active with
	 * {@code -Dcloudgram.selftest.openSetup}.
	 */
	private static void maybeOpenSetupForSelfTest(Minecraft client, TitleScreen screen, ConfigStore configStore) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		String raw = System.getProperty("cloudgram.selftest.openSetup");
		if (raw == null || !Boolean.parseBoolean(raw.trim())) {
			return;
		}
		if (!DEV_SETUP_OPENED.compareAndSet(false, true)) {
			return;
		}

		Cloudgram.LOG.info("Cloudgram self-test: opening the setup wizard");
		client.execute(() -> {
			if (client.gui.screen() == screen) {
				client.gui.setScreen(new CloudgramSetupScreen(screen, configStore));
			}
		});
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
