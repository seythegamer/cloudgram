package dev.cloudgram.client;

import dev.cloudgram.Cloudgram;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudgramClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Cloudgram.LOG.info("Cloudgram client initialised");
		registerSelfTestHook();
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
