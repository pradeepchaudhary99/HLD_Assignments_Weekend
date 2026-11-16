# Keyspace registry — fill this in BEFORE you write code

Every key or channel our app touches. One row per key pattern. If two rows could ever produce the same key, you have a bug.

**Conventions:** `object:id:field` with colons, lower-case, ids last. Every key either has a TTL or a sentence in "Why no TTL".

| Key pattern | Type | Example | TTL | Owner (track / name) | Written by | Read by | Why no TTL? |
|---|---|---|---|---|---|---|---|
| `menu:{restaurantId}` | STRING (JSON) | `menu:r1` | 60–70 s | A / | `MenuCache.getMenu` | `MenuCache.getMenu` | |
| `cart:{userId}` | HASH | `cart:9876543210` | 30 min sliding | A / | | | |
| `rl:fixed:{userId}:{window}` | | | | B / | | | |
| `rl:sliding:{userId}` | | | | B / | | | |
| `otp:{phone}` | | | | B / | | | |
| `otp:cooldown:{phone}` | | | | B / | | | |
| `otp:attempts:{phone}` | | | | B / | | | |
| `session:{token}` | | | | B / | | | |
| `sale:{saleId}:stock` | | | | C / | | | |
| `sale:{saleId}:buyers` | | | | C / | | | |
| `lock:{name}` | | | | C / | | | |
| `rider:{riderId}:order` | | | | C / | | | |
| `lb:restaurants:{year}-W{week}` | | | | D / | | | |
| `riders:geo` | | | | D / | | | |
| `rider:{riderId}:alive` | | | | D / | | | |
| `order:{orderId}:status` | channel | | n/a | E / | | | |
| `orders:stream` | | | | E / | | | |
| *(add your stretch-goal keys here)* | | | | | | | |

## Memory estimate (fill in)
Assume 1M daily users, 200k orders/day, 20k restaurants, 50k riders. Use `MEMORY USAGE <key>` on a real key to get a per-key size.

| Key pattern | How many live at once? | Bytes each (MEMORY USAGE) | Total |
|---|---|---|---|
| `menu:*` | | | |
| `session:*` | | | |
| `cart:*` | | | |
| … | | | |
| **Total** | | | |
