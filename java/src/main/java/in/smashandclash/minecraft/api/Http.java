package in.smashandclash.minecraft.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The low-level HTTP client (versioned /api/v1 paths): the SDK's Http, in Java.
 * A 429 is retried after its Retry-After; a dropped connection is retried when
 * repeating is safe (a GET, a call marked repeatable, or one whose connection never opened).
 */
public final class Http {
	/** A request that failed below HTTP: no response, or one cut off. */
	public static final class NetworkError extends RuntimeException {
		public final boolean neverSent;

		NetworkError(IOException cause, boolean neverSent) {
			super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
			this.neverSent = neverSent;
		}
	}

	/** The rate-limit state the last response reported. */
	public record RateLimit(String policy, int remaining, int resetSeconds) {}

	static final Gson GSON = new Gson();
	private static final Pattern RL = Pattern.compile("^\"([^\"]+)\"");
	private static final Pattern RL_R = Pattern.compile(";r=(\\d+)");
	private static final Pattern RL_T = Pattern.compile(";t=(\\d+)");

	public final String baseUrl;
	private final String sdk;
	private final int retries;
	private final HttpClient client;
	public volatile RateLimit rateLimit;

	public Http(String baseUrl, String sdkHeader, int retries) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.sdk = sdkHeader;
		this.retries = retries;
		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	public JsonElement get(String path, Map<String, Object> query, String token) {
		return request("GET", path, query, null, token, false);
	}

	public JsonElement post(String path, JsonObject body, String token, boolean repeatable) {
		return request("POST", path, null, body, token, repeatable);
	}

	public JsonElement request(String method, String path, Map<String, Object> query, JsonObject body, String token, boolean repeatable) {
		StringBuilder url = new StringBuilder(baseUrl).append(path);
		if (query != null) {
			char sep = '?';
			for (Map.Entry<String, Object> e : query.entrySet()) {
				if (e.getValue() == null) continue;
				url.append(sep).append(enc(e.getKey())).append('=').append(enc(String.valueOf(e.getValue())));
				sep = '&';
			}
		}
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.toString()))
			.timeout(Duration.ofSeconds(40))
			.header("accept", "application/json")
			.header("x-sdk", sdk);
		if (token != null) b.header("authorization", "Bearer " + token);
		if (body != null) b.header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
		else b.method(method, HttpRequest.BodyPublishers.noBody());
		HttpRequest req = b.build();
		boolean safe = "GET".equals(method) || repeatable;
		for (int attempt = 0; ; attempt++) {
			HttpResponse<String> res;
			try {
				res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException e) {
				boolean neverSent = e instanceof ConnectException || e instanceof HttpConnectTimeoutException || e instanceof UnknownHostException
					|| e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException;
				if (attempt < retries && (safe || neverSent)) {
					sleep((long) (250 * Math.pow(3, attempt)));
					continue;
				}
				throw new NetworkError(e, neverSent);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new NetworkError(new IOException("interrupted"), false);
			}
			res.headers().firstValue("ratelimit").ifPresent(this::readRateLimit);
			if (res.statusCode() == 429 && attempt < retries) {
				long after = res.headers().firstValue("retry-after").map(s -> {
					try {
						return Long.parseLong(s.trim());
					} catch (NumberFormatException ex) {
						return 1L;
					}
				}).orElse(1L);
				sleep(Math.max(1, after) * 1000);
				continue;
			}
			String text = res.body();
			JsonElement data = null;
			if (text != null && !text.isEmpty()) {
				try {
					data = JsonParser.parseString(text);
				} catch (RuntimeException ignored) {
					data = null;
				}
			}
			if (res.statusCode() >= 400) throw problemOf(res.statusCode(), data, text);
			return data;
		}
	}

	private void readRateLimit(String rl) {
		Matcher p = RL.matcher(rl);
		Matcher r = RL_R.matcher(rl);
		Matcher t = RL_T.matcher(rl);
		rateLimit = new RateLimit(p.find() ? p.group(1) : "", r.find() ? Integer.parseInt(r.group(1)) : 0, t.find() ? Integer.parseInt(t.group(1)) : 0);
	}

	/** The error a failed response stands for: problem+json ({code, error, hint}), a platform error, or plain text. */
	static SmashAndClashError problemOf(int status, JsonElement data, String text) {
		String message = null, code = null, hint = null;
		if (data != null && data.isJsonObject()) {
			JsonObject p = data.getAsJsonObject();
			message = str(p.get("error"));
			if (p.has("error") && p.get("error").isJsonObject()) {
				JsonObject nested = p.getAsJsonObject("error");
				if (message == null) message = str(nested.get("message"));
				if (str(nested.get("code")) != null) code = str(nested.get("code")).toLowerCase();
			}
			if (message == null) message = str(p.get("detail"));
			if (str(p.get("code")) != null) code = str(p.get("code"));
			hint = str(p.get("hint"));
		} else if (text != null && !text.isBlank()) {
			message = text.split("\n")[0].trim();
		}
		if (message == null) message = "HTTP " + status;
		if (code == null) code = status == 404 ? "not_found" : "error";
		return new SmashAndClashError(status, code, message, hint);
	}

	private static String str(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
		String s = e.getAsString().trim();
		return s.isEmpty() ? null : s;
	}

	static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	static Map<String, Object> query(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
