package com.cubiccadence.client.provider.netease;

import com.cubiccadence.auth.AuthSession;
import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.auth.AuthorizationStatus;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the independently deployed {@code api-enhanced} service.
 * The reverse-engineered service issues a NetEase cookie, so the session model
 * carries that cookie instead of official access/refresh tokens.
 */
public final class NeteaseAuthClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long SESSION_TTL_MS = Duration.ofDays(7).toMillis();
    private static final long QRCODE_TTL_MS = Duration.ofMinutes(5).toMillis();
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Gson GSON = new Gson();

    private final URI baseUri;
    private final HttpClient httpClient;

    public NeteaseAuthClient(URI baseUri) {
        this(baseUri, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    NeteaseAuthClient(URI baseUri, HttpClient httpClient) {
        this.baseUri = validateBaseUri(baseUri);
        this.httpClient = httpClient;
    }

    public CompletableFuture<AuthorizationChallenge> beginLogin() {
        return getJson("/login/qr/key", Map.of())
                .thenCompose(keyBody -> {
                    String unikey = field(keyBody, "unikey");
                    if (unikey == null || unikey.isBlank()) {
                        throw new GatewayException("api-enhanced returned no QR key");
                    }
                    return getJson("/login/qr/create", Map.of("key", unikey))
                            .thenApply(createBody -> {
                                String qrUrl = field(createBody, "qrurl");
                                if (qrUrl == null || qrUrl.isBlank()) {
                                    throw new GatewayException("api-enhanced returned no QR url");
                                }
                                long now = System.currentTimeMillis();
                                return new AuthorizationChallenge(
                                        unikey, qrUrl, qrUrl, now + QRCODE_TTL_MS, POLL_INTERVAL_MS
                                );
                            });
                });
    }

    public CompletableFuture<AuthorizationResult> pollAuthorization(String unikey) {
        return getJson("/login/qr/check", Map.of("key", unikey))
                .thenApply(body -> {
                    int code = intField(body, "code", 800);
                    return switch (code) {
                        case 801 -> new AuthorizationResult(AuthorizationStatus.PENDING, null, null);
                        case 802 -> new AuthorizationResult(AuthorizationStatus.SCANNED, null, null);
                        case 803 -> {
                            String cookie = field(body, "cookie");
                            if (cookie == null || cookie.isBlank()) {
                                throw new GatewayException("api-enhanced login returned no cookie");
                            }
                            yield new AuthorizationResult(
                                    AuthorizationStatus.AUTHORIZED,
                                    new AuthSession("netease", cookie, System.currentTimeMillis() + SESSION_TTL_MS),
                                    null
                            );
                        }
                        default -> new AuthorizationResult(AuthorizationStatus.EXPIRED, null, null);
                    };
                });
    }

    public CompletableFuture<AuthSession> refresh(AuthSession session) {
        return getJson("/login/refresh", Map.of("cookie", session.cookie()))
                .thenApply(body -> {
                    String cookie = field(body, "cookie");
                    if (cookie == null || cookie.isBlank()) {
                        throw new GatewayException("api-enhanced refresh returned no cookie");
                    }
                    return new AuthSession("netease", cookie, System.currentTimeMillis() + SESSION_TTL_MS);
                });
    }

    public CompletableFuture<Void> logout(AuthSession session) {
        return getJson("/logout", Map.of("cookie", session.cookie()))
                .thenApply(ignored -> null);
    }

    private CompletableFuture<JsonObject> getJson(String path, Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path, query))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Cubic-Cadence/1.0")
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    byte[] body = response.body();
                    if (body.length > MAX_RESPONSE_BYTES) {
                        throw new GatewayException("api-enhanced response was too large");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new GatewayException("api-enhanced returned HTTP " + response.statusCode());
                    }
                    return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                });
    }

    private URI resolve(String path, Map<String, String> query) {
        String base = baseUri.toString();
        StringBuilder url = new StringBuilder(base.endsWith("/")
                ? base.substring(0, base.length() - 1) : base);
        url.append(path);
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(entry.getKey()).append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(url.toString());
    }

    private static String field(JsonObject body, String name) {
        JsonElement direct = body.get(name);
        if (direct != null && !direct.isJsonNull()) {
            return direct.getAsString();
        }
        JsonElement data = body.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement nested = data.getAsJsonObject().get(name);
            if (nested != null && !nested.isJsonNull()) {
                return nested.getAsString();
            }
        }
        return null;
    }

    private static int intField(JsonObject body, String name, int defaultValue) {
        JsonElement value = body.get(name);
        if (value != null && value.isJsonPrimitive()) {
            try {
                return value.getAsInt();
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static URI validateBaseUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("api-enhanced base URL is invalid");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException("api-enhanced base URL must use HTTPS (HTTP is local-only)");
        }
        return uri;
    }

    public static final class GatewayException extends RuntimeException {
        GatewayException(String message) {
            super(message);
        }

        GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
