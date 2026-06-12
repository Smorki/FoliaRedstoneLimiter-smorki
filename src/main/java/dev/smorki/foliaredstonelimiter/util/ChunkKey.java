package dev.smorki.foliaredstonelimiter.util;

import java.util.Objects;

/**
 * Immutable, allocation-minimal key that uniquely identifies a chunk across worlds.
 *
 * <p>Using a plain {@code String} key ("world:x:z") incurs repeated string
 * concatenation on every redstone event. This record avoids that cost while
 * remaining safe as a {@link java.util.concurrent.ConcurrentHashMap} key
 * because records are immutable and implement {@code equals}/{@code hashCode}
 * correctly by default.
 */
public record ChunkKey(String world, int chunkX, int chunkZ) {

    /**
     * Factory method used at the event hot-path.
     * Kept here to allow future caching / interning without changing call sites.
     */
    public static ChunkKey of(String world, int chunkX, int chunkZ) {
        return new ChunkKey(world, chunkX, chunkZ);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkKey other)) return false;
        return chunkX == other.chunkX
                && chunkZ == other.chunkZ
                && Objects.equals(world, other.world);
    }

    @Override
    public int hashCode() {
        // Spread bits to reduce ConcurrentHashMap bucket collisions.
        int h = world.hashCode();
        h = 31 * h + chunkX;
        h = 31 * h + chunkZ;
        return h;
    }
}
