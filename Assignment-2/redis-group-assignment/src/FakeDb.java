import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GIVEN — pretend this is QuickBite's Postgres database.
 * Every menu read takes 300 ms (a slow join across 4 tables on a busy primary).
 * Redis exists in this project to stop us paying that 300 ms again and again.
 */
final class FakeDb {

    record Restaurant(String id, String name, String cuisine) {}
    record MenuItem(String itemId, String name, int price, boolean veg) {}

    static final long QUERY_MS = 300;
    /** How many slow queries have hit the "database". The checker watches this. */
    static final AtomicInteger MENU_READS = new AtomicInteger();

    static final Map<String, Restaurant> RESTAURANTS = new LinkedHashMap<>();
    private static final Map<String, List<MenuItem>> MENUS = new ConcurrentHashMap<>();

    static {
        add(new Restaurant("r1", "Dosa Junction", "South Indian"),
                new MenuItem("masala-dosa", "Masala Dosa", 129, true),
                new MenuItem("rava-dosa", "Rava Dosa", 149, true),
                new MenuItem("filter-coffee", "Filter Coffee", 49, true));
        add(new Restaurant("r2", "Biryani Brothers", "Hyderabadi"),
                new MenuItem("chicken-biryani", "Chicken Dum Biryani", 299, false),
                new MenuItem("veg-biryani", "Veg Biryani", 219, true),
                new MenuItem("double-ka-meetha", "Double Ka Meetha", 99, true));
        add(new Restaurant("r3", "Punjab Da Dhaba", "North Indian"),
                new MenuItem("butter-chicken", "Butter Chicken", 329, false),
                new MenuItem("dal-makhani", "Dal Makhani", 229, true),
                new MenuItem("garlic-naan", "Garlic Naan", 59, true));
        add(new Restaurant("r4", "Idli Express", "South Indian"),
                new MenuItem("podi-idli", "Ghee Podi Idli", 119, true),
                new MenuItem("medu-vada", "Medu Vada", 79, true));
        add(new Restaurant("r5", "Wok This Way", "Chinese"),
                new MenuItem("hakka-noodles", "Hakka Noodles", 189, true),
                new MenuItem("chilli-chicken", "Chilli Chicken", 249, false));
    }

    private static void add(Restaurant r, MenuItem... items) {
        RESTAURANTS.put(r.id(), r);
        MENUS.put(r.id(), List.of(items));
    }

    /** SLOW. Returns null if the restaurant doesn't exist. */
    static List<MenuItem> getMenu(String restaurantId) {
        MENU_READS.incrementAndGet();
        sleep(QUERY_MS);
        return MENUS.get(restaurantId);
    }

    /** Returns false if the restaurant or item doesn't exist. */
    static synchronized boolean updatePrice(String restaurantId, String itemId, int newPrice) {
        List<MenuItem> menu = MENUS.get(restaurantId);
        if (menu == null) return false;
        List<MenuItem> updated = new ArrayList<>();
        boolean found = false;
        for (MenuItem m : menu) {
            if (m.itemId().equals(itemId)) { updated.add(new MenuItem(m.itemId(), m.name(), newPrice, m.veg())); found = true; }
            else updated.add(m);
        }
        if (found) MENUS.put(restaurantId, List.copyOf(updated));
        return found;
    }

    /** Fast price lookup for the cart (pretend it's an indexed column). null = no such item. */
    static Integer priceOf(String itemId) {
        for (List<MenuItem> menu : MENUS.values())
            for (MenuItem m : menu) if (m.itemId().equals(itemId)) return m.price();
        return null;
    }

    /** Which restaurant sells this item. null = no such item. */
    static String restaurantOf(String itemId) {
        for (var e : MENUS.entrySet())
            for (MenuItem m : e.getValue()) if (m.itemId().equals(itemId)) return e.getKey();
        return null;
    }

    /** Restaurant locations (lat, lng) in Bengaluru. */
    static final Map<String, double[]> RESTAURANT_LOCATION = Map.of(
            "r1", new double[]{12.9340, 77.6260},    // Koramangala
            "r2", new double[]{12.9710, 77.6400},    // Indiranagar
            "r3", new double[]{12.9130, 77.6430},    // HSR Layout
            "r4", new double[]{12.9300, 77.6200},    // Koramangala 5th block
            "r5", new double[]{12.9690, 77.7490});   // Whitefield

    static String restaurantName(String id) {
        Restaurant r = RESTAURANTS.get(id);
        return r == null ? id : r.name();
    }

    /** Riders and where they start (Bengaluru). Used by the demo and the kitchen worker. */
    static final Map<String, double[]> RIDER_START = new LinkedHashMap<>();
    static {
        RIDER_START.put("rider-ravi", new double[]{12.9352, 77.6245});     // Koramangala
        RIDER_START.put("rider-imran", new double[]{12.9719, 77.6412});    // Indiranagar
        RIDER_START.put("rider-asha", new double[]{12.9121, 77.6446});     // HSR Layout
        RIDER_START.put("rider-karthik", new double[]{12.9698, 77.7500});  // Whitefield
        RIDER_START.put("rider-simran", new double[]{12.8452, 77.6602});   // Electronic City
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private FakeDb() {}
}
