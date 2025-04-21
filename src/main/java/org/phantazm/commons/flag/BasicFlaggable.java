package org.phantazm.commons.flag;

import it.unimi.dsi.fastutil.Hash;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BasicFlaggable implements Flaggable {
    private final Map<Key, AtomicInteger> flags;

    public BasicFlaggable() {
        this(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
    }

    public BasicFlaggable(int initialSize) {
        this(initialSize, Hash.DEFAULT_LOAD_FACTOR);
    }

    public BasicFlaggable(int initialSize, float loadFactor) {
        this.flags = new ConcurrentHashMap<>(initialSize, loadFactor);
    }

    @Override
    public boolean hasFlag(@NotNull Key flag) {
        Objects.requireNonNull(flag);
        return flags.containsKey(flag);
    }

    @Override
    public void setFlag(@NotNull Key flag) {
        Objects.requireNonNull(flag);

        flags.compute(flag, (k, v) -> {
            if (v == null) return new AtomicInteger(1);

            v.getAndIncrement();
            return v;
        });
    }

    @Override
    public void clearFlag(@NotNull Key flag) {
        Objects.requireNonNull(flag);
        flags.remove(flag);
    }

    @Override
    public void unsetFlag(@NotNull Key flag) {
        Objects.requireNonNull(flag);
        flags.compute(flag, (k, v) -> {
            if (v == null || v.decrementAndGet() <= 0) return null;
            else return v;
        });
    }
}
