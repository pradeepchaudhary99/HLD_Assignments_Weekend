import java.util.List;

/**
 * TRACK C · Feature C1 — Flash sale: "₹1 Biryani, 100 plates, 8 PM sharp".   Redis: counter + SET + Lua
 *
 * Problem: at 8:00:00 PM, 20,000 users hit "Buy" within one second. Rules:
 *   - never sell more than the stock (overselling = refunds + angry restaurant)
 *   - one plate per user
 * Reading the stock, checking it, and writing it back is three steps. Between them, other requests
 * sneak in. buyNaive() below shows the bug — the checker proves it oversells.
 *
 * Keys you own:   sale:{saleId}:stock    STRING  remaining plates
 *                 sale:{saleId}:buyers   SET     userIds who bought
 *
 *   POST /sales/{saleId}/start {"stock":100}     POST /sales/{saleId}/buy {"userId":"u1"}     GET /sales/{saleId}
 */
final class FlashSale {

    static String stockKey(String saleId) { return "sale:" + saleId + ":stock"; }
    static String buyersKey(String saleId) { return "sale:" + saleId + ":buyers"; }

    /** GIVEN: (re)start a sale. */
    static void start(String saleId, int stock) {
        Redis.call("SET", stockKey(saleId), stock);
        Redis.call("DEL", buyersKey(saleId));
    }

    /** GIVEN — and BROKEN. Read-check-write in Java: correct on one thread, oversells under load. */
    static String buyNaive(String saleId, String userId) {
        if (Redis.num("SISMEMBER", buyersKey(saleId), userId) == 1) return "ALREADY_BOUGHT";
        long stock = Long.parseLong(Redis.str("GET", stockKey(saleId)));
        if (stock <= 0) return "SOLD_OUT";
        FakeDb.sleep(1);                                    // payment pre-check, logging... any small delay
        Redis.call("SET", stockKey(saleId), stock - 1);     // <- another thread read the same stock!
        Redis.call("SADD", buyersKey(saleId), userId);
        return "OK";
    }

    /**
     * TODO(1): The same logic, but as ONE Lua script. Redis runs a script start-to-finish with nothing
     * else in between — so check-then-act becomes atomic across all your servers.
     *   KEYS[1] = stock key, KEYS[2] = buyers key, ARGV[1] = userId
     *   - user already in the buyers SET (SISMEMBER == 1)  -> return 'ALREADY_BOUGHT'
     *   - stock (GET, tonumber(...)) <= 0                    -> return 'SOLD_OUT'
     *   - otherwise DECR stock, SADD the user                -> return 'OK'
     */
    static final String BUY_LUA = """
            -- write your script here
            """;

    /** TODO(2): Run BUY_LUA with Redis.eval(script, keys, args...) and return its reply ("OK", "SOLD_OUT"...). */
    static String buy(String saleId, String userId) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /** GIVEN: current state for the API / checker. */
    static long remaining(String saleId) {
        String s = Redis.str("GET", stockKey(saleId));
        return s == null ? 0 : Long.parseLong(s);
    }

    static long buyerCount(String saleId) { return Redis.num("SCARD", buyersKey(saleId)); }

    private FlashSale() {}
}
