import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/**
 * The automatic checker. Needs Redis running, NOT the app.
 *
 *   ./run.sh check A        only Track A        (A, B, C, D, E or all)
 *
 * Tests call your feature classes directly and then look inside Redis to see what you stored.
 * [STRETCH] tests are optional and never count as failures.
 */
public class Check {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000, 36);

    public static void main(String[] args) throws Exception {
        String track = args.length > 0 ? args[0].toUpperCase() : "ALL";
        try {
            System.out.println("Redis: " + Redis.str("PING") + " at " + Redis.host() + ":" + Redis.port());
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        if (track.equals("ALL") || track.equals("A")) trackA();
        if (track.equals("ALL") || track.equals("B")) trackB();
        if (track.equals("ALL") || track.equals("C")) trackC();
        if (track.equals("ALL") || track.equals("D")) trackD();
        if (track.equals("ALL") || track.equals("E")) trackE();
        Kit.summary(track.equals("ALL") ? "All tracks" : "Track " + track);
        System.exit(Kit.failed == 0 ? 0 : 1);
    }

    // =====================================================================
    static void trackA() {
        Kit.section("A1 MenuCache -- cache-aside (STRING + TTL)");
        Kit.test("first read is a MISS from the DB, second is a HIT from Redis", () -> {
            Redis.call("DEL", "menu:r3");
            int before = FakeDb.MENU_READS.get();
            MenuCache.Result a = MenuCache.getMenu("r3");
            MenuCache.Result b = MenuCache.getMenu("r3");
            Kit.expect(a != null && !a.hit(), "first read should be a MISS");
            Kit.expect(a.json().contains("Butter Chicken"), "menu JSON looks wrong: " + a.json());
            Kit.expect(b.hit(), "second read should be a HIT");
            Kit.expect(b.json().equals(a.json()), "the HIT returned different data than the MISS");
            Kit.expect(FakeDb.MENU_READS.get() - before == 1, "DB was read " + (FakeDb.MENU_READS.get() - before) + " times, expected 1");
        });
        Kit.test("a HIT is fast (no 300 ms DB call)", () -> {
            MenuCache.getMenu("r3");
            long t = System.nanoTime();
            for (int i = 0; i < 20; i++) MenuCache.getMenu("r3");
            long avg = (System.nanoTime() - t) / 20 / 1_000_000;
            Kit.expect(avg < 30, "average HIT took " + avg + " ms");
        });
        Kit.test("menu:r3 has a TTL of 60-70 s (SET ... EX, with jitter)", () -> {
            long ttl = Redis.num("TTL", "menu:r3");
            Kit.expect(ttl > 50 && ttl <= 70, "TTL is " + ttl + " (-1 means you forgot the expiry)");
        });
        Kit.test("unknown restaurant -> null, no crash", () -> Kit.expect(MenuCache.getMenu("r404") == null, "expected null"));
        Kit.test("updatePrice changes the DB AND deletes the cached menu; next read sees the new price", () -> {
            MenuCache.getMenu("r3");
            Kit.expect(MenuCache.updatePrice("r3", "dal-makhani", 239), "updatePrice returned false");
            Kit.expect(Redis.num("EXISTS", "menu:r3") == 0, "menu:r3 is still cached -> users see the old price");
            MenuCache.Result r = MenuCache.getMenu("r3");
            Kit.expect(!r.hit() && r.json().contains("239"), "new price not visible: " + r.json());
            MenuCache.updatePrice("r3", "dal-makhani", 229);
        });
        Kit.test("updatePrice for an unknown item -> false", () -> Kit.expect(!MenuCache.updatePrice("r3", "pizza", 1), "expected false"));
        Kit.stretch("30 concurrent misses on a cold key cause only ONE DB read (getMenuProtected)", () -> {
            Redis.call("DEL", "menu:r4", "lock:menu:r4");
            int before = FakeDb.MENU_READS.get();
            List<MenuCache.Result> rs = Kit.concurrently(30, i -> MenuCache.getMenuProtected("r4"));
            int reads = FakeDb.MENU_READS.get() - before;
            Kit.expect(rs.stream().allMatch(r -> r != null && r.json().contains("Podi")), "some callers got no menu");
            Kit.expect(reads == 1, "DB was read " + reads + " times (stampede!)");
        });

        Kit.section("A2 Cart (HASH)");
        String u = "chk-" + RUN;
        Kit.test("HINCRBY: add 2 dosas, then 1 more -> 3", () -> {
            Cart.clear(u);
            Kit.expect(Cart.addItem(u, "masala-dosa", 2) == 2, "first add should return 2");
            Kit.expect(Cart.addItem(u, "masala-dosa", 1) == 3, "second add should return 3");
            Kit.expect("3".equals(Redis.str("HGET", "cart:" + u, "masala-dosa")), "cart:" + u + " field masala-dosa should be 3");
        });
        Kit.test("items() reads the whole hash", () -> {
            Cart.addItem(u, "filter-coffee", 1);
            Map<String, Integer> items = Cart.items(u);
            Kit.expect(items.equals(Map.of("masala-dosa", 3, "filter-coffee", 1)), "items were " + items);
        });
        Kit.test("removing down to 0 deletes the field", () -> {
            Kit.expect(Cart.addItem(u, "masala-dosa", -5) == 0, "should return 0");
            Kit.expect(Redis.num("HEXISTS", "cart:" + u, "masala-dosa") == 0, "field still there with a negative/zero qty");
        });
        Kit.test("every change resets the cart TTL to 30 min", () -> {
            Redis.call("EXPIRE", "cart:" + u, 100);
            Cart.addItem(u, "filter-coffee", 1);
            long ttl = Redis.num("TTL", "cart:" + u);
            Kit.expect(ttl > 1790 && ttl <= 1800, "TTL is " + ttl);
        });
        Kit.test("unknown item or qty 0 -> IllegalArgumentException", () -> {
            Kit.expectThrows(IllegalArgumentException.class, () -> Cart.addItem(u, "pizza", 1));
            Kit.expectThrows(IllegalArgumentException.class, () -> Cart.addItem(u, "filter-coffee", 0));
        });
        Kit.test("clear() removes the key", () -> {
            Cart.clear(u);
            Kit.expect(Redis.num("EXISTS", "cart:" + u) == 0 && Cart.items(u).isEmpty(), "cart still exists");
        });
    }

    // =====================================================================
    static void trackB() {
        Kit.section("B1 RateLimiter");
        Kit.test("fixed window: 5 allowed (remaining 4..0), 6th refused", () -> {
            String u = "fx-" + RUN;
            for (int i = 1; i <= 5; i++) {
                RateLimiter.Decision d = RateLimiter.fixedWindow(u, 5, 60);
                Kit.expect(d.allowed(), "request " + i + " was refused");
                Kit.expect(d.remaining() == 5 - i, "request " + i + ": remaining should be " + (5 - i) + ", was " + d.remaining());
            }
            RateLimiter.Decision d = RateLimiter.fixedWindow(u, 5, 60);
            Kit.expect(!d.allowed(), "6th request was allowed");
            Kit.expect(d.retryAfterSeconds() >= 1 && d.retryAfterSeconds() <= 60, "retryAfter should be 1..60, was " + d.retryAfterSeconds());
        });
        Kit.test("fixed window: the counter key expires (no memory leak)", () -> {
            String u = "fx2-" + RUN;
            RateLimiter.fixedWindow(u, 5, 60);
            long window = System.currentTimeMillis() / 1000 / 60;
            long ttl = Redis.num("TTL", "rl:fixed:" + u + ":" + window);
            Kit.expect(ttl > 0 && ttl <= 60, "TTL of rl:fixed:" + u + ":" + window + " is " + ttl);
        });
        Kit.test("fixed window: 40 concurrent requests, limit 10 -> exactly 10 allowed", () -> {
            String u = "fx3-" + RUN;
            long ok = Kit.concurrently(40, i -> RateLimiter.fixedWindow(u, 10, 60)).stream().filter(RateLimiter.Decision::allowed).count();
            Kit.expect(ok == 10, ok + " allowed");
        });
        Kit.test("users don't share limits", () -> {
            for (int i = 0; i < 6; i++) RateLimiter.fixedWindow("noisy-" + RUN, 5, 60);
            Kit.expect(RateLimiter.fixedWindow("quiet-" + RUN, 5, 60).allowed(), "a different user was refused");
        });
        Kit.test("sliding window: limit 3 per 1 s -> 4th refused, allowed again after the window slides", () -> {
            String u = "sl-" + RUN;
            for (int i = 0; i < 3; i++) Kit.expect(RateLimiter.slidingWindow(u, 3, 1000).allowed(), "request " + (i + 1) + " refused");
            RateLimiter.Decision d = RateLimiter.slidingWindow(u, 3, 1000);
            Kit.expect(!d.allowed(), "4th request allowed");
            Kit.expect(d.retryAfterSeconds() == 1, "retryAfter should be 1 s, was " + d.retryAfterSeconds());
            FakeDb.sleep(1100);
            Kit.expect(RateLimiter.slidingWindow(u, 3, 1000).allowed(), "still refused after the window passed");
            Kit.expect("zset".equals(Redis.str("TYPE", "rl:sliding:" + u)), "rl:sliding:" + u + " should be a sorted set");
        });
        Kit.test("sliding window: 40 concurrent requests, limit 10 -> exactly 10 (atomic Lua)", () -> {
            String u = "sl2-" + RUN;
            long ok = Kit.concurrently(40, i -> RateLimiter.slidingWindow(u, 10, 60_000)).stream().filter(RateLimiter.Decision::allowed).count();
            Kit.expect(ok == 10, ok + " allowed -- is the check+add atomic?");
        });

        Kit.section("B2 OTP + sessions");
        String phone = "90" + RUN;
        String[] code = new String[1], token = new String[1];
        Kit.test("requestOtp stores a 6-digit code with a 120 s TTL", () -> {
            Redis.call("DEL", "otp:cooldown:" + phone);
            code[0] = OtpAuth.requestOtp(phone);
            Kit.expect(code[0] != null && code[0].matches("\\d{6}"), "code should be 6 digits: " + code[0]);
            Kit.expect(code[0].equals(Redis.str("GET", "otp:" + phone)), "otp:" + phone + " doesn't hold the code");
            long ttl = Redis.num("TTL", "otp:" + phone);
            Kit.expect(ttl > 110 && ttl <= 120, "TTL is " + ttl);
        });
        Kit.test("asking again within 30 s -> ApiError 429", () -> {
            try {
                OtpAuth.requestOtp(phone);
                throw new AssertionError("second request was allowed");
            } catch (Http.ApiError e) {
                Kit.expect(e.status == 429, "status was " + e.status);
            }
        });
        Kit.test("wrong code -> WRONG_CODE; right code -> OK + token", () -> {
            String wrong = code[0].equals("000000") ? "111111" : "000000";
            Kit.expect("WRONG_CODE".equals(OtpAuth.verifyOtp(phone, wrong).status()), "wrong code not rejected");
            OtpAuth.VerifyResult r = OtpAuth.verifyOtp(phone, code[0]);
            Kit.expect("OK".equals(r.status()) && r.token() != null, "correct code gave " + r);
            token[0] = r.token();
        });
        Kit.test("a code works only once", () ->
                Kit.expect("EXPIRED".equals(OtpAuth.verifyOtp(phone, code[0]).status()), "the same code worked twice"));
        Kit.test("session:{token} is a HASH with the phone, TTL 30 min", () -> {
            Kit.expect("hash".equals(Redis.str("TYPE", "session:" + token[0])), "session key should be a hash");
            Map<String, String> s = OtpAuth.session(token[0]);
            Kit.expect(s != null && phone.equals(s.get("phone")), "session() returned " + s);
        });
        Kit.test("using a session pushes its expiry back (sliding)", () -> {
            Redis.call("EXPIRE", "session:" + token[0], 100);
            OtpAuth.session(token[0]);
            long ttl = Redis.num("TTL", "session:" + token[0]);
            Kit.expect(ttl > 1790, "TTL is " + ttl);
        });
        Kit.test("3 wrong guesses -> LOCKED, even for the right code", () -> {
            String p2 = "91" + RUN;
            Redis.call("DEL", "otp:cooldown:" + p2);
            String c = OtpAuth.requestOtp(p2);
            for (int i = 0; i < 3; i++) OtpAuth.verifyOtp(p2, "999999".equals(c) ? "888888" : "999999");
            Kit.expect("LOCKED".equals(OtpAuth.verifyOtp(p2, c).status()), "4th attempt with the right code wasn't LOCKED");
        });
        Kit.test("logout deletes the session; unknown tokens -> null", () -> {
            Kit.expect(OtpAuth.logout(token[0]), "logout returned false");
            Kit.expect(OtpAuth.session(token[0]) == null, "session still works after logout");
            Kit.expect(!OtpAuth.logout(token[0]), "second logout should return false");
            Kit.expect(OtpAuth.session("not-a-token") == null, "unknown token should give null");
        });
    }

    // =====================================================================
    static void trackC() {
        Kit.section("C1 FlashSale (Lua atomicity)");
        Kit.info(() -> {
            String sale = "naive-" + RUN;
            FlashSale.start(sale, 50);
            long sold = Kit.concurrently(200, i -> FlashSale.buyNaive(sale, "u" + i)).stream().filter("OK"::equals).count();
            return "for comparison, buyNaive sold " + sold + " of 50 plates" + (sold > 50 ? "  <- OVERSOLD" : "");
        });
        Kit.test("rules on one thread: OK, ALREADY_BOUGHT, OK, SOLD_OUT", () -> {
            String sale = "s1-" + RUN;
            FlashSale.start(sale, 2);
            List<String> got = List.of(FlashSale.buy(sale, "a"), FlashSale.buy(sale, "a"), FlashSale.buy(sale, "b"), FlashSale.buy(sale, "c"));
            Kit.expect(got.equals(List.of("OK", "ALREADY_BOUGHT", "OK", "SOLD_OUT")), "got " + got);
        });
        Kit.test("400 concurrent buyers (250 users), stock 100 -> exactly 100 sold, stock 0", () -> {
            String sale = "s2-" + RUN;
            FlashSale.start(sale, 100);
            List<String> results = Kit.concurrently(400, i -> FlashSale.buy(sale, "u" + (i % 250)));
            long ok = results.stream().filter("OK"::equals).count();
            Kit.expect(ok == 100, ok + " sold");
            Kit.expect(FlashSale.remaining(sale) == 0, "stock is " + FlashSale.remaining(sale));
            Kit.expect(FlashSale.buyerCount(sale) == 100, "buyers set has " + FlashSale.buyerCount(sale));
        });
        Kit.test("one user hammering buy 30x at once -> 1 plate", () -> {
            String sale = "s3-" + RUN;
            FlashSale.start(sale, 10);
            long ok = Kit.concurrently(30, i -> FlashSale.buy(sale, "greedy")).stream().filter("OK"::equals).count();
            Kit.expect(ok == 1, "greedy got " + ok + " plates");
        });

        Kit.section("C2 RiderLock (distributed lock)");
        Kit.test("tryLock: first caller gets a token, second gets null, key has a TTL", () -> {
            String n = "t1-" + RUN;
            String a = RiderLock.tryLock(n, 5000);
            Kit.expect(a != null, "first tryLock returned null");
            Kit.expect(RiderLock.tryLock(n, 5000) == null, "second tryLock also got the lock!");
            long pttl = Redis.num("PTTL", "lock:" + n);
            Kit.expect(pttl > 0 && pttl <= 5000, "PTTL is " + pttl + " -- a crashed holder would block forever");
        });
        Kit.test("unlock with the wrong token does nothing; the right token releases", () -> {
            String n = "t2-" + RUN;
            String a = RiderLock.tryLock(n, 5000);
            Kit.expect(!RiderLock.unlock(n, "someone-else"), "unlock with a wrong token returned true");
            Kit.expect(Redis.num("EXISTS", "lock:" + n) == 1, "lock was deleted by a stranger");
            Kit.expect(RiderLock.unlock(n, a), "unlock with the right token returned false");
            Kit.expect(Redis.num("EXISTS", "lock:" + n) == 0, "lock still exists");
        });
        Kit.test("an expired lock can be taken again", () -> {
            String n = "t3-" + RUN;
            RiderLock.tryLock(n, 200);
            FakeDb.sleep(300);
            Kit.expect(RiderLock.tryLock(n, 1000) != null, "lock never expired");
        });
        Kit.test("slow holder's late unlock must NOT delete the next holder's lock", () -> {
            String n = "t4-" + RUN;
            String slow = RiderLock.tryLock(n, 150);
            FakeDb.sleep(250);                         // slow holder's lock expired...
            String next = RiderLock.tryLock(n, 5000);  // ...someone else took it
            Kit.expect(next != null, "couldn't take the expired lock");
            Kit.expect(!RiderLock.unlock(n, slow), "the slow holder's unlock returned true");
            Kit.expect(next.equals(Redis.str("GET", "lock:" + n)), "the new holder lost its lock");
        });
        Kit.test("30 orders at once, 5 riders -> 5 assigned, each rider exactly once", () -> {
            List<String> riders = List.of("ra-" + RUN, "rb-" + RUN, "rc-" + RUN, "rd-" + RUN, "re-" + RUN);
            riders.forEach(RiderLock::release);
            List<String> got = Kit.concurrently(30, i -> RiderLock.assignRider("ord-" + i, riders));
            List<String> assigned = got.stream().filter(Objects::nonNull).toList();
            Kit.expect(assigned.size() == 5, assigned.size() + " assignments for 5 riders");
            Kit.expect(new HashSet<>(assigned).size() == 5, "a rider got two orders: " + assigned);
            riders.forEach(RiderLock::release);
        });
    }

    // =====================================================================
    static void trackD() {
        Kit.section("D1 Leaderboard (SORTED SET)");
        String board = "lb:test:" + RUN;
        Kit.test("recordOrder increments and returns the new score", () -> {
            for (int i = 0; i < 3; i++) Leaderboard.recordOrder(board, "r1");
            for (int i = 0; i < 4; i++) Leaderboard.recordOrder(board, "r2");
            Leaderboard.recordOrder(board, "r3");
            Kit.expect(Leaderboard.recordOrder(board, "r2") == 5, "r2 should be at 5");
            Kit.expect("zset".equals(Redis.str("TYPE", board)), board + " should be a sorted set");
            long ttl = Redis.num("TTL", board);
            Kit.expect(ttl > 7 * 86400, "board needs an ~8 day expiry, TTL is " + ttl);
        });
        Kit.test("top(2) -> Biryani Brothers (5), Dosa Junction (3), ranks from 1", () -> {
            List<Leaderboard.Entry> top = Leaderboard.top(board, 2);
            Kit.expect(top.size() == 2, "got " + top.size() + " entries");
            Kit.expect(top.get(0).equals(new Leaderboard.Entry(1, "r2", "Biryani Brothers", 5)), "first was " + top.get(0));
            Kit.expect(top.get(1).equals(new Leaderboard.Entry(2, "r1", "Dosa Junction", 3)), "second was " + top.get(1));
        });
        Kit.test("rankOf(r3) -> #3 with 1 order; rankOf(r5) -> null", () -> {
            Kit.expect(new Leaderboard.Entry(3, "r3", "Punjab Da Dhaba", 1).equals(Leaderboard.rankOf(board, "r3")), "got " + Leaderboard.rankOf(board, "r3"));
            Kit.expect(Leaderboard.rankOf(board, "r5") == null, "r5 has no orders, expected null");
        });
        Kit.test("empty board -> empty list", () -> Kit.expect(Leaderboard.top("lb:empty:" + RUN, 10).isEmpty(), "expected empty"));
        Redis.call("DEL", board);

        Kit.section("D2 NearbyRiders (GEO)");
        String kora = "kora-" + RUN, indira = "indira-" + RUN, white = "white-" + RUN;
        Kit.test("updateLocation stores lng/lat correctly + a 30 s heartbeat", () -> {
            NearbyRiders.updateLocation(kora, 12.9352, 77.6245);
            NearbyRiders.updateLocation(indira, 12.9719, 77.6412);
            NearbyRiders.updateLocation(white, 12.9698, 77.7500);
            @SuppressWarnings("unchecked")
            List<Object> pos = (List<Object>) Redis.list("GEOPOS", "riders:geo", kora).get(0);
            Kit.expect(pos != null, kora + " not in riders:geo");
            double lng = Double.parseDouble((String) pos.get(0)), lat = Double.parseDouble((String) pos.get(1));
            Kit.expect(Math.abs(lat - 12.9352) < 0.001 && Math.abs(lng - 77.6245) < 0.001,
                    "stored lng=" + lng + " lat=" + lat + " -- GEOADD takes LONGITUDE first");
            long ttl = Redis.num("TTL", "rider:" + kora + ":alive");
            Kit.expect(ttl > 25 && ttl <= 30, "heartbeat TTL is " + ttl);
        });
        Kit.test("nearby(Koramangala, 6 km) -> Koramangala rider then Indiranagar, not Whitefield", () -> {
            List<NearbyRiders.Nearby> got = mine(NearbyRiders.nearby(12.9340, 77.6260, 6, 50));
            Kit.expect(got.size() == 2, "expected 2 test riders, got " + got);
            Kit.expect(got.get(0).riderId().equals(kora) && got.get(1).riderId().equals(indira), "wrong order: " + got);
            Kit.expect(got.get(0).distanceKm() < 0.5, "Koramangala rider should be < 0.5 km, was " + got.get(0).distanceKm());
            Kit.expect(got.get(1).distanceKm() > 3 && got.get(1).distanceKm() < 6, "Indiranagar should be ~4.5 km, was " + got.get(1).distanceKm());
        });
        Kit.test("rider with no heartbeat is skipped AND removed from riders:geo", () -> {
            Redis.call("DEL", "rider:" + kora + ":alive");
            List<NearbyRiders.Nearby> got = mine(NearbyRiders.nearby(12.9340, 77.6260, 6, 50));
            Kit.expect(got.stream().noneMatch(n -> n.riderId().equals(kora)), "dead rider still returned");
            Kit.expect(Redis.call("ZSCORE", "riders:geo", kora) == null, "dead rider still in riders:geo");
        });
        Kit.test("goOffline removes the rider", () -> {
            NearbyRiders.goOffline(indira);
            Kit.expect(Redis.call("ZSCORE", "riders:geo", indira) == null, "still in riders:geo");
            Kit.expect(Redis.num("EXISTS", "rider:" + indira + ":alive") == 0, "alive key still there");
        });
        Redis.call("ZREM", "riders:geo", kora, indira, white);
    }

    static List<NearbyRiders.Nearby> mine(List<NearbyRiders.Nearby> all) {
        return all.stream().filter(n -> n.riderId().endsWith("-" + RUN)).toList();
    }

    // =====================================================================
    static void trackE() {
        Kit.section("E1 LiveUpdates (Pub/Sub)");
        Kit.test("publish with nobody listening -> 0 receivers (the message is gone)", () ->
                Kit.expect(LiveUpdates.publish("nobody-" + RUN, "PREPARING") == 0, "expected 0"));
        Kit.test("listen() receives ACCEPTED, PREPARING, DELIVERED in order, then returns", () -> {
            String order = "live-" + RUN;
            List<String> got = new CopyOnWriteArrayList<>();
            Thread t = Thread.startVirtualThread(() -> LiveUpdates.listen(order, new LiveUpdates.Listener() {
                public boolean onMessage(String json) { got.add(json); return true; }
                public boolean onIdle() { return true; }
            }));
            FakeDb.sleep(300);
            long receivers = LiveUpdates.publish(order, "ACCEPTED");
            LiveUpdates.publish(order, "PREPARING");
            LiveUpdates.publish(order, "DELIVERED");
            t.join(3000);
            Kit.expect(receivers == 1, "publish should reach 1 subscriber, reached " + receivers);
            Kit.expect(got.size() == 3, "received " + got.size() + " messages: " + got);
            Kit.expect(got.get(0).contains("ACCEPTED") && got.get(2).contains("DELIVERED"), "wrong order: " + got);
            Kit.expect(got.get(0).contains("\"orderId\":\"" + order + "\""), "message should include orderId: " + got.get(0));
            Kit.expect(!t.isAlive(), "listen() didn't return after DELIVERED");
        });
        Kit.test("listen() stops when the listener returns false (browser left)", () -> {
            String order = "live2-" + RUN;
            Thread t = Thread.startVirtualThread(() -> LiveUpdates.listen(order, new LiveUpdates.Listener() {
                public boolean onMessage(String json) { return false; }
                public boolean onIdle() { return false; }
            }));
            FakeDb.sleep(300);
            LiveUpdates.publish(order, "ACCEPTED");
            t.join(3000);
            Kit.expect(!t.isAlive(), "still listening");
        });

        Kit.section("E2 OrderPipeline (STREAM + consumer group)");
        OrderPipeline p = new OrderPipeline("test:orders:" + RUN, "kitchen");
        p.ensureGroup();
        Kit.test("add() appends 10 entries and returns ids", () -> {
            for (int i = 0; i < 10; i++) {
                String id = p.add(Map.of("orderId", "o" + i, "restaurantId", "r1"));
                Kit.expect(id != null && id.matches("\\d+-\\d+"), "id looks wrong: " + id);
            }
            Kit.expect(Redis.num("XLEN", p.stream) == 10, "XLEN is " + Redis.num("XLEN", p.stream));
        });
        Kit.test("two workers in one group split the 10 entries -- nobody gets the same one", () -> {
            List<OrderPipeline.Entry> a = p.readNew("w1", 6, 100);
            List<OrderPipeline.Entry> b = p.readNew("w2", 10, 100);
            Set<String> ids = new HashSet<>();
            a.forEach(e -> ids.add(e.id()));
            b.forEach(e -> ids.add(e.id()));
            Kit.expect(a.size() == 6 && b.size() == 4, "w1 got " + a.size() + ", w2 got " + b.size());
            Kit.expect(ids.size() == 10, "duplicate deliveries");
            Kit.expect(a.get(0).fields().get("orderId").equals("o0"), "fields not parsed: " + a.get(0));
            Kit.expect(p.pendingCount() == 10, "10 should be pending until acked, XPENDING says " + p.pendingCount());
            for (OrderPipeline.Entry e : a) p.ack(e.id());
            for (OrderPipeline.Entry e : b) Kit.expect(p.ack(e.id()) == 1, "ack returned 0");
            Kit.expect(p.pendingCount() == 0, "pending after acks: " + p.pendingCount());
        });
        Kit.test("readNew with nothing new waits ~blockMs and returns an empty list", () -> {
            long t = System.currentTimeMillis();
            List<OrderPipeline.Entry> none = p.readNew("w1", 5, 300);
            Kit.expect(none.isEmpty(), "got " + none);
            Kit.expect(System.currentTimeMillis() - t >= 250, "didn't block");
        });
        Kit.test("crashed worker: its unacked entries are rescued with claimStuck", () -> {
            for (int i = 0; i < 3; i++) p.add(Map.of("orderId", "crash" + i));
            List<OrderPipeline.Entry> taken = p.readNew("ghost", 10, 100);     // ...and ghost dies
            Kit.expect(taken.size() == 3, "ghost read " + taken.size());
            Kit.expect(p.claimStuck("rescuer", 500, 10).isEmpty(), "claimed entries that weren't idle long enough");
            FakeDb.sleep(600);
            List<OrderPipeline.Entry> rescued = p.claimStuck("rescuer", 500, 10);
            Kit.expect(rescued.size() == 3, "rescued " + rescued.size());
            Kit.expect(rescued.get(0).fields().get("orderId").startsWith("crash"), "wrong entries: " + rescued);
            rescued.forEach(e -> p.ack(e.id()));
            Kit.expect(p.pendingCount() == 0, "still pending: " + p.pendingCount());
        });
        Redis.call("DEL", p.stream);

        Kit.section("I1 Checkout (integration: needs Tracks A, B, E)");
        String phone = "93" + RUN;
        String[] token = new String[1];
        Kit.test("no / bad token -> 401", () -> {
            Kit.expect(Checkout.checkout(null).status() == 401, "null token not refused");
            Kit.expect(Checkout.checkout("nope").status() == 401, "bad token not refused");
        });
        Kit.test("logged in, empty cart -> 400", () -> {
            Redis.call("DEL", "otp:cooldown:" + phone, "rl:sliding:" + phone);
            token[0] = OtpAuth.verifyOtp(phone, OtpAuth.requestOtp(phone)).token();
            Cart.clear(phone);
            Kit.expect(Checkout.checkout(token[0]).status() == 400, "empty cart not refused");
        });
        Kit.test("items from two restaurants -> 400", () -> {
            Cart.addItem(phone, "masala-dosa", 1);
            Cart.addItem(phone, "veg-biryani", 1);
            Kit.expect(Checkout.checkout(token[0]).status() == 400, "mixed cart not refused");
            Cart.clear(phone);
        });
        Kit.test("2 dosas -> 202, total 258, order on orders:stream, cart emptied", () -> {
            Cart.addItem(phone, "masala-dosa", 2);
            Checkout.Response r = Checkout.checkout(token[0]);
            Kit.expect(r.status() == 202, "status " + r.status() + " " + r.body());
            String orderId = (String) r.body().get("orderId");
            Kit.expect(orderId != null && Integer.valueOf(258).equals(r.body().get("total")), "body: " + r.body());
            Kit.expect(Cart.items(phone).isEmpty(), "cart not cleared");
            List<Object> recent = Redis.list("XREVRANGE", "orders:stream", "+", "-", "COUNT", 50);
            Kit.expect(recent.toString().contains(orderId), orderId + " not found on orders:stream");
        });
        Kit.test("6th checkout within a minute -> 429 with retryAfterSeconds", () -> {
            Checkout.Response r = null;
            for (int i = 0; i < 3; i++) {
                Cart.addItem(phone, "filter-coffee", 1);
                r = Checkout.checkout(token[0]);
            }
            Kit.expect(r.status() == 429, "expected 429 on the 6th call, got " + r.status());
            Kit.expect(r.body().containsKey("retryAfterSeconds"), "429 body needs retryAfterSeconds");
            Cart.clear(phone);
        });

        Kit.section("I2 KitchenWorker.process (integration: needs every track)");
        Kit.test("process() publishes ACCEPTED..DELIVERED, bumps the leaderboard, frees the rider", () -> {
            KitchenWorker.stepMs = 50;
            for (var e : FakeDb.RIDER_START.entrySet()) {
                NearbyRiders.updateLocation(e.getKey(), e.getValue()[0], e.getValue()[1]);
                RiderLock.release(e.getKey());
            }
            String order = "kw-" + RUN;
            Leaderboard.Entry before = Leaderboard.rankOf(Leaderboard.thisWeek(), "r1");
            List<String> got = new CopyOnWriteArrayList<>();
            Thread t = Thread.startVirtualThread(() -> LiveUpdates.listen(order, new LiveUpdates.Listener() {
                public boolean onMessage(String json) { got.add(Kit.field(json, "status")); return true; }
                public boolean onIdle() { return true; }
            }));
            FakeDb.sleep(300);
            KitchenWorker.process(new OrderPipeline.Entry("0-1", Map.of("orderId", order, "restaurantId", "r1")));
            t.join(3000);
            Kit.expect(got.equals(List.of("ACCEPTED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED")), "statuses: " + got);
            Leaderboard.Entry after = Leaderboard.rankOf(Leaderboard.thisWeek(), "r1");
            Kit.expect(after != null && after.orders() == (before == null ? 0 : before.orders()) + 1, "leaderboard not incremented");
            for (String r : FakeDb.RIDER_START.keySet())
                Kit.expect(!order.equals(Redis.str("GET", "rider:" + r + ":order")), r + " still holds the delivered order");
        });
    }
}

