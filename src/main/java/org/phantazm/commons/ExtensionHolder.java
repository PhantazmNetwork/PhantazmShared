package org.phantazm.commons;

import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private static final long HOLDER_ID_MASK = 0x0000_0000_FFFF_FFFFL;

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
        private final int localIndex;
        private final AtomicInteger inheritedIndex;
        private final int holderId;
        private final long inheritance;

        private final AtomicInteger actualInheritedIndex = new AtomicInteger(-1);

        private Key(Class<T> type, int localIndex, AtomicInteger inheritedIndex, int holderId, long inheritance) {
            this.type = type;
            this.localIndex = localIndex;
            this.inheritedIndex = inheritedIndex;
            this.holderId = holderId;
            this.inheritance = inheritance;
        }

        private int getIndex(int id) {
            if (id == holderId || inheritedIndex == null) {
                return localIndex;
            }

            if (actualInheritedIndex.compareAndSet(-1, -2)) {
                actualInheritedIndex.set(inheritedIndex.getAndIncrement());
            }

            int value;
            do {
                value = actualInheritedIndex.get();
            }
            while (value < 0);

            return value;
        }
    }

    private final Object sync = new Object();

    private final AtomicInteger localIndex;
    private final AtomicInteger inheritedIndex;
    private final int id;

    /*
    upper 32 bits are inheritance ID; lower 32 are the holder ID of the root extension holder
    inheritance ID starts at 0 and increments by 1 for every successive "subclass" holder
     */
    private final long inheritance;

    private volatile int resizeGuard;

    private volatile Object[] localArray;
    private volatile Object[] inheritedArray;

    /**
     * Creates a new instance of this class. This does not initialize the internal array; that will be done later if
     * needed. Therefore, it is generally cheap to create many instances of this class.
     */
    public ExtensionHolder() {
        this.localIndex = new AtomicInteger();
        this.inheritedIndex = null;
        this.id = HOLDER_ID.getAndIncrement();

        this.inheritance = ((long) this.id) & HOLDER_ID_MASK;

        this.resizeGuard = 0;
    }

    /*
    this isn't actually a copy constructor
     */
    @SuppressWarnings("CopyConstructorMissesField")
    private ExtensionHolder(ExtensionHolder parent) {
        this.inheritedIndex = parent.inheritedIndex == null ? parent.localIndex : parent.inheritedIndex;
        this.localIndex = new AtomicInteger();
        this.id = HOLDER_ID.getAndIncrement();

        this.resizeGuard = 0;

        //zero if parent is the root of this inheritance
        int parentInheritanceId = inheritanceId(parent.inheritance);

        this.inheritance = ((long) (parentInheritanceId + 1) << 32) |
            ((long) (parentInheritanceId == 0 ? parent.id : inheritanceRoot(parent.inheritance))) & HOLDER_ID_MASK;
    }

    /*
    static utils for volatile operations on arrays
     */
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

    private static int inheritanceId(long inheritance) {
        return (int) (inheritance >> 32);
    }

    private static int inheritanceRoot(long inheritance) {
        return (int) (HOLDER_ID_MASK & inheritance);
    }

    /*
    Superclass-constructed keys are valid for subclasses, but not vice-versa
     */
    private void validateKey(Key<?> key) {
        //key must have the same inheritance root as this instance
        //smaller inheritance ID = superclass
        if (key.holderId != id && (inheritanceRoot(key.inheritance) != inheritanceRoot(this.inheritance) ||
            inheritanceId(key.inheritance) > inheritanceId(this.inheritance))) {
            throwKeyValidationIEE();
        }
    }

    private void throwKeyValidationIEE() {
        throw new IllegalArgumentException("Key is not valid for this ExtensionHolder");
    }

    private void validateKeyAndObject(Key<?> key, Object object) {
        validateKey(key);
        Objects.requireNonNull(object);

        if (!key.type.isAssignableFrom(object.getClass())) {
            throw new IllegalArgumentException("Object type is not valid given key type");
        }
    }

    private Object[] getArray(Key<?> key) {
        return key.holderId != id ? inheritedArray : localArray;
    }

    private void setArray(Key<?> key, Object[] newValue) {
        if (key.holderId != id) {
            this.inheritedArray = newValue;
        } else {
            this.localArray = newValue;
        }
    }

    private void createNewArrayForKey(Key<?> key, int index, Object object) {
        Object[] newArray = new Object[Math.max(computeRequiredSize(index), MINIMUM_SIZE)];
        newArray[index] = object;

        setArray(key, newArray);
    }

    //only to be called when sync is held
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private void resizeArrayForKey(Key<?> key, int index, Object[] array, Object object) {
        Object[] arrayCopy = new Object[computeRequiredSize(index)];

        resizeGuard++;
        try {
            System.arraycopy(array, 0, arrayCopy, 0, array.length);
            arrayCopy[index] = object;

            setArray(key, arrayCopy);
        } finally {
            resizeGuard++;
        }
    }

    private static int computeRequiredSize(int index) {
        int requiredSize = index + 1;
        return requiredSize + (requiredSize >> 1);
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
        return new Key<>(Objects.requireNonNull(type), this.localIndex.getAndIncrement(), inheritedIndex, id,
            inheritance);
    }

    /**
     * Creates a new {@link ExtensionHolder} derived from this one. The child ExtensionHolder can use keys requested
     * from the parent, but the parent will be unable to use keys requested from the child.
     *
     * @return the derived holder
     */
    public @NotNull ExtensionHolder derive() {
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

        Object[] array = getArray(key);
        int index = key.getIndex(id);

        if (array == null || index >= array.length) {
            return null;
        }

        return key.type.cast(VolatileArray.get(array, index));
    }

    /**
     * Works identically to {@link ExtensionHolder#get(Key)}, but uses the provided {@link Supplier} to generate and
     * return a default value if no extension object has been set for the given key.
     *
     * @param key             the key
     * @param defaultSupplier the default value supplier
     * @param <T>             the type of extension object
     * @return the extension object, or default value (if present)
     */
    public <T> T getOrDefault(@NotNull Key<T> key, @NotNull Supplier<? extends T> defaultSupplier) {
        validateKey(key);

        Object[] array = getArray(key);
        int index = key.getIndex(id);

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

        Object[] array = getArray(key);
        int index = key.getIndex(id);

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
                    array = getArray(key);

                    if (index < array.length) {
                        //make sure the value actually got set
                        VolatileArray.compareAndSet(array, index, null, object);
                        return true;
                    }

                    resizeArrayForKey(key, index, array, object);
                    return true;
                }
            }
        }

        synchronized (sync) {
            array = getArray(key);

            if (array == null) {
                createNewArrayForKey(key, index, object);
                return true;
            }

            if (index < array.length) {
                return VolatileArray.compareAndSet(array, index, null, object);
            }

            resizeArrayForKey(key, index, array, object);
            return true;
        }
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

        Object[] array = getArray(key);
        int index = key.getIndex(id);

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
            array = getArray(key);

            //array needs to be created
            if (array == null) {
                createNewArrayForKey(key, index, object);
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

            resizeArrayForKey(key, index, array, object);
            return null;
        }
    }

    /**
     * Trims any internal arrays to size.
     */
    public void trimToSize() {
        synchronized (sync) {
            Object[] localArray = this.localArray;
            Object[] inheritedArray = this.inheritedArray;

            resizeGuard++;
            try {
                int localIndex = this.localIndex.get();
                if (localArray != null && localArray.length > localIndex) {
                    Object[] copy = new Object[localIndex];
                    System.arraycopy(localArray, 0, copy, 0, localIndex);
                    this.localArray = copy;
                }

                int inheritedIndex;
                if (inheritedArray != null && inheritedArray.length > (inheritedIndex = this.inheritedIndex.get())) {
                    Object[] copy = new Object[inheritedIndex];
                    System.arraycopy(inheritedArray, 0, copy, 0, inheritedIndex);
                    this.inheritedArray = copy;
                }
            } finally {
                resizeGuard++;
            }
        }
    }
}
