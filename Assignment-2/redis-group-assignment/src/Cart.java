import java.util.*;

/**
 * TRACK A · Feature A2 — Shopping cart.                     Redis type: HASH
 *
 * Problem: the cart changes on every tap (+1 dosa, -1 coffee). Writing each tap to Postgres is
 * heavy, and an abandoned cart should just disappear after a while.
 *
 * Keys you own:   cart:{userId}   HASH   field = itemId, value = quantity     expires 30 min after last change
 *
 *   POST   /cart/{userId}/items  {"itemId":"masala-dosa","qty":2}   (qty may be negative)
 *   GET    /cart/{userId}
 *   DELETE /cart/{userId}
 *
 * Try it:  redis-cli HGETALL cart:u1     redis-cli TTL cart:u1
 */
final class Cart {

    static final int TTL_SECONDS = 30 * 60;

    static String key(String userId) { return "cart:" + userId; }

    /**
     * TODO(1): Add (or remove, if qty is negative) some quantity of an item.
     *   - Unknown item (FakeDb.priceOf(itemId) == null) or qty == 0 -> throw new IllegalArgumentException("...")
     *   - HINCRBY the field by qty. It returns the new quantity.
     *   - New quantity <= 0 -> HDEL the field (nobody wants "-1 dosa")
     *   - Reset the expiry of the WHOLE cart to TTL_SECONDS (EXPIRE) — it's a sliding expiry.
     *   - Return the new quantity (0 if it was removed).
     */
    static long addItem(String userId, String itemId, int qty) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Read the cart as itemId -> quantity.
     *   HGETALL returns a flat list: [field1, value1, field2, value2, ...]. Empty cart -> empty map.
     */
    static Map<String, Integer> items(String userId) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /** TODO(3): Empty the cart (after checkout, or when the user taps "clear"). One command. */
    static void clear(String userId) {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /** GIVEN: the cart with prices and a total, for the API. */
    static Map<String, Object> view(String userId) {
        List<Map<String, Object>> lines = new ArrayList<>();
        int total = 0;
        for (var e : items(userId).entrySet()) {
            int price = Optional.ofNullable(FakeDb.priceOf(e.getKey())).orElse(0);
            lines.add(Map.of("itemId", e.getKey(), "qty", e.getValue(), "price", price));
            total += price * e.getValue();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("items", lines);
        out.put("total", total);
        out.put("expiresInSeconds", Redis.num("TTL", key(userId)));
        return out;
    }

    private Cart() {}
}
