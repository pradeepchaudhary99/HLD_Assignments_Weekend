import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** GIVEN. Small HTTP helpers on top of the JDK's built-in server. */
final class Http {
    static final class BadRequest extends RuntimeException {
        BadRequest(String m) { super(m); }
    }

    /** Throw this from anywhere to answer with a specific status, e.g. new Http.ApiError(429, "slow down"). */
    static final class ApiError extends RuntimeException {
        final int status;
        ApiError(int status, String m) { super(m); this.status = status; }
    }

    /** Wraps a handler: TODOs become 501, bad JSON becomes 400, bugs become 500. */
    static HttpHandler safe(HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (UnsupportedOperationException e) {
                trySend(ex, 501, Map.of("error", e.getMessage()));
            } catch (ApiError e) {
                trySend(ex, e.status, Map.of("error", e.getMessage()));
            } catch (Redis.RedisError e) {
                trySend(ex, 500, Map.of("error", "Redis said: " + e.getMessage()));
            } catch (BadRequest | IllegalArgumentException e) {
                trySend(ex, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                trySend(ex, 500, Map.of("error", "Server bug: " + e));
            } finally {
                ex.close();
            }
        };
    }

    private static void trySend(HttpExchange ex, int status, Object body) {
        try { send(ex, status, body); } catch (Exception ignored) { /* response already started */ }
    }

    static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) ex.getResponseHeaders().set("Content-Type", "application/json");
        System.out.printf("%-6s %-40s -> %d%n", ex.getRequestMethod(), ex.getRequestURI(), status);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static void methodNotAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        send(ex, 405, Map.of("error", ex.getRequestMethod() + " not allowed here. Allowed: " + allow));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpExchange ex) throws IOException {
        return parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String raw) {
        if (raw.isBlank()) throw new BadRequest("Request body is required");
        Object parsed;
        try { parsed = Json.parse(raw); } catch (RuntimeException e) { throw new BadRequest("Malformed JSON: " + e.getMessage()); }
        if (!(parsed instanceof Map)) throw new BadRequest("Body must be a JSON object");
        return (Map<String, Object>) parsed;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                  kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }
}

