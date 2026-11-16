import java.util.List;

/**
 * TRACK E · Integration I2 — the kitchen worker. A separate process that drains orders:stream.
 *
 *   ./run.sh worker w1              start a worker named w1 (start w2 in another terminal too!)
 *   ./run.sh worker w3 --crash      reads ONE order and dies without XACK -> watch w1/w2 rescue it
 *
 *   orders:stream ──XREADGROUP──► worker ──► ACCEPTED ──► PREPARING ──► rider assigned ──► OUT_FOR_DELIVERY ──► DELIVERED ──► XACK
 *                                              │            │                 │                                   │
 *                                         publish (E1)  leaderboard (D1)  nearby (D2) + lock (C2)          release rider (C2)
 */
final class KitchenWorker {

    /** How long each kitchen step "takes". The checker shortens it. */
    static long stepMs = 2000;

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "w1";
        boolean crash = List.of(args).contains("--crash");
        OrderPipeline pipeline = OrderPipeline.MAIN;
        pipeline.ensureGroup();
        System.out.println("Kitchen worker " + name + " started" + (crash ? " [will CRASH after reading one order]" : ""));

        while (true) {
            // First rescue orders another worker took but never finished (idle > 15 s)...
            List<OrderPipeline.Entry> batch = pipeline.claimStuck(name, 15_000, 10);
            if (!batch.isEmpty()) System.out.println("[" + name + "] rescued " + batch.size() + " stuck order(s) from a dead worker");
            // ...then wait up to 5 s for new ones.
            if (batch.isEmpty()) batch = pipeline.readNew(name, 5, 5_000);

            for (OrderPipeline.Entry e : batch) {
                if (crash) {
                    System.out.println("[" + name + "] took " + e.fields().get("orderId") + " ... and CRASHED (no XACK)");
                    System.exit(1);
                }
                try {
                    process(e);
                    pipeline.ack(e.id());
                } catch (RuntimeException ex) {
                    // No ack: the entry stays pending and will be claimed again after 15 s.
                    System.out.println("[" + name + "] failed " + e.id() + ": " + ex.getMessage());
                }
            }
        }
    }

    /**
     * TODO(1): Cook and deliver one order, using everyone's features.
     *   orderId = e.fields().get("orderId"), restaurantId = e.fields().get("restaurantId")
     *   1. LiveUpdates.publish(orderId, "ACCEPTED")
     *      Leaderboard.recordOrder(Leaderboard.thisWeek(), restaurantId)
     *   2. sleep(stepMs);  publish "PREPARING";  sleep(stepMs)
     *   3. Find riders: loc = FakeDb.RESTAURANT_LOCATION.get(restaurantId)
     *      NearbyRiders.nearby(loc[0], loc[1], 10, 5) -> rider ids, nearest first
     *      rider = RiderLock.assignRider(orderId, ids)
     *      no rider -> throw new IllegalStateException("no free rider")   (no ack -> retried later)
     *   4. publish "OUT_FOR_DELIVERY";  sleep(stepMs)
     *   5. publish "DELIVERED";  RiderLock.release(rider)
     *   Print a line for each step so the demo is easy to follow.
     *   (Use FakeDb.sleep(ms) for sleeping.)
     */
    static void process(OrderPipeline.Entry e) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    static void log(String orderId, String msg) {
        System.out.println("  [kitchen] " + orderId + " -> " + msg);
    }
}
