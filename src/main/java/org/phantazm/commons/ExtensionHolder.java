package org.phantazm.commons;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A holder of arbitrary-typed objects (extensions), which may be accessed using "keys" ({@link ExtensionHolder.Key}).
 * This class is thread-safe, and optimized for many concurrent reads with relatively few writes, and small sizes.
 */
public class ExtensionHolder {
    private static final AtomicLong GLOBAL_ID = new AtomicLong();
    private static final Long2ObjectOpenHashMap<Object> EMPTY_MAP = new Long2ObjectOpenHashMap<>(0);

    /**
     * Represents a key to retrieve a typed value from an {@link ExtensionHolder}. Each ExtensionHolder is capable of
     * holding any number of types of values. Therefore, this object provides the necessary type information.
     *
     * @param <T> the type of value to retrieve
     */
    public static class Key<T> {
        private final Class<T> type;
        private final long key;

        private Key(Class<T> type, long key) {
            this.type = type;
            this.key = key;
        }
    }

    private final Lock lock;
    private volatile Long2ObjectOpenHashMap<Object> map;

    /**
     * Creates a new instance of this class.
     */
    public ExtensionHolder() {
        this.lock = new ReentrantLock();
        this.map = EMPTY_MAP;
    }

    private ExtensionHolder(Long2ObjectOpenHashMap<Object> map) {
        this.lock = new ReentrantLock();
        this.map = map;
    }

    private void validateKey(Key<?> key, Object object) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(object);

        if (!key.type.isAssignableFrom(object.getClass())) {
            throw new IllegalArgumentException("Object not assignable to key type!");
        }
    }

    /**
     * Requests a new key, which will be usable with any {@link ExtensionHolder}.
     *
     * @param type the upper bounds of the runtime type of the object that will be stored at this key
     * @param <T>  the object type
     * @return a new {@link Key}
     */
    public static <T> @NotNull Key<T> requestKey(@NotNull Class<T> type) {
        Objects.requireNonNull(type);

        long id = GLOBAL_ID.getAndIncrement();
        return new Key<>(type, id);
    }

    /**
     * Gets an extension object, given a key.
     *
     * @param key the key
     * @param <T> the type of the extension object
     * @return the extension object, or {@code null} if such an object has not been set yet
     * @throws IllegalArgumentException if the key is invalid for this holder
     */
    public <T> T get(@NotNull Key<T> key) {
        Long2ObjectOpenHashMap<Object> map = this.map;
        return key.type.cast(map.get(key.key));
    }

    /**
     * Works identically to {@link ExtensionHolder#get(ExtensionHolder.Key)}, but uses the provided {@link Supplier} to
     * generate and return a default value if no extension object has been set for the given key.
     *
     * @param key             the key
     * @param defaultSupplier the default value supplier
     * @param <T>             the type of extension object
     * @return the extension object, or default value (if present)
     */
    public <T> T getOrDefault(@NotNull Key<T> key, @NotNull Supplier<? extends T> defaultSupplier) {
        Long2ObjectOpenHashMap<Object> map = this.map;
        Object object = map.get(key.key);
        if (object == null) {
            return defaultSupplier.get();
        }

        return key.type.cast(object);
    }

    /**
     * Atomically sets a non-null extension object. The runtime type of {@code object} must be assignable to the type
     * that was used to create the {@link Key}. This method is blocking with respect to other writes.
     *
     * @param key    the key used to set the extension object
     * @param object the extension object to set, must be non-null
     * @param <T>    the type of the extension object
     * @return the old value, can be {@code null} if no extension was set before calling this method
     */
    public <T> T set(@NotNull Key<T> key, @NotNull T object) {
        validateKey(key, object);

        Object oldValue;
        lock.lock();
        try {
            Long2ObjectOpenHashMap<Object> oldMap = this.map;
            Long2ObjectOpenHashMap<Object> newMap = new Long2ObjectOpenHashMap<>(oldMap);

            oldValue = newMap.put(key.key, object);
            newMap.trim();

            this.map = newMap;
        } finally {
            lock.unlock();
        }

        return key.type.cast(oldValue);
    }

    /**
     * Creates an exact copy of this holder, preserving any stored values, and returns it.
     *
     * @return a copy of this holder
     */
    public @NotNull ExtensionHolder copy() {
        Long2ObjectOpenHashMap<Object> map = this.map;
        return new ExtensionHolder(map);
    }

    /**
     * Returns the size of this holder (number of currently-registered extensions).
     *
     * @return the number of currently-registered extensions
     */
    public int size() {
        Long2ObjectOpenHashMap<Object> map = this.map;
        return map.size();
    }
}
