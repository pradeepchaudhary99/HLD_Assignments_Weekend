import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * TRACK D · Feature D1 — "Top restaurants this week" leaderboard.     Redis type: SORTED SET
 *
 * Problem: the home screen shows the 10 most-ordered restaurants this week, and each restaurant's
 * dashboard shows "You're #7 in Bengaluru". Doing ORDER BY COUNT(*) over millions of orders on every
 * app open is slow. A sorted set keeps members ordered by score at all times: +1 is O(log N),
 * top-10 is O(log N + 10).
 *
 * Keys you own:   lb:restaurants:{year}-W{week}    ZSET  member = restaurantId, score = orders
 *
 *   GET /leaderboard?top=10      GET /leaderboard/{restaurantId}
 *
 * Try it:  redis-cli ZREVRANGE lb:restaurants:2026-W47 0 -1 WITHSCORES
 */
final class Leaderboard {

    /** rank is 1-based (1 = most orders). */
    record Entry(long rank, String restaurantId, String name, long orders) {}

    static final int KEEP_DAYS = 8;

    /** GIVEN: this week's board, e.g. lb:restaurants:2026-W47. A new key every week = automatic weekly reset. */
    static String thisWeek() {
        LocalDate d = LocalDate.now();
        return "lb:restaurants:" + d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /**
     * TODO(1): An order was placed at restaurantId.
     *   ZINCRBY board 1 restaurantId     (creates the member at 0 if missing)
     *   EXPIRE board (KEEP_DAYS days in seconds) so old weeks clean themselves up.
     *   Return the new score.
     */
    static long recordOrder(String board, String restaurantId) {
        throw new UnsupportedOperationException("TODO(1) not implemented yet");
    }

    /**
     * TODO(2): Top n, highest first.
     *   ZREVRANGE board 0 (n-1) WITHSCORES  ->  [member1, score1, member2, score2, ...]   (scores are strings like "42")
     *   Build Entry(rank, id, FakeDb.restaurantName(id), orders) with rank starting at 1.
     */
    static List<Entry> top(String board, int n) {
        throw new UnsupportedOperationException("TODO(2) not implemented yet");
    }

    /**
     * TODO(3): One restaurant's position. ZREVRANK gives the 0-based rank (null if not on the board),
     *   ZSCORE gives the score. Return null if the restaurant has no orders this week.
     *   Ties: two restaurants with equal scores are ordered by member name — fine for us.
     */
    static Entry rankOf(String board, String restaurantId) {
        throw new UnsupportedOperationException("TODO(3) not implemented yet");
    }

    private Leaderboard() {}
}
