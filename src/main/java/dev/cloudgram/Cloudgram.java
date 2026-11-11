package dev.cloudgram;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class Cloudgram implements ModInitializer {
	public static final String MOD_ID = "cloudgram";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			LOG.info("Cloudgram attached to world '{}' at {}", server.getWorldData().getLevelName(), worldDir);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// flush = true: wait until pending chunk writes hit disk before we ever copy the folder.
			boolean saved = server.saveEverything(false, true, true);
			LOG.info("Cloudgram forced world save (ok={})", saved);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			LOG.info("Cloudgram world closed; a snapshot would start now for {}", worldDir);
		});
	}
}
