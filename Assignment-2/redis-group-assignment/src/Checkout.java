import java.util.*;

/**
 * TRACK E · Integration I1 — Checkout.  EVERYONE's features meet here. Do this together, last.
 *
 *   POST /checkout     header Authorization: Bearer <token>
 *
 *   Customer app                     Checkout                          Redis
 *   ─────────────                    ────────                          ─────
 *   token  ───────────────────────►  1. OtpAuth.session(token)      ── session:{token}      (Track B)
 *                                    2. RateLimiter.check(phone)    ── rl:sliding:{phone}   (Track B)
 *                                    3. Cart.items(phone)           ── cart:{phone}         (Track A)
 *                                    4. OrderPipeline.MAIN.add(...) ── orders:stream        (Track E)
 *                                    5. Cart.clear(phone)
 *                                    6. LiveUpdates.publish(PLACED) ── order:{id}:status    (Track E)
 *   202 {orderId}  ◄───────────────
 *
 * The cart belongs to the logged-in user: userId = the session's phone number.
 */
final class Checkout {

    record Response(int status, Map<String, Object> body) {
        static Response error(int status, String msg) { return new Response(status, Map.of("error", msg)); }
    }

    /**
     * TODO(1): Implement the flow above.
     *   1. no session                                   -> 401 "Please log in"
     *   2. rate limit not allowed                       -> 429, body also has "retryAfterSeconds"
     *   3. empty cart                                   -> 400 "Cart is empty"
     *      items from more than one restaurant          -> 400 (FakeDb.restaurantOf(itemId) tells you which)
     *   4. orderId = "ord_" + 8 random chars;  total = sum(price * qty)  (FakeDb.priceOf)
     *      add to the stream with fields: orderId, userId, restaurantId, items (Json.write(itemsMap)), total
     *   5-6. clear the cart, publish "PLACED"
     *   7. 202 {"orderId": ..., "status": "PLACED", "total": ..., "live": "/orders/{orderId}/live"}
     *   Order matters: why check the rate limit BEFORE reading the cart, and add to the stream BEFORE
     *   clearing the cart? (docs/TRACK-E.md Q5)
     */
    static Response checkout(String token) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    private Checkout() {}
}
