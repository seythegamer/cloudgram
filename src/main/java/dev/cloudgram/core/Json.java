package dev.cloudgram.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared Gson configuration for the on-disk and on-Telegram JSON documents. */
public final class Json {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private Json() {
	}

	public static Gson gson() {
		return GSON;
	}
}
