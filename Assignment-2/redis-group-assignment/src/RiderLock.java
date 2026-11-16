import java.util.List;
import java.util.UUID;

/**
 * TRACK C · Feature C2 — Distributed lock for rider assignment.     Redis: SET NX PX + Lua
 *
 * Problem: we run 3 dispatcher instances. Two orders come in at the same time and both dispatchers
 * pick Ravi, the nearest rider. Now Ravi has two orders on opposite sides of town.
 * A `synchronized` block only protects one JVM. We need a lock that ALL instances respect.
 *
 * Keys you own:   lock:{name}            STRING  value = a random token, expires after ttlMs
 *                 rider:{riderId}:order  STRING  the order a rider is currently delivering
 */
final class RiderLock {

    static String lockKey(String name) { return "lock:" + name; }

    /**
     * TODO(1): Try to take the lock. Don't wait.
     *   token = a random UUID string (proves later that YOU own the lock)
     *   SET lock:{name} token NX PX ttlMs     -> "OK" = you got it -> return token
     *                                          -> null = someone else holds it -> return null
     *   Why PX (an expiry)? If this JVM crashes while holding the lock, it must free itself.
     */
    static String tryLock(String name, long ttlMs) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Release the lock — but ONLY if it's still yours.
     *   Danger: your lock expired (you were slow), someone else took it, and now you DEL *their* lock.
     *   So: "if GET == my token then DEL" — and that check+delete must be atomic -> Lua.
     *   Return true if the lock was yours and got deleted.
     */
    static final String UNLOCK_LUA = """
            -- write your script here
            """;

    static boolean unlock(String name, String token) {
        throw new UnsupportedOperationException("TODO not implemented yet");
    }

    /**
     * TODO(3): Assign the first free rider from `candidates` (nearest first) to the order.
     *   for each rider:
     *     token = tryLock("rider:" + rider, 5000);   null -> someone is assigning this rider right now, skip
     *     try {
     *        free = EXISTS rider:{rider}:order == 0
     *        if free -> SET rider:{rider}:order orderId and return rider
     *     } finally { unlock(...) }        <- always release, even on exceptions
     *   nobody free -> return null
     *   (Is the lock even needed here? Think about SET ... NX on rider:{rider}:order. docs/TRACK-C.md Q4)
     */
    static String assignRider(String orderId, List<String> candidates) {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /** GIVEN: the rider delivered the order and is free again. */
    static void release(String riderId) {
        Redis.call("DEL", "rider:" + riderId + ":order");
    }

    private RiderLock() {}
}
