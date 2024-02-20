package org.phantazm.commons;

import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe container of "extension objects", which can be accessed using corresponding
 * {@link ExtensionHolder.Key}s. It is intended for use as an arbitrary container of data, to be associated with another
 * object, and optimized for concurrent reads and writes. Especially for small data sets, it should be significantly
 * faster and more memory efficient than an equivalent {@link ConcurrentHashMap}, and encounter fewer cases of lock
 * contention.
 */
public class ExtensionHolder {
    private static final AtomicInteger HOLDER_ID = new AtomicInteger();
    private static final int MINIMUM_SIZE = 10;

    //used instead of AtomicReferenceArray because we want to be able to access the internal array directly sometimes
    private static class AtomicObjectArray {
        private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Object[].class);

        private final Object[] array;

        private AtomicObjectArray(int length) {
            this.array = new Object[length];
        }

        private Object get(int i) {
            return AA.getVolatile(array, i);
        }

        private void set(int i, Object o) {
            AA.setVolatile(array, i, o);
        }

        private Object getAndSet(int i, Object o) {
            return AA.getAndSet(array, i, o);
        }

        private int length() {
            return array.length;
        }
    }

    /**
     * A key, used to access an extension object. Instances can be obtained from
     * {@link ExtensionHolder#requestKey(Class)}.
     * <p>
     * Keys can only be used to retrieve or set values for the ExtensionHolder to which they belong.
     *
     * @param <T> the type of object to be accessed
     */
    public static class Key<T> {
        private final Class<T> type;
        private final int index;
        private final int holderId;

        private Key(Class<T> type, int index, int holderId) {
            this.type = type;
            this.index = index;
            this.holderId = holderId;
        }

    }

    private final Object sync = new Object();

    private volatile AtomicObjectArray array;

    private final AtomicInteger index;
    private final AtomicInteger writeGuard;
    private final int id;

    /**
     * Creates a new instance of this class. This does not initialize the internal array; that will be done later if
     * needed. Therefore, it is generally cheap to create many instances of this class.
     */
    public ExtensionHolder() {
        this.index = new AtomicInteger();
        this.writeGuard = new AtomicInteger();
        this.id = HOLDER_ID.getAndIncrement();
    }

    private void validateKey(Key<?> key) {
        if (key.holderId != id) {
            throw new IllegalArgumentException("Key was requested from a different ExtensionHolder");
        }
    }

    private void validateKeyAndObject(Key<?> key, Object object) {
        validateKey(key);
        Objects.requireNonNull(object);

        if (!key.type.isAssignableFrom(object.getClass())) {
            throw new IllegalArgumentException("Object type is not valid given key type");
        }
    }

    private int computeRequiredSize(int index) {
        int requiredSize = index + 1;
        return requiredSize + (requiredSize >> 1);
    }

    /**
     * Creates a new key for this holder.
     *
     * @param type the class of the extension object
     * @param <T>  the type of extension object
     * @return a new key
     */
    public <T> @NotNull Key<T> requestKey(@NotNull Class<T> type) {
        return new Key<>(Objects.requireNonNull(type), index.getAndIncrement(), id);
    }

    /**
     * Gets an extension object, given a key.
     *
     * @param key the key
     * @param <T> the type of the extension object
     * @return the extension object, or {@code null} if such an object has not been set yet
     */
    public <T> T get(@NotNull Key<T> key) {
        validateKey(key);

        AtomicObjectArray array = this.array;
        if (array == null || key.index >= array.length()) {
            return null;
        }

        return key.type.cast(array.get(key.index));
    }

    /**
     * Atomically sets a non-null extension object. A best-effort attempt will be made to avoid acquiring any locks.
     * This method may need to resize the internal array in order to fit the object. If it does so, the array will
     * generally have more capacity than is strictly needed to fit the element, in order to prevent unnecessary resizing
     * in the future. If it is known that additional extension objects will not be added, users can call
     * {@link ExtensionHolder#trimToSize()} to minimize the size of the array.
     *
     * @param key    the key used to set the extension object; must have been created by this extension holder
     * @param object the extension object to set, must be non-null
     * @param <T>    the type of the extension object
     * @return the old value, can be {@code null} if no extension was set before calling this method
     */
    public <T> T set(@NotNull Key<T> key, @NotNull T object) {
        validateKeyAndObject(key, object);

        AtomicObjectArray array = this.array;

        boolean hasSavedOldValue = false;
        Object savedOldValue = null;
        if (array != null && key.index < array.length()) {
            //the least significant bit of stamp will be 1 if we are currently resizing, and 0 if we are not
            int stamp = writeGuard.get();
            if ((stamp & 1) == 0) {
                Object oldValue = array.getAndSet(key.index, object);

                //if the guard differs from the stamp, it means we completed an entire resize while we were setting the
                //array value
                //if the guard is the same as the stamp, our write succeeded and we can simply return
                if (writeGuard.get() == stamp) {
                    //we were able to set without acquiring a lock
                    return key.type.cast(oldValue);
                }

                //blocked by resize
                hasSavedOldValue = true;
                savedOldValue = oldValue;
            }
        }

        synchronized (sync) {
            array = this.array;

            //array needs to be created
            if (array == null) {
                AtomicObjectArray newArray = new AtomicObjectArray(Math.max(computeRequiredSize(key.index),
                    MINIMUM_SIZE));
                newArray.array[key.index] = object;

                this.array = newArray;
                return null;
            }

            //array exists, and can be indexed into without a resize
            if (key.index < array.length()) {
                //we ended up getting blocked by a resize
                if (hasSavedOldValue) {
                    array.set(key.index, object);
                    return key.type.cast(savedOldValue);
                }

                return key.type.cast(array.getAndSet(key.index, object));
            }

            writeGuard.incrementAndGet();
            try {
                AtomicObjectArray arrayCopy = new AtomicObjectArray(computeRequiredSize(key.index));
                System.arraycopy(array.array, 0, arrayCopy.array, 0, array.array.length);
                arrayCopy.array[key.index] = object;

                this.array = arrayCopy;
                return null;
            } finally {
                writeGuard.incrementAndGet();
            }
        }
    }

    /**
     * Trims the internal array to optimal capacity. Does nothing if the array is already optimally sized. Should not be
     * called if keys are still actively being requested, as that will only incur wasteful resizes and no benefit.
     */
    public void trimToSize() {
        synchronized (sync) {
            AtomicObjectArray array = this.array;
            int index = this.index.get();

            if (array.length() <= index) {
                //size is already optimal
                return;
            }

            writeGuard.incrementAndGet();
            try {
                AtomicObjectArray arrayCopy = new AtomicObjectArray(index);
                System.arraycopy(array.array, 0, arrayCopy.array, 0, arrayCopy.array.length);

                this.array = arrayCopy;
            } finally {
                writeGuard.incrementAndGet();
            }
        }
    }
}
