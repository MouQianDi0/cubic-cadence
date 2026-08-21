package com.cubiccadence.client.provider.netease;

import com.cubiccadence.auth.AuthorizationChallenge;
import com.cubiccadence.auth.AuthorizationResult;
import com.cubiccadence.auth.AuthorizationStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseAuthClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void bypassesQrCachesAndMapsAuthorizationProgress() throws IOException {
        List<URI> requests = new CopyOnWriteArrayList<>();
        AtomicInteger checks = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requests, checks));
        server.start();

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        NeteaseAuthClient client = new NeteaseAuthClient(baseUri);

        AuthorizationChallenge challenge = client.beginLogin().join();
        AuthorizationResult pending = client.pollAuthorization(challenge.authorizationId()).join();
        AuthorizationResult scanned = client.pollAuthorization(challenge.authorizationId()).join();
        AuthorizationResult authorized = client.pollAuthorization(challenge.authorizationId()).join();

        assertEquals("test-key", challenge.authorizationId());
        assertEquals(3_000L, challenge.pollIntervalMs());
        assertEquals(AuthorizationStatus.PENDING, pending.status());
        assertEquals(AuthorizationStatus.SCANNED, scanned.status());
        assertEquals(AuthorizationStatus.AUTHORIZED, authorized.status());
        assertNotNull(authorized.session());
        assertEquals("MUSIC_U=test-cookie", authorized.session().cookie());

        assertEquals(List.of(
                "/login/qr/key",
                "/login/qr/create",
                "/login/qr/check",
                "/login/qr/check",
                "/login/qr/check"
        ), requests.stream().map(URI::getPath).toList());

        List<String> timestamps = new ArrayList<>();
        for (URI request : requests) {
            String timestamp = queryParameters(request).get("timestamp");
            assertNotNull(timestamp, () -> "missing timestamp in " + request);
            assertTrue(Long.parseLong(timestamp) > 0L);
            timestamps.add(timestamp);
        }
        assertEquals(timestamps.size(), timestamps.stream().distinct().count());
        assertEquals("test-key", queryParameters(requests.get(1)).get("key"));
        assertEquals("test-key", queryParameters(requests.get(2)).get("key"));
    }

    private static void respond(
            HttpExchange exchange,
            List<URI> requests,
            AtomicInteger checks
    ) throws IOException {
        URI requestUri = exchange.getRequestURI();
        requests.add(requestUri);
        String body = switch (requestUri.getPath()) {
            case "/login/qr/key" -> "{\"code\":200,\"data\":{\"unikey\":\"test-key\"}}";
            case "/login/qr/create" -> "{\"code\":200,\"data\":{\"qrurl\":\"https://music.163.com/login?codekey=test-key\"}}";
            case "/login/qr/check" -> switch (checks.getAndIncrement()) {
                case 0 -> "{\"code\":801}";
                case 1 -> "{\"code\":802}";
                default -> "{\"code\":803,\"cookie\":\"MUSIC_U=test-cookie\"}";
            };
            default -> throw new AssertionError("unexpected request path: " + requestUri.getPath());
        };
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            parameters.put(key, value);
        }
        return parameters;
    }
}
