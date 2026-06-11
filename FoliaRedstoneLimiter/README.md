# FoliaRedstoneLimiter

> Per-region redstone flood limiter built natively for [Folia](https://github.com/PaperMC/Folia)'s regionized multi-threading architecture.

<div align="center">

![Version](https://img.shields.io/badge/version-1.0.0-8E44AD?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Folia%201.20+-00FFFF?style=flat-square&labelColor=1a1a2e)
![Java](https://img.shields.io/badge/java-21+-8E44AD?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-00FFFF?style=flat-square&labelColor=1a1a2e)

</div>

---

## What it does

Redstone lag machines are one of the most effective ways to degrade server performance. On traditional servers a single limiter can guard the whole world, but **Folia runs each region on its own thread** — a naive global lock would introduce cross-region contention and defeat the entire point of Folia.

FoliaRedstoneLimiter solves this correctly:

- Tracks redstone update rates **per chunk, per tick** using lock-free `LongAdder` counters.
- When a chunk exceeds the configured threshold, its redstone is **frozen inside that region only** — zero impact on other regions.
- Freeze penalties are lifted by `RegionScheduler.runDelayed()`, which runs on the **same thread that owns the offending chunk**.
- Configuration reloads happen on `AsyncScheduler` — disk I/O never touches a region thread.

---

## Performance architecture

### Why Folia's RegionScheduler changes everything

Vanilla Paper (and most Paper forks) use a single-threaded tick loop. Every plugin task, event handler, and scheduler callback runs on that one thread. Under load, a spike in one chunk stalls the entire server.

Folia replaces this with a **regionized model**:

```
World
 ├── Region A (chunks 0–31, 0–31)   → Thread A
 ├── Region B (chunks 32–63, 0–31)  → Thread B
 └── Region C (chunks 0–31, 32–63)  → Thread C
```

Each region ticks independently. A lag machine in Region A does **not** slow the tick rate of Region B or C.

FoliaRedstoneLimiter is designed around this model:

| Operation | Scheduler used | Why |
|---|---|---|
| Redstone counter increment | Region thread (event callback) | Events already fire on the owning region thread — no dispatch needed |
| Per-tick counter reset | `RegionScheduler.runAtFixedRate` | Pinned to chunk's region; reset races with increment are impossible |
| Freeze penalty timer | `RegionScheduler.runDelayed` | Unfreeze runs on the same region thread that applied the freeze |
| Config reload | `AsyncScheduler.runNow` | File I/O must never block a region or game thread |

### Lock-free data structures

| Structure | Usage | Why not synchronized? |
|---|---|---|
| `ConcurrentHashMap` | Frozen chunk set, counter map, reset-task registry | Lock-striped; reads never block writers |
| `LongAdder` | Per-chunk update counter | Outperforms `AtomicLong` under contention; ideal for high-frequency increment, low-frequency read |
| `AtomicInteger` | Config values (limit, freeze duration) | Single-word publish; readers never see torn values |
| `AtomicReference<List<T>>` | Config lists (bypass worlds, messages) | Publishes immutable snapshots atomically |

### Event listener design

```java
@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
public void onRedstoneChange(BlockRedstoneEvent event) { … }
```

- **LOWEST priority** — the event is cancelled before any other plugin sees it, avoiding wasted downstream processing (piston logic, comparator updates, etc.).
- **ignoreCancelled = true** — already-cancelled events (from other plugins) are skipped entirely.
- **No synchronization inside the handler** — all shared state is either thread-local to the region or backed by lock-free structures.

---

## Installation

1. Make sure you are running **Folia 1.20.1** or newer (not Paper, not Spigot).
2. Download `FoliaRedstoneLimiter-1.0.0.jar` from [Releases](../../releases).
3. Place it in your server's `plugins/` folder.
4. Restart the server (not `/reload` — Folia discourages hot-reload).

---

## Configuration (`config.yml`)

```yaml
max-redstone-updates-per-tick: 500   # Updates allowed per chunk per tick
freeze-duration-seconds: 30          # How long to suppress a violating chunk
bypass-worlds:                       # Worlds excluded from limiting
  - creative_world
messages:
  freeze-alert: "..."
  reload-success: "..."
```

Reload without restarting:

```
/frl reload
```

Requires permission: `frl.admin` (default: op)

---

## Project structure

```
src/main/java/dev/smorki/foliaredstonelimiter/
 ├── FoliaRedstoneLimiter.java          Main plugin class, lifecycle management
 ├── config/
 │   └── ConfigManager.java             Thread-safe config cache, async loader
 ├── listener/
 │   └── RedstoneListener.java          Hot-path event handler
 ├── scheduler/
 │   ├── RegionFreezeManager.java       Freeze/unfreeze lifecycle with RegionScheduler
 │   └── TickCounterManager.java        LongAdder counters + per-tick reset tasks
 ├── command/
 │   └── FRLCommand.java                /frl executor + tab completer
 └── util/
     ├── ChunkKey.java                  Immutable chunk identifier (record)
     └── MessageUtil.java               MiniMessage parser & broadcast helpers
```

---

## Building from source

**Requirements:** Java 21+, Maven 3.9+

```bash
git clone https://github.com/smorki/FoliaRedstoneLimiter.git
cd FoliaRedstoneLimiter
mvn clean package
# Output: target/FoliaRedstoneLimiter-1.0.0.jar
```

---

## Why this matters for server operators

| Scenario | Without FRL | With FRL |
|---|---|---|
| Lag machine in Region A | Entire server TPS drops | Only Region A redstone is suppressed |
| 10 simultaneous lag machines across regions | Server-wide freeze | Each region handles its own freeze independently |
| /frl reload under load | Config read blocks tick thread | Async read; zero tick impact |
| Player in Region B during Region A freeze | Experiences lag | Unaffected |

---

## License

MIT — free to use, modify, and redistribute with attribution.

---

<div align="center">
Made with care by <strong>Smorki</strong>
</div>
