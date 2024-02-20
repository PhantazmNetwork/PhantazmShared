package org.phantazm.commons;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionHolderTest {
    @Test
    void setOne() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        ExtensionHolder.Key<String> stringKey = extensionHolder.requestKey(String.class);

        extensionHolder.set(stringKey, "vegetals");

        assertEquals("vegetals", extensionHolder.get(stringKey));
    }

    @Test
    void setTwo() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        ExtensionHolder.Key<String> firstKey = extensionHolder.requestKey(String.class);
        ExtensionHolder.Key<String> secondKey = extensionHolder.requestKey(String.class);

        extensionHolder.set(firstKey, "steank");
        extensionHolder.set(secondKey, "vegetals");

        assertEquals("steank", extensionHolder.get(firstKey));
        assertEquals("vegetals", extensionHolder.get(secondKey));
    }

    @Test
    void trim() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        ExtensionHolder.Key<String> firstKey = extensionHolder.requestKey(String.class);
        ExtensionHolder.Key<String> secondKey = extensionHolder.requestKey(String.class);

        extensionHolder.set(firstKey, "steank");
        extensionHolder.set(secondKey, "vegetals");

        extensionHolder.trimToSize();

        assertEquals("steank", extensionHolder.get(firstKey));
        assertEquals("vegetals", extensionHolder.get(secondKey));
    }

    @Test
    void setMany() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        List<ExtensionHolder.Key<String>> keys = new ArrayList<>(20000);
        for (int i = 0; i < 20000; i++) {
            keys.add(extensionHolder.requestKey(String.class));
        }

        int i = 0;
        for (ExtensionHolder.Key<String> key : keys) {
            extensionHolder.set(key, Integer.toString(i++));
        }

        extensionHolder.trimToSize();
        int j = 0;
        for (ExtensionHolder.Key<String> key : keys) {
            assertEquals(Integer.toString(j++), extensionHolder.get(key));
        }
    }

    @Test
    void disallowsForeignKeys() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        ExtensionHolder otherHolder = new ExtensionHolder();

        ExtensionHolder.Key<?> key = extensionHolder.requestKey(Object.class);
        ExtensionHolder.Key<?> otherKey = otherHolder.requestKey(Object.class);

        assertThrows(IllegalArgumentException.class, () -> otherHolder.get(key));
        assertThrows(IllegalArgumentException.class, () -> extensionHolder.get(otherKey));
    }
}