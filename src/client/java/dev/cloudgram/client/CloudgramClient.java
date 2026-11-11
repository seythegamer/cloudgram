package dev.cloudgram.client;

import dev.cloudgram.Cloudgram;
import net.fabricmc.api.ClientModInitializer;

public final class CloudgramClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Cloudgram.LOG.info("Cloudgram client initialised");
	}
}
