# 🟪 Track D — Real-time data: leaderboard + nearby riders

**Files:** `src/Leaderboard.java`, `src/NearbyRiders.java`  **Check:** `./run.sh check D`  **Demo cards:** 6 (Top restaurants), 7 (Riders)

## Warm-up in `redis-cli` (10 min)
```
ZINCRBY lb:test 1 r1
ZINCRBY lb:test 5 r2
ZINCRBY lb:test 3 r3
ZREVRANGE lb:test 0 2 WITHSCORES      # highest first
ZREVRANK lb:test r3                   # 0-based rank
ZSCORE lb:test r3

GEOADD riders:test 77.6245 12.9352 ravi        # LONGITUDE first!
GEOADD riders:test 77.6412 12.9719 imran
GEODIST riders:test ravi imran km
GEOSEARCH riders:test FROMLONLAT 77.626 12.934 BYRADIUS 5 km ASC WITHDIST
TYPE riders:test                      # zset! GEO is a sorted set with geohash scores
```

## D1 — Leaderboard

```mermaid
flowchart LR
    O["order delivered<br/>at Dosa Junction"]:::e -- "ZINCRBY lb:restaurants:2026-W47 1 r1" --> Z
    subgraph Z["ZSET — always sorted by score"]
      direction TB
      z1["r2 · 41"]:::d
      z2["r1 · 37"]:::d
      z3["r4 · 22"]:::d
    end
    Z -- "ZREVRANGE 0 9 WITHSCORES" --> H["🏠 home screen top 10"]:::c
    Z -- "ZREVRANK r1 → 1 → '#2'" --> RD["📊 restaurant dashboard"]:::c
    classDef e fill:#00e676,color:#000,stroke:#000
    classDef d fill:#d500f9,color:#fff,stroke:#fff
    classDef c fill:#00e5ff,color:#000,stroke:#000
```

| TODO | Method | What |
|---|---|---|
| 1 | `recordOrder` | `ZINCRBY` + `EXPIRE` 8 days |
| 2 | `top` | `ZREVRANGE ... WITHSCORES` → entries with rank |
| 3 | `rankOf` | `ZREVRANK` + `ZSCORE` |

## D2 — Nearby riders

```mermaid
flowchart LR
    P["🛵 rider app<br/>every 10 s"]:::d -- "GEOADD riders:geo lng lat id<br/>SET rider:id:alive 1 EX 30" --> G[("riders:geo")]:::r
    K["👨‍🍳 order ready<br/>at Koramangala"]:::e -- "GEOSEARCH ... BYRADIUS 5 km ASC" --> G
    G -- "ravi 0.3 km, asha 3.1 km, ..." --> F{"alive key<br/>exists?"}:::w
    F -- "yes" --> OUT["candidates →<br/>Track C lock"]:::c
    F -- "no: ZREM (lazy cleanup)" --> G
    classDef d fill:#d500f9,color:#fff,stroke:#fff
    classDef r fill:#ff1744,color:#fff,stroke:#fff
    classDef e fill:#00e676,color:#000,stroke:#000
    classDef w fill:#ffea00,color:#000,stroke:#000
    classDef c fill:#00e5ff,color:#000,stroke:#000
```

| TODO | Method | What |
|---|---|---|
| 1 | `updateLocation` | `GEOADD` (lng, lat!) + heartbeat key |
| 2 | `nearby` | `GEOSEARCH ... WITHDIST`, skip and `ZREM` dead riders |
| 3 | `goOffline` | `ZREM` + `DEL` |

## Think & answer
1. Why is a sorted set's `ZINCRBY` + top-10 so much cheaper than `SELECT restaurant_id, COUNT(*) ... ORDER BY 2 DESC LIMIT 10` on 50 million orders?
2. A new key per week means "this week" resets automatically. How would you build "top restaurants this **month**" from weekly boards? (Look up `ZUNIONSTORE`.)
3. Two restaurants tie on 37 orders. Who's ranked higher, and why? How would you break ties by "whoever reached 37 first"?
4. Why can't we put the TTL on each rider inside `riders:geo`? Why the separate `alive` key?
5. 50,000 riders each send GPS every 5 s. How many writes per second is that? Can one Redis handle it? What would you shard by?
6. Our riders are only removed when someone searches near them (lazy cleanup). What's the downside? What's an alternative?

## Stretch
- `around(board, id, 2)`: show the 2 restaurants above and below a given one ("you're #7: here's who's just ahead").
- Top **dishes** per restaurant: `lb:dishes:{restaurantId}:{week}`.
- `GEOSEARCH ... BYBOX` for a map view, plus a dot for each rider on the demo page.