// =====================================================================
// Tiny test kit (given) -- no JUnit needed.
// =====================================================================
final class Kit {
    static int passed, failed, stretchDone, stretchTotal;

    interface Body { void run() throws Exception; }
    interface Info { String get() throws Exception; }

    static void section(String title) { System.out.println("\n-- " + title + " --"); }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + "\n         " + describe(t));
        }
    }

    static void stretch(String name, Body body) {
        stretchTotal++;
        try {
            body.run();
            stretchDone++;
            System.out.println("  [STRETCH DONE] " + name);
        } catch (Throwable t) {
            System.out.println("  [STRETCH todo] " + name + "  (" + describe(t) + ")");
        }
    }

    static void info(Info info) {
        try { System.out.println("  [INFO] " + info.get()); } catch (Throwable t) { System.out.println("  [INFO] " + describe(t)); }
    }

    static String describe(Throwable t) {
        while (t.getCause() != null && (t instanceof ExecutionException || t instanceof CompletionException || t instanceof RuntimeException && t.getMessage() == null)) t = t.getCause();
        if (t instanceof AssertionError) return t.getMessage();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static void summary(String what) {
        System.out.printf("%n%d passed, %d failed%s%n", passed, failed, failed == 0 ? "  -> " + what + " complete!" : "");
        if (stretchTotal > 0) System.out.printf("stretch goals: %d/%d%n", stretchDone, stretchTotal);
    }

    static void expect(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static void expectThrows(Class<? extends Throwable> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + describe(t));
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    /** Runs n tasks at the same instant (on virtual threads) and returns their results in order. */
    static <T> List<T> concurrently(int n, IntFunction<T> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> { go.await(); return task.apply(idx); }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        }
    }

    static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
