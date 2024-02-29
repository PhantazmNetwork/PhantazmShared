package org.phantazm.commons;

import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

    @SuppressWarnings("CopyConstructorMissesField")
    private ExtensionHolder(ExtensionHolder parent) {
        this.id = HOLDER_ID.getAndIncrement();

        this.inheritanceRoot = parent.inheritanceId == 0 ? parent.id : parent.inheritanceRoot;
        this.inheritanceId = parent.inheritanceId + 1;
        this.keysRequested = parent.keysRequested;

        this.indices = parent.indices;
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
     * from the parent, but the parent will be unable to use keys requested from the child. No data is copied over; the
     * child holder will be initially empty.
     *
     * @return the derived holder
     */
    public @NotNull ExtensionHolder derive() {
        if (this.inheritanceId >= 7) {
            throw new IllegalStateException("Cannot derive further from this ExtensionHolder");
        }

        return new ExtensionHolder(this);
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
