import java.util.*;

/**
 * TRACK E · Feature E2 — Reliable order queue with Streams.      Redis type: STREAM + consumer group
 *
 * Problem: checkout must answer the user in milliseconds, but "send to restaurant, assign rider,
 * update leaderboard..." takes seconds. So checkout just appends the order to a queue, and kitchen
 * workers process it in the background. Pub/Sub can't do this: if no worker is listening, the order
 * is lost. A STREAM is an append-only log that remembers every entry; a CONSUMER GROUP gives each
 * entry to exactly one worker and tracks which ones were never acknowledged (the worker crashed).
 *
 * Keys you own:   orders:stream   STREAM   one entry per order   (group "kitchen")
 *
 * Try it:  redis-cli XLEN orders:stream    redis-cli XRANGE orders:stream - +    redis-cli XPENDING orders:stream kitchen
 */
final class OrderPipeline {

    /** One stream entry: its id (e.g. "1731234567890-0") and its fields. */
    record Entry(String id, Map<String, String> fields) {}

    static final OrderPipeline MAIN = new OrderPipeline("orders:stream", "kitchen");

    final String stream, group;

    OrderPipeline(String stream, String group) {
        this.stream = stream;
        this.group = group;
    }

    /** GIVEN: create the consumer group (and the stream) once. "0" = the group starts from the first entry. */
    void ensureGroup() {
        try {
            Redis.call("XGROUP", "CREATE", stream, group, "0", "MKSTREAM");
        } catch (Redis.RedisError e) {
            if (!e.getMessage().startsWith("BUSYGROUP")) throw e;   // already exists: fine
        }
    }

    /**
     * TODO(1): Append an order.
     *   XADD orders:stream MAXLEN ~ 100000 * field1 value1 field2 value2 ...
     *     "*"          = let Redis generate the id (timestamp-sequence)
     *     "MAXLEN ~ N" = keep roughly the last N entries so memory can't grow forever
     *   Return the generated id.
     */
    String add(Map<String, String> fields) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Read entries no other worker in the group has received yet.
     *   XREADGROUP GROUP {group} {consumer} COUNT {count} BLOCK {blockMs} STREAMS {stream} >
     *     ">" = "give me new entries". Nothing new within blockMs -> reply is null -> return an empty list.
     *   The reply shape is nested — parseRead(reply) below turns it into List<Entry>.
     */
    List<Entry> readNew(String consumer, int count, long blockMs) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /** TODO(3): Acknowledge = "done, don't give this to anyone again". XACK {stream} {group} {id}. Return the count acked. */
    long ack(String id) {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    /**
     * TODO(4): Crash recovery. A worker read an entry and died before XACK. It sits in the
     *   "pending" list forever unless someone takes it over.
     *   XAUTOCLAIM {stream} {group} {consumer} {minIdleMs} 0-0 COUNT {count}
     *     -> moves entries idle for >= minIdleMs to `consumer` and returns them.
     *     Reply: [nextCursor, [[id, [f, v, ...]], ...], [deletedIds]]  -> parseEntries(reply.get(1))
     */
    @SuppressWarnings("unchecked")
    List<Entry> claimStuck(String consumer, long minIdleMs, int count) {
        throw new UnsupportedOperationException("TODO(4) not implemented yet");
    }

    /** GIVEN: how many entries were delivered to some worker but never acked. */
    long pendingCount() {
        List<Object> summary = Redis.list("XPENDING", stream, group);
        return (Long) summary.get(0);
    }

    // ---------------- GIVEN: reply parsing ----------------

    /** XREADGROUP reply: [[streamName, [[id, [f, v, ...]], ...]]]  or null. */
    @SuppressWarnings("unchecked")
    static List<Entry> parseRead(Object reply) {
        if (reply == null) return List.of();
        List<Object> streams = (List<Object>) reply;
        List<Object> first = (List<Object>) streams.get(0);
        return parseEntries((List<Object>) first.get(1));
    }

    @SuppressWarnings("unchecked")
    static List<Entry> parseEntries(List<Object> raw) {
        List<Entry> out = new ArrayList<>();
        for (Object o : raw) {
            List<Object> e = (List<Object>) o;
            List<Object> kv = (List<Object>) e.get(1);
            if (kv == null) continue;                        // entry was trimmed away
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.size(); i += 2) fields.put((String) kv.get(i), (String) kv.get(i + 1));
            out.add(new Entry((String) e.get(0), fields));
        }
        return out;
    }
}
