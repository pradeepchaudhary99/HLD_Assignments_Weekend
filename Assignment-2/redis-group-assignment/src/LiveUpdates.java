import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TRACK E · Feature E1 — Live order status with Pub/Sub.       Redis: PUBLISH / SUBSCRIBE
 *
 * Problem: the kitchen worker (one process) moves an order to PREPARING. The customer's phone is
 * connected via SSE to API server #7 (another process). How does server #7 find out?
 * Pub/Sub: the worker PUBLISHes to a channel, every server SUBSCRIBEd to it gets the message instantly.
 * (This is Assignment 1's SSE exercise, now working across many servers.)
 *
 * Channels you own:   order:{orderId}:status       (channels are not keys — they store nothing!)
 *
 *   GET /orders/{orderId}/live    -> text/event-stream, one event per status change
 *
 * Try it:  terminal 1: redis-cli SUBSCRIBE order:ord_1:status     terminal 2: redis-cli PUBLISH order:ord_1:status hi
 */
final class LiveUpdates {

    static String channel(String orderId) { return "order:" + orderId + ":status"; }

    /** Called by the SSE endpoint. Return false from a method to stop listening (the browser left). */
    interface Listener {
        boolean onMessage(String json);
        boolean onIdle();
    }

    /**
     * TODO(1): Announce a status change.
     *   json = Json.write(map of orderId, status, at = System.currentTimeMillis())
     *   PUBLISH order:{orderId}:status json    -> returns how many subscribers received it. Return that.
     *   Note: if nobody is subscribed right now, the message is simply lost. (docs/TRACK-E.md Q1)
     */
    static long publish(String orderId, String status) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Listen for one order's updates until it's DELIVERED or the listener says stop.
     *   try (Redis.Subscription sub = Redis.subscribe(channel(orderId))) {
     *       loop:
     *         msg = sub.next(15_000)          // waits up to 15 s; null = nothing came
     *         null    -> if (!listener.onIdle()) return;          (SSE sends a keep-alive here)
     *         message -> if (!listener.onMessage(msg)) return;
     *                    if msg contains "\"DELIVERED\"" -> return (we're done)
     *   }
     */
    static void listen(String orderId, Listener listener) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    private LiveUpdates() {}
}
