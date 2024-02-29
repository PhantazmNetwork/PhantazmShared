package org.phantazm.commons;

import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe holder of typed objects. Designed to be a faster and more concurrency-friendly data structure than
 * {@link ConcurrentHashMap} for small sets of values, that are most often written once (but can tolerate concurrent
 * writes well) and read extremely frequently.
 * <p>
 * Lock contention should be rare with this class. Synchronization is only used when resizing the internal array, which
 * is necessarily a blocking operation, but this cannot block reads, only writes.
 * <p>
 * To access (read or write) values, first a key must be requested using the {@link ExtensionHolder#requestKey(Class)}
 * method, which accepts a {@link Class} object as the data type to be stored (associated with the key). After, a value
 * can be written using the {@link ExtensionHolder#set(Key, Object)} method, and read using the
 * {@link ExtensionHolder#get(Key)} method.
 * <p>
 * Keys normally cannot be shared across different {@link ExtensionHolder}s, for either reading or writing. Attempting
 * to do so will result in an {@link IllegalArgumentException}. However, it is possible to construct a "family" of
 * ExtensionHolders which <i>do</i> allow key sharing. "Derived" holders may be constructed using
 * {@link ExtensionHolder#derive(boolean)}. Keys requested from the "parent" holder may be used in the "derived" holder.
 * However, <i>the reverse is not true</i>, as keys requested from a derived holder may not be used in any parent
 * holders.
 * <p>
 * "Sibling" holders are any holders that share a parent. They can be created through multiple invocations of
 * {@link ExtensionHolder#derive(boolean)} on the same object, or directly using the
 * {@link ExtensionHolder#sibling(boolean)} method. Sibling holders all share keys; any key requested from holder
 * {@code x} is usable in holder {@code y} if {@code y} is a sibling.
 * <p>
 * Although they share keys, related holders (parent-derivation or sibling-sibling) do not synchronize their values.
 * That is, a value that is set on a holder will not become visible to a sibling or derivation, and vice-versa.
 * <p>
 * There are hard limits to both key requests and the number of "nested derivations" (i.e. derivations of derivations).
 * Derivation chains may only go 7 levels deep, 8 if the initial parentless holder is considered. Additionally, for each
 * "family tree" of holders, it is not possible to request more than 65536 keys. Attempting to exceed either of this
 * limits will result in an {@link IllegalStateException} being thrown in the appropriate method call.
 */
public class ExtensionHolder {
    private static final AtomicInteger HOLDER_ID = new AtomicInteger();
    private static final int MINIMUM_SIZE = 10;

    /*
    atomic operations on 'high' and 'low' long values (for combined 128 bits), grouped into 16-bit chunks
    each 16-bit chunk can be simultaneously added to
    this class does not implement "overflow" protection for chunks, that must be checked externally
     */
    private static class IndexHolder {
        //can replace unsafe usage when VarHandles support operations on numeric types without boxing/unboxing
        private static final Unsafe U;

        private static final long IH;
        private static final long IL;

        private static final long[] OFFSETS;
        private static final long FULL_DELTA = 0x0001_0001_0001_0001L;
        private static final long INDEX_MASK = 0xFFFF;
        private static final long FULL_MASK = 0xFFFF_FFFF_FFFF_FFFFL;

        static {
            try {
                Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);

                U = (Unsafe) unsafeField.get(null);
                IH = U.objectFieldOffset(IndexHolder.class.getDeclaredField("indexHigh"));
                IL = U.objectFieldOffset(IndexHolder.class.getDeclaredField("indexLow"));

                OFFSETS = new long[]{IL, IH};
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile long indexHigh;
        private volatile long indexLow;

        private static long delta(int inheritanceMod) {
            return (FULL_MASK << (inheritanceMod << 4)) & FULL_DELTA;
        }

        /*
        simultaneously increments index for all derived holders too
         */
        private int getAndIncrementIndex(int inheritanceId) {
            int mod = inheritanceId & 3;
            if (inheritanceId > 3) {
                long old = U.getAndAddLong(this, IH, delta(mod));
                return (int) ((old >>> (mod << 4)) & INDEX_MASK);
            }

            U.getAndAddLong(this, IH, FULL_DELTA);
            VarHandle.fullFence();
            long old = U.getAndAddLong(this, IL, delta(mod));
            return (int) ((old >>> (mod << 4)) & INDEX_MASK);
        }

        private int getIndex(int inheritanceId) {
            return (int) ((U.getLong(this, OFFSETS[inheritanceId >>> 2]) >>> ((inheritanceId & 3) << 4)) & INDEX_MASK);
        }
    }

    private static class VolatileArray {
        private static final VarHandle AA = MethodHandles.arrayElementVarHandle(Object[].class);

        private static Object get(Object[] array, int i) {
            return AA.getVolatile(array, i);
        }

        private static void set(Object[] array, int i, Object o) {
            AA.setVolatile(array, i, o);
        }

        private static Object getAndSet(Object[] array, int i, Object o) {
            return AA.getAndSet(array, i, o);
        }

        @SuppressWarnings("SameParameterValue")
        private static boolean compareAndSet(Object[] array, int i, Object expectedValue, Object newValue) {
            return AA.compareAndSet(array, i, expectedValue, newValue);
        }
    }

    /**
     * Represents a key to retrieve a typed value from an {@link ExtensionHolder}. Each ExtensionHolder is capable of
     * holding any number of types of values. Therefore, this object provides the necessary type information.
     *
     * @param <T> the type of value to retrieve
     */
    public static class Key<T> {
        private final Class<T> type;
        private final int index;

        private final int holderId;
        private final int inheritanceRoot;
        private final int inheritanceId;

        private Key(Class<T> type, int index, int holderId, int inheritanceRoot, int inheritanceId) {
            this.type = type;
            this.index = index;
            this.holderId = holderId;
            this.inheritanceRoot = inheritanceRoot;
            this.inheritanceId = inheritanceId;
        }
    }

    private final int id;
    private final int inheritanceRoot;
    private final int inheritanceId;
    private final AtomicInteger keysRequested;

    private final Object sync = new Object();

    //instance shared among all derivations
    private final IndexHolder indices;

    private volatile Object[] array;
    private volatile int resizeGuard;

    /**
     * Creates a new instance of this class. This does not initialize the internal array; that will be done later if
     * needed. Therefore, it is generally cheap to create many instances of this class.
     */
    public ExtensionHolder() {
        this.id = HOLDER_ID.getAndIncrement();

        this.inheritanceRoot = this.id;
        this.inheritanceId = 0;
        this.keysRequested = new AtomicInteger();

        this.indices = new IndexHolder();
    }

    private ExtensionHolder(ExtensionHolder other, boolean isParent, boolean copyValues) {
        this.id = HOLDER_ID.getAndIncrement();

        if (copyValues) {
            Object[] otherArray = other.array;
            if (otherArray != null) {
                Object[] ourArray = new Object[otherArray.length];
                System.arraycopy(otherArray, 0, ourArray, 0, ourArray.length);
                this.array = ourArray;
            }
        }

        if (isParent) {
            this.inheritanceRoot = other.inheritanceId == 0 ? other.id : other.inheritanceRoot;
            this.inheritanceId = other.inheritanceId + 1;
            this.keysRequested = other.keysRequested;

            this.indices = other.indices;
            return;
        }

        if (other.inheritanceRoot == other.id) {
            //our sibling has no parent, so we shouldn't either
            this.inheritanceRoot = this.id;
            this.inheritanceId = 0;
            this.keysRequested = new AtomicInteger();
            this.indices = new IndexHolder();
        } else {
            //otherwise, we share inheritance information with our sibling
            this.inheritanceRoot = other.inheritanceRoot;
            this.inheritanceId = other.inheritanceId;
            this.keysRequested = other.keysRequested;
            this.indices = other.indices;
        }
    }

    private void throwKeyValidationIEE() {
        throw new IllegalArgumentException("Key is not valid for this ExtensionHolder");
    }

    private void validateKey(Key<?> key) {
        //key must have the same inheritance root as this instance
        //smaller inheritance ID = superclass
        if (key.holderId != id && (key.inheritanceRoot != this.inheritanceRoot || key.inheritanceId > this.inheritanceId)) {
            throwKeyValidationIEE();
        }
    }

    private void validateKeyAndObject(Key<?> key, Object object) {
        validateKey(key);
        Objects.requireNonNull(object);

        if (!key.type.isAssignableFrom(object.getClass())) {
            throw new IllegalArgumentException("Object type is not valid given key type");
        }
    }

    private static int computeRequiredSize(int index) {
        int requiredSize = index + 1;
        return requiredSize + (requiredSize >> 1);
    }

    private void createNewArrayForIndex(int index, Object object) {
        Object[] newArray = new Object[Math.max(computeRequiredSize(index), MINIMUM_SIZE)];
        newArray[index] = object;

        this.array = newArray;
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private void resizeArrayForIndex(int index, Object[] array, Object object) {
        Object[] arrayCopy = new Object[computeRequiredSize(index)];

        resizeGuard++;
        try {
            System.arraycopy(array, 0, arrayCopy, 0, array.length);
            arrayCopy[index] = object;
            this.array = arrayCopy;
        } finally {
            resizeGuard++;
        }
    }

    /**
     * Creates a new key for this holder. This key will be valid for derived ExtensionHolders, but not for holders for
     * which this instance is derived.
     * <p>
     * Up to {@code 65536} keys may be requested for any given family tree of {@link ExtensionHolder}s. In other words,
     * this limit is shared between siblings, as well as parents and derivations. Attempting to exceed this limit will
     * result in an {@link IllegalStateException}.
     *
     * @param type the class of the extension object
     * @param <T>  the type of extension object
     * @return a new key
     */
    public <T> @NotNull Key<T> requestKey(@NotNull Class<T> type) {
        int newValue = keysRequested.updateAndGet(current -> {
            return Math.min(current + 1, 65536);
        });

        if (newValue == 65536) {
            throw new IllegalStateException("Too many keys have been requested from this holder and/or derivations!");
        }

        return new Key<>(type, indices.getAndIncrementIndex(this.inheritanceId), id, this.inheritanceRoot,
            this.inheritanceId);
    }

    /**
     * Creates a new {@link ExtensionHolder} derived from this one. The child ExtensionHolder can use keys requested
     * from the parent, but the parent will be unable to use keys requested from the child.
     * <p>
     * There is a hard derivation depth (that is, derivations of derivations) limit of 7. Attempting to go beyond this
     * limit will result in a {@link IllegalStateException}. Similarly, parents and derivations will share the hard key
     * request limit of {@code 65536}.
     * <p>
     * If copyValues is {@code true}, values from the parent will be copied to the child once at initialization. Values
     * added to the parent after copying will <i>not</i> be visible in the child (and vice-versa).
     *
     * @param copyValues whether to initialize the derivation with the same values as this instance (the parent)
     * @return the derived holder
     */
    public @NotNull ExtensionHolder derive(boolean copyValues) {
        if (this.inheritanceId >= 7) {
            throw new IllegalStateException("Cannot derive further from this ExtensionHolder");
        }

        return new ExtensionHolder(this, true, copyValues);
    }

    /**
     * Creates a new {@link ExtensionHolder} that is a "sibling" of this one. That is, the created holder will share the
     * same parent as its sibling. All keys valid for one sibling are valid for other siblings.
     * <p>
     * Since siblings do not increase the derivation depth, this method can be called any number of times. However,
     * siblings (as well as any derivations of siblings, and their parents) will share the key request limit of
     * {@code 65536}.
     * <p>
     * If copyValues is {@code true}, values from this instance will be copied to the sibling once at initialization.
     * Values added to the new sibling after copying will <i>not</i> be visible in this instance (and vice-versa).
     *
     * @param copyValues whether the newly-created sibling will be initialized with the same values as this instance
     * @return a new sibling ExtensionHolder
     */
    public @NotNull ExtensionHolder sibling(boolean copyValues) {
        return new ExtensionHolder(this, false, copyValues);
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
        validateKey(key);

        Object[] array = this.array;
        int index = key.index;

        if (array == null || index >= array.length) {
            return null;
        }

        return key.type.cast(VolatileArray.get(array, index));
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
        validateKey(key);

        Object[] array = this.array;
        int index = key.index;

        if (array == null || index >= array.length) {
            return defaultSupplier.get();
        }

        Object object = VolatileArray.get(array, index);
        if (object == null) {
            return defaultSupplier.get();
        }

        return key.type.cast(object);
    }

    /**
     * Atomically sets a non-null extension object. A best-effort attempt will be made to avoid acquiring any locks.
     * This method may need to resize the internal array in order to fit the object. If it does so, the array will
     * generally have more capacity than is strictly needed to fit the element, in order to prevent unnecessary resizing
     * in the future.
     *
     * @param key    the key used to set the extension object; must have been created by this extension holder
     * @param object the extension object to set, must be non-null
     * @param <T>    the type of the extension object
     * @return the old value, can be {@code null} if no extension was set before calling this method
     */
    public <T> T set(@NotNull Key<T> key, @NotNull T object) {
        validateKeyAndObject(key, object);

        Object[] array = this.array;
        int index = key.index;

        boolean hasSavedOldValue = false;
        Object savedOldValue = null;
        if (array != null && index < array.length) {
            //the least significant bit of stamp will be 1 if we are currently resizing, and 0 if we are not
            int stamp = resizeGuard;
            if ((stamp & 1) == 0) {
                Object oldValue = VolatileArray.getAndSet(array, index, object);

                //if the guard differs from the stamp, we either started or completed a resize and our write is invalid
                //if the guard is the same as the stamp, our write succeeded and we can simply return
                if (resizeGuard == stamp) {
                    //we were able to set without acquiring a lock
                    return key.type.cast(oldValue);
                }

                //blocked by resize
                //our volatile set MIGHT have been eaten by the resize!
                hasSavedOldValue = true;
                savedOldValue = oldValue;
            }
        }

        synchronized (sync) {
            array = this.array;

            //array needs to be created
            if (array == null) {
                createNewArrayForIndex(index, object);
                return null;
            }

            //array exists, and can be indexed into without a resize
            if (index < array.length) {
                if (hasSavedOldValue) {
                    VolatileArray.set(array, index, object);

                    //return the value that should have been returned by the first getAndSet
                    return key.type.cast(savedOldValue);
                }

                return key.type.cast(VolatileArray.getAndSet(array, index, object));
            }

            resizeArrayForIndex(index, array, object);
            return null;
        }
    }


    /**
     * Atomically sets the value at the given key, if it is absent. Returns {@code true} if the value was successfully
     * set.
     *
     * @param key    the key
     * @param object the value to set
     * @param <T>    the object type
     * @return true if the value was set (previously absent), false otherwise
     */
    public <T> boolean setIfAbsent(@NotNull Key<T> key, @NotNull T object) {
        validateKeyAndObject(key, object);

        Object[] array = this.array;
        int index = key.index;

        if (array != null && index < array.length) {
            int stamp = resizeGuard;
            if ((stamp & 1) == 0) {
                boolean casSucceeded = VolatileArray.compareAndSet(array, index, null, object);

                //if we failed the CAS, it doesn't matter that we got blocked by an array resize
                //if the CAS succeeded, and we were interrupted by a resize, we have to synchronize
                if (!casSucceeded || resizeGuard == stamp) {
                    return casSucceeded;
                }

                //casSucceeded && resizeGuard != stamp
                synchronized (sync) {
                    array = this.array;

                    if (index < array.length) {
                        //make sure the value actually got set
                        VolatileArray.compareAndSet(array, index, null, object);
                        return true;
                    }

                    resizeArrayForIndex(index, array, object);
                    return true;
                }
            }
        }

        synchronized (sync) {
            array = this.array;

            if (array == null) {
                createNewArrayForIndex(index, object);
                return true;
            }

            if (index < array.length) {
                return VolatileArray.compareAndSet(array, index, null, object);
            }

            resizeArrayForIndex(index, array, object);
            return true;
        }
    }

    /**
     * Trims the internal array to size.
     */
    public void trimToSize() {
        synchronized (sync) {
            Object[] array = this.array;

            resizeGuard++;
            try {
                int localIndex = this.indices.getIndex(this.inheritanceId);
                if (array != null && array.length > localIndex) {
                    Object[] copy = new Object[localIndex];
                    System.arraycopy(array, 0, copy, 0, localIndex);
                    this.array = copy;
                }
            } finally {
                resizeGuard++;
            }
        }
    }
}
