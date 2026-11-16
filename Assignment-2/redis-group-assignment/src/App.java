import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GIVEN — the QuickBite API server. It only routes HTTP to your feature classes; all Redis work
 * lives in the feature files. You shouldn't need to edit this file (keeps merge conflicts away).
 *
 *   ./run.sh            -> http://localhost:8080   (demo page with a button for every feature)
 */
public class App {

    static final int PORT = Integer.parseInt(Optional.ofNullable(System.getenv("PORT")).orElse("8080"));

    public static void main(String[] args) throws IOException {
        System.out.println("Redis: " + Redis.str("PING") + " from " + Redis.host() + ":" + Redis.port());
        try { OrderPipeline.MAIN.ensureGroup(); } catch (RuntimeException ignored) { }
        startRiderSimulator();

        System.setProperty("sun.net.httpserver.nodelay", "true");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Http.safe(App::route));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("QuickBite API on http://localhost:" + PORT + "  -- open it in a browser");
    }

    static void route(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String[] p = path.split("/");     // "/cart/u1/items" -> ["", "cart", "u1", "items"]
        Map<String, String> q = Http.query(ex);

        // ---------- demo page ----------
        if (path.equals("/") && m.equals("GET")) { sendText(ex, 200, "text/html; charset=utf-8", DemoPage.HTML); return; }

        // ---------- Track A: menu cache ----------
        if (p.length == 4 && p[1].equals("restaurants") && p[3].equals("menu") && m.equals("GET")) {
            long t = System.nanoTime();
            MenuCache.Result r = MenuCache.getMenu(p[2]);
            if (r == null) { Http.send(ex, 404, Map.of("error", "No restaurant " + p[2])); return; }
            ex.getResponseHeaders().set("X-Cache", r.hit() ? "HIT" : "MISS");
            ex.getResponseHeaders().set("X-Time-Ms", String.valueOf((System.nanoTime() - t) / 1_000_000));
            sendText(ex, 200, "application/json", r.json());
            return;
        }
        if (p.length == 5 && p[1].equals("restaurants") && p[3].equals("menu") && m.equals("PUT")) {
            int price = ((Number) Http.body(ex).get("price")).intValue();
            boolean ok = MenuCache.updatePrice(p[2], p[4], price);
            Http.send(ex, ok ? 200 : 404, Map.of("updated", ok));
            return;
        }
        // ---------- Track A: cart ----------
        if (p.length >= 3 && p[1].equals("cart")) {
            String user = p[2];
            if (p.length == 4 && p[3].equals("items") && m.equals("POST")) {
                Map<String, Object> b = Http.body(ex);
                Cart.addItem(user, (String) b.get("itemId"), ((Number) b.getOrDefault("qty", 1L)).intValue());
                Http.send(ex, 200, Cart.view(user));
            } else if (m.equals("GET")) Http.send(ex, 200, Cart.view(user));
            else if (m.equals("DELETE")) { Cart.clear(user); Http.send(ex, 204, null); }
            else Http.methodNotAllowed(ex, "GET, POST, DELETE");
            return;
        }

        // ---------- Track B: auth ----------
        if (path.equals("/auth/otp") && m.equals("POST")) {
            String phone = (String) Http.body(ex).get("phone");
            String code = OtpAuth.requestOtp(phone);
            // devCode is ONLY for this classroom demo. A real API never returns the OTP!
            Http.send(ex, 200, Map.of("sent", true, "devCode", code));
            return;
        }
        if (path.equals("/auth/verify") && m.equals("POST")) {
            Map<String, Object> b = Http.body(ex);
            OtpAuth.VerifyResult r = OtpAuth.verifyOtp((String) b.get("phone"), (String) b.get("code"));
            int status = switch (r.status()) { case "OK" -> 200; case "LOCKED" -> 423; default -> 401; };
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", r.status());
            if (r.token() != null) body.put("token", r.token());
            Http.send(ex, status, body);
            return;
        }
        if (path.equals("/auth/me") && m.equals("GET")) {
            Map<String, String> s = OtpAuth.session(bearer(ex));
            if (s == null) Http.send(ex, 401, Map.of("error", "Not logged in"));
            else Http.send(ex, 200, s);
            return;
        }
        if (path.equals("/auth/logout") && m.equals("POST")) {
            Http.send(ex, 200, Map.of("loggedOut", OtpAuth.logout(bearer(ex))));
            return;
        }
        if (path.equals("/ratelimit-demo") && m.equals("POST")) {     // hit it 7 times quickly
            String user = q.getOrDefault("user", "demo");
            RateLimiter.Decision d = "fixed".equals(q.get("algo"))
                    ? RateLimiter.fixedWindow(user, RateLimiter.LIMIT, RateLimiter.WINDOW_SECONDS)
                    : RateLimiter.check(user);
            ex.getResponseHeaders().set("X-RateLimit-Remaining", String.valueOf(d.remaining()));
            if (!d.allowed()) ex.getResponseHeaders().set("Retry-After", String.valueOf(d.retryAfterSeconds()));
            Http.send(ex, d.allowed() ? 200 : 429, d);
            return;
        }

        // ---------- Track C: flash sale ----------
        if (p.length >= 3 && p[1].equals("sales")) {
            String sale = p[2];
            if (p.length == 4 && p[3].equals("start") && m.equals("POST")) {
                FlashSale.start(sale, ((Number) Http.body(ex).getOrDefault("stock", 100L)).intValue());
            } else if (p.length == 4 && p[3].equals("buy") && m.equals("POST")) {
                String user = (String) Http.body(ex).get("userId");
                String result = "naive".equals(q.get("mode")) ? FlashSale.buyNaive(sale, user) : FlashSale.buy(sale, user);
                Http.send(ex, result.equals("OK") ? 200 : 409, Map.of("result", result));
                return;
            }
            Http.send(ex, 200, Map.of("saleId", sale, "remaining", FlashSale.remaining(sale), "buyers", FlashSale.buyerCount(sale)));
            return;
        }

        // ---------- Track D: leaderboard + riders ----------
        if (p.length >= 2 && p[1].equals("leaderboard") && m.equals("GET")) {
            String board = Leaderboard.thisWeek();
            if (p.length == 3) {
                Leaderboard.Entry e = Leaderboard.rankOf(board, p[2]);
                if (e == null) Http.send(ex, 404, Map.of("error", "No orders this week for " + p[2]));
                else Http.send(ex, 200, e);
            } else {
                Http.send(ex, 200, Map.of("board", board, "top", Leaderboard.top(board, Integer.parseInt(q.getOrDefault("top", "10")))));
            }
            return;
        }
        if (p.length == 4 && p[1].equals("riders") && p[3].equals("location") && m.equals("POST")) {
            Map<String, Object> b = Http.body(ex);
            NearbyRiders.updateLocation(p[2], ((Number) b.get("lat")).doubleValue(), ((Number) b.get("lng")).doubleValue());
            Http.send(ex, 200, Map.of("ok", true));
            return;
        }
        if (path.equals("/riders/nearby") && m.equals("GET")) {
            Http.send(ex, 200, NearbyRiders.nearby(Double.parseDouble(q.get("lat")), Double.parseDouble(q.get("lng")),
                    Double.parseDouble(q.getOrDefault("km", "5")), Integer.parseInt(q.getOrDefault("limit", "5"))));
            return;
        }

        // ---------- Track E: checkout + live updates ----------
        if (path.equals("/checkout") && m.equals("POST")) {
            Checkout.Response r = Checkout.checkout(bearer(ex));
            Http.send(ex, r.status(), r.body());
            return;
        }
        if (p.length == 4 && p[1].equals("orders") && p[3].equals("live") && m.equals("GET")) {
            streamLive(ex, p[2]);
            return;
        }

        Http.send(ex, 404, Map.of("error", "No route for " + m + " " + path));
    }

    /** Server-Sent Events, fed by Redis Pub/Sub (Assignment 1, exercise 03 -- now multi-server). */
    static void streamLive(HttpExchange ex, String orderId) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        LiveUpdates.listen(orderId, new LiveUpdates.Listener() {
            public boolean onMessage(String json) { return write(os, "event: status\ndata: " + json + "\n\n"); }
            public boolean onIdle() { return write(os, ": keep-alive\n\n"); }
        });
        os.close();
    }

    static boolean write(OutputStream os, String s) {
        try {
            os.write(s.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (IOException e) {
            return false;    // browser went away
        }
    }

    static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    static void sendText(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Pretend the 5 riders' phones send GPS every 10 s (they drift a little). */
    static void startRiderSimulator() {
        Thread.startVirtualThread(() -> {
            boolean warned = false;
            while (true) {
                try {
                    for (var e : FakeDb.RIDER_START.entrySet()) {
                        double jitter = ThreadLocalRandom.current().nextDouble(-0.002, 0.002);
                        NearbyRiders.updateLocation(e.getKey(), e.getValue()[0] + jitter, e.getValue()[1] + jitter);
                    }
                } catch (UnsupportedOperationException ex) {
                    if (!warned) System.out.println("(rider simulator idle until NearbyRiders.updateLocation is implemented)");
                    warned = true;
                } catch (RuntimeException ex) {
                    System.out.println("rider simulator: " + ex.getMessage());
                }
                FakeDb.sleep(10_000);
            }
        });
    }
}
