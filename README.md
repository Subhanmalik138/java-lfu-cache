# LFU Cache — Java

A bounded, thread-safe **Least Frequently Used (LFU)** cache in plain Java 17, with zero dependencies.

LRU evicts whatever was touched longest ago. LFU evicts whatever is touched *least often* — so a hot key that gets read a hundred times survives, while a key that was inserted once and never read again is the first to go. That is the behaviour you want for a page cache, a query-result cache, or any workload with a skewed access pattern (a few hot keys, a long tail of one-hit wonders).

## Design decisions

1. **Frequency buckets, not a heap.** Entries live in `LinkedHashSet`s keyed by access count (`Map<Integer, LinkedHashSet<Node>>`), plus a `minFrequency` pointer. Eviction is then "take the first element of the lowest bucket" — amortised O(1) for both `get` and `put`, with no heap rebalancing and no stale-entry cleanup. This is the same structure behind the textbook O(1) LFU solution.
2. **LRU tie-breaking inside a bucket.** When several entries share the lowest frequency, the one touched longest ago is evicted first (the `LinkedHashSet` keeps insertion/touch order). Pure frequency alone would evict arbitrarily; the recency tie-break makes eviction deterministic and fair.
3. **One lock, honest concurrency.** A single `ReentrantLock` guards every mutation. A `ReadWriteLock` looks tempting, but `get` *mutates* state (it promotes the entry), so reads would take the write lock anyway. One lock is simpler, correct, and fast enough for a cache of this size — and the concurrent test proves the size invariant holds under contention.
4. **No TTL, no soft references.** This cache answers one question well: *which entries deserve to stay?* Expiry is a separate concern and would muddy the eviction semantics; a TTL layer can wrap this class without touching it.

## What it does

- Generic `LfuCache<K, V>` with O(1) `get` / `put` / `remove`
- LFU eviction with LRU tie-breaking; `put` on an existing key counts as an access
- `frequencyOf(key)` and `keysByFrequency()` for inspecting the cache's internal state
- `CacheStats` snapshot: hits, misses, evictions, hit rate
- Thread-safe; `clear()` resets entries and statistics
- A REPL (`put` / `get` / `remove` / `freq` / `dump` / `stats` / `clear`) and a scripted `--demo`

## Run it

Requires JDK 17+.

```bash
./build.sh            # compile + build out/lfu-cache.jar
java -jar out/lfu-cache.jar --demo
java -jar out/lfu-cache.jar --capacity 5   # start the REPL
```

Demo output (capacity 3):

```
LFU demo — capacity 3
put home = /
put about = /about
put contact = /contact
get home -> /
get home -> /
get about -> /about
home is hot (freq 3), contact was never read (freq 1)
after inserting pricing:
  freq 1: [pricing]
  freq 2: [about]
  freq 3: [home]
size=3/3 hits=3 misses=0 evictions=1 hitRate=100.0%
contact evicted even though it was inserted last — LFU evicts by frequency, not by age.
```

REPL session:

```
lfu> put home /
ok
lfu> get home
/
lfu> get home
/
lfu> dump
  freq 1: [home]
lfu> stats
size=1/3 hits=2 misses=0 evictions=0 hitRate=100.0%
```

## Tests

```bash
./build.sh test
```

17 JUnit 5 tests, all passing: eviction picks the LFU entry, ties break by recency, hot keys survive repeated evictions, `minFrequency` recovers after its bucket drains, stats and hit-rate math, argument validation, and an 8-thread concurrent stress test that checks the size invariant and the eviction count.

## Tech stack

- Java 17, no runtime dependencies
- JUnit 5 (console-standalone) for tests — the only test dependency
- Build is a single `build.sh` script (`javac` + `jar`); no Maven/Gradle needed

## License

MIT — see [LICENSE](LICENSE).
