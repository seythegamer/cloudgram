package dev.cloudgram.core.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real HTTP client against a loopback bot server.
 *
 * <p>This covers what a stub transport cannot: URL building, form encoding, status handling and
 * JSON envelope parsing. It never talks to Telegram.
 */
class HttpTelegramTransportTest {
	private static final String TOKEN = "123456789:TEST-TOKEN-NOT-A-REAL-BOT-0000";

	private HttpServer server;
	private HttpTelegramTransport transport;
	private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
	private volatile Response response;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/bot", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String method = path.substring(path.lastIndexOf('/') + 1);
			String query = exchange.getRequestURI().getRawQuery();
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Recorded(path, method, exchange.getRequestMethod(), query, body));

			Response current = response;
			byte[] payload = current.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(current.status(), payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		});
		server.start();

		transport = new HttpTelegramTransport(
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
				Duration.ofSeconds(5));
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void getMeParsesTheBotIdentity() throws TelegramException {
		response = new Response(200, """
				{"ok":true,"result":{"id":123456789,"is_bot":true,
				"first_name":"Cloudgram","username":"cloudgram_bot"}}
				""");

		BotInfo bot = transport.getMe(TOKEN);

		assertEquals(123456789L, bot.id());
		assertEquals("cloudgram_bot", bot.username());
		assertEquals("Cloudgram", bot.firstName());
		assertEquals("@cloudgram_bot", bot.displayName());

		Recorded request = requests.getFirst();
		assertEquals("getMe", request.method());
		assertTrue(request.path().endsWith("/bot" + TOKEN + "/getMe"));
		assertEquals("GET", request.httpMethod());
	}

	@Test
	void unauthorizedTokenBecomesAnUnauthorizedException() {
		response = new Response(401, """
				{"ok":false,"error_code":401,"description":"Unauthorized"}
				""");

		TelegramException error = assertThrows(TelegramException.class, () -> transport.getMe(TOKEN));

		assertTrue(error.isUnauthorized());
		assertEquals(401, error.code());
		assertTrue(error.getMessage().contains("Unauthorized"));
		assertFalse(error.getMessage().contains("TEST-TOKEN"), "the token must never appear in errors");
	}

	@Test
	void rateLimitsExposeRetryAfter() {
		response = new Response(429, """
				{"ok":false,"error_code":429,"description":"Too Many Requests: retry later",
				 "parameters":{"retry_after":17}}
				""");

		TelegramException error = assertThrows(TelegramException.class, () -> transport.getMe(TOKEN));

		assertTrue(error.isRateLimited());
		assertEquals(17, error.retryAfterSeconds());
	}

	@Test
	void getUpdatesParsesMessagesAndIgnoresOtherUpdateTypes() throws TelegramException {
		response = new Response(200, """
				{"ok":true,"result":[
				  {"update_id":41,"message":{"message_id":7,
				    "from":{"id":1,"is_bot":false,"first_name":"sey"},
				    "chat":{"id":42,"type":"private","first_name":"sey","last_name":"K"},
				    "date":1800000000,"text":"/start"}},
				  {"update_id":42,"edited_message":{"message_id":8,
				    "chat":{"id":42,"type":"private"},"text":"edited"}},
				  {"update_id":43,"message":{"message_id":9,
				    "chat":{"id":-100123,"type":"channel","title":"Channel"},
				    "date":1800000001,"text":"hello"}}
				]}
				""");

		List<IncomingMessage> updates = transport.getUpdates(TOKEN, 40, 0);

		assertEquals(2, updates.size());
		assertEquals(41, updates.get(0).updateId());
		assertEquals(42, updates.get(0).chatId());
		assertEquals("sey", updates.get(0).chatTitle());
		assertTrue(updates.get(0).isPrivate());
		assertTrue(updates.get(0).isStartCommand());
		assertEquals("Channel", updates.get(1).chatTitle());
		assertEquals("channel", updates.get(1).chatType());

		Map<String, String> query = decodeForm(requests.getFirst().query());
		assertEquals("40", query.get("offset"));
		assertEquals("0", query.get("timeout"));
		assertEquals("[\"message\"]", query.get("allowed_updates"));
	}

	@Test
	void getUpdatesWithNoPendingMessagesReturnsAnEmptyList() throws TelegramException {
		response = new Response(200, "{\"ok\":true,\"result\":[]}");

		assertTrue(transport.getUpdates(TOKEN, 5, 0).isEmpty());
	}

	@Test
	void sendMessagePostsFormFieldsAndReturnsTheMessageId() throws TelegramException {
		response = new Response(200, """
				{"ok":true,"result":{"message_id":77,"date":1800000000,
				 "chat":{"id":42,"type":"private"},"text":"hi"}}
				""");

		long messageId = transport.sendMessage(TOKEN, 42L, "Cloudgram is connected\nDevice: DESKTOP");

		assertEquals(77, messageId);
		Recorded request = requests.getFirst();
		assertEquals("sendMessage", request.method());
		assertEquals("POST", request.httpMethod());
		Map<String, String> form = decodeForm(request.body());
		assertEquals("42", form.get("chat_id"));
		assertEquals("Cloudgram is connected\nDevice: DESKTOP", form.get("text"));
		assertEquals("true", form.get("disable_web_page_preview"));
	}

	@Test
	void pinChatMessagePostsTheMessageId() throws TelegramException {
		response = new Response(200, "{\"ok\":true,\"result\":true}");

		transport.pinChatMessage(TOKEN, 42L, 77L);

		Recorded request = requests.getFirst();
		assertEquals("pinChatMessage", request.method());
		Map<String, String> form = decodeForm(request.body());
		assertEquals("42", form.get("chat_id"));
		assertEquals("77", form.get("message_id"));
	}

	@Test
	void unreadableResponseIsReportedWithTheHttpStatus() {
		response = new Response(502, "<html>bad gateway</html>");

		TelegramException error = assertThrows(TelegramException.class, () -> transport.getMe(TOKEN));

		assertTrue(error.getMessage().contains("502"));
	}

	@Test
	void rejectsInvalidArgumentsBeforeSending() {
		assertThrows(IllegalArgumentException.class, () -> transport.getUpdates(TOKEN, -2, 0));
		assertThrows(IllegalArgumentException.class, () -> transport.getUpdates(TOKEN, 0, -1));
		assertThrows(IllegalArgumentException.class, () -> transport.sendMessage(TOKEN, 42L, " "));
		assertThrows(IllegalArgumentException.class, () -> transport.getMe(" "));
	}

	private static Map<String, String> decodeForm(String encoded) {
		Map<String, String> values = new LinkedHashMap<>();
		if (encoded == null || encoded.isEmpty()) {
			return values;
		}
		for (String pair : encoded.split("&")) {
			int equals = pair.indexOf('=');
			String key = equals < 0 ? pair : pair.substring(0, equals);
			String value = equals < 0 ? "" : pair.substring(equals + 1);
			values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
					URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return values;
	}

	private record Recorded(String path, String method, String httpMethod, String query, String body) {
	}

	private record Response(int status, String body) {
	}
}
