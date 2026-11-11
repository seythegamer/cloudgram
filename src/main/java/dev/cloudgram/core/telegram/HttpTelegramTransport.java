package dev.cloudgram.core.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bot API client built on {@link HttpClient}.
 *
 * <p>Only form-encoded and JSON responses are used here; file uploads (multipart) belong to the
 * upload queue. No request or response is logged, because the URL contains the bot token.
 */
public final class HttpTelegramTransport implements TelegramTransport {
	private static final URI DEFAULT_BASE = URI.create("https://api.telegram.org");
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	private final URI base;
	private final Duration timeout;
	private final HttpClient client;

	public HttpTelegramTransport() {
		this(DEFAULT_BASE, DEFAULT_TIMEOUT);
	}

	public HttpTelegramTransport(URI base, Duration timeout) {
		if (base == null) {
			throw new IllegalArgumentException("base is required");
		}
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		this.base = base;
		this.timeout = timeout;
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public BotInfo getMe(String token) throws TelegramException {
		JsonObject result = callObject(token, "getMe", Map.of(), false);
		return new BotInfo(
				requiredLong(result, "id"),
				requiredString(result, "username"),
				optionalString(result, "first_name"));
	}

	@Override
	public List<IncomingMessage> getUpdates(String token, long offset, int timeoutSeconds) throws TelegramException {
		if (offset < -1) {
			throw new IllegalArgumentException("offset must be -1 or greater");
		}
		if (timeoutSeconds < 0) {
			throw new IllegalArgumentException("timeoutSeconds must not be negative");
		}
		JsonElement result = call(token, "getUpdates", Map.of(
				"offset", Long.toString(offset),
				"timeout", Integer.toString(timeoutSeconds),
				"allowed_updates", "[\"message\"]"), false);

		List<IncomingMessage> messages = new ArrayList<>();
		if (result == null || !result.isJsonArray()) {
			return List.of();
		}
		JsonArray updates = result.getAsJsonArray();
		for (JsonElement element : updates) {
			IncomingMessage message = parseUpdate(element);
			if (message != null) {
				messages.add(message);
			}
		}
		return List.copyOf(messages);
	}

	@Override
	public long sendMessage(String token, long chatId, String text) throws TelegramException {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("text is required");
		}
		JsonObject result = callObject(token, "sendMessage", Map.of(
				"chat_id", Long.toString(chatId),
				"text", text,
				"disable_web_page_preview", "true"), true);
		return requiredLong(result, "message_id");
	}

	@Override
	public void pinChatMessage(String token, long chatId, long messageId) throws TelegramException {
		call(token, "pinChatMessage", Map.of(
				"chat_id", Long.toString(chatId),
				"message_id", Long.toString(messageId),
				"disable_notification", "true"), true);
	}

	private JsonObject callObject(String token, String method, Map<String, String> params, boolean post)
			throws TelegramException {
		JsonElement result = call(token, method, params, post);
		if (result == null || !result.isJsonObject()) {
			throw new TelegramException("Telegram returned an unexpected result for " + method);
		}
		return result.getAsJsonObject();
	}

	/**
	 * Performs one Bot API call and returns the {@code result} element.
	 *
	 * @throws TelegramException for HTTP failures, {@code ok=false} responses, and network errors
	 */
	private JsonElement call(String token, String method, Map<String, String> params, boolean post)
			throws TelegramException {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("token is required");
		}

		URI uri = base.resolve("/bot" + token.trim() + "/" + method);
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("User-Agent", "Cloudgram/0.1");

		if (post) {
			request.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
					.POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8));
		} else {
			request.GET();
			if (!params.isEmpty()) {
				uri = URI.create(uri + "?" + formEncode(params));
				request.uri(uri);
			}
		}

		HttpResponse<String> response;
		try {
			response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TelegramException("Telegram request was interrupted", e);
		} catch (IOException e) {
			throw new TelegramException("Cannot reach Telegram: " + e.getMessage(), e);
		}

		JsonObject root;
		try {
			root = JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new TelegramException(
					"Telegram returned HTTP " + response.statusCode() + " with an unreadable body", e);
		}

		boolean ok = root.has("ok") && root.get("ok").isJsonPrimitive() && root.get("ok").getAsBoolean();
		if (!ok) {
			int code = root.has("error_code") ? root.get("error_code").getAsInt() : response.statusCode();
			String description = root.has("description") ? root.get("description").getAsString() : "unknown error";
			int retryAfter = 0;
			if (root.has("parameters") && root.get("parameters").isJsonObject()) {
				JsonObject parameters = root.getAsJsonObject("parameters");
				if (parameters.has("retry_after")) {
					retryAfter = parameters.get("retry_after").getAsInt();
				}
			}
			throw new TelegramException(code, description, retryAfter);
		}

		return root.get("result");
	}

	private static IncomingMessage parseUpdate(JsonElement element) throws TelegramException {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject update = element.getAsJsonObject();
		if (!update.has("message") || !update.get("message").isJsonObject()) {
			return null;
		}
		JsonObject message = update.getAsJsonObject("message");
		if (!message.has("chat") || !message.get("chat").isJsonObject()) {
			return null;
		}
		JsonObject chat = message.getAsJsonObject("chat");

		String chatTitle = optionalString(chat, "title");
		if (chatTitle == null) {
			chatTitle = optionalString(chat, "username");
		}
		if (chatTitle == null) {
			String first = optionalString(chat, "first_name");
			String last = optionalString(chat, "last_name");
			chatTitle = joinName(first, last);
		}
		if (chatTitle == null) {
			chatTitle = "Telegram chat " + chat.get("id").getAsLong();
		}

		return new IncomingMessage(
				requiredLong(update, "update_id"),
				requiredLong(message, "message_id"),
				requiredLong(chat, "id"),
				optionalString(chat, "type"),
				chatTitle,
				optionalString(message, "text"),
				message.has("date") ? message.get("date").getAsLong() : 0L);
	}

	private static String joinName(String first, String last) {
		if (first == null) {
			return last;
		}
		return last == null ? first : first + " " + last;
	}

	private static long requiredLong(JsonObject object, String field) throws TelegramException {
		if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
			throw new TelegramException("Telegram response is missing '" + field + "'");
		}
		try {
			return object.get(field).getAsLong();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			throw new TelegramException("Telegram response has a non-numeric '" + field + "'", e);
		}
	}

	private static String requiredString(JsonObject object, String field) throws TelegramException {
		String value = optionalString(object, field);
		if (value == null || value.isBlank()) {
			throw new TelegramException("Telegram response is missing '" + field + "'");
		}
		return value;
	}

	private static String optionalString(JsonObject object, String field) {
		if (!object.has(field) || object.get(field).isJsonNull() || !object.get(field).isJsonPrimitive()) {
			return null;
		}
		return object.get(field).getAsString();
	}

	private static String formEncode(Map<String, String> params) {
		StringJoiner joiner = new StringJoiner("&");
		for (Map.Entry<String, String> entry : params.entrySet()) {
			joiner.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
		}
		return joiner.toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
