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

        assertEquals("steank", extensionHolder.get(firstKey));
        assertEquals("vegetals", extensionHolder.get(secondKey));
    }

    @Test
    void setMany() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        List<ExtensionHolder.Key<String>> keys = new ArrayList<>(50000);
        for (int i = 0; i < 50000; i++) {
            keys.add(extensionHolder.requestKey(String.class));
        }

        int i = 0;
        for (ExtensionHolder.Key<String> key : keys) {
            extensionHolder.set(key, Integer.toString(i++));
        }

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

    @Test
    void typeAssignability() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        ExtensionHolder.Key<Object> key = extensionHolder.requestKey(Object.class);

        extensionHolder.set(key, "this is a string");
        assertEquals("this is a string", extensionHolder.get(key));
    }

    @Test
    void singleInheritance() {
        ExtensionHolder parent = new ExtensionHolder();
        ExtensionHolder child = parent.derive();

        var parentKey = parent.requestKey(String.class);
        var childKey = child.requestKey(String.class);

        parent.set(parentKey, "parent value");
        child.set(childKey, "child value");

        child.set(parentKey, "value set with inherited key");

        assertThrows(IllegalArgumentException.class, () -> parent.set(childKey, "this should not work"));

        assertEquals("parent value", parent.get(parentKey));
        assertEquals("child value", child.get(childKey));
        assertEquals("value set with inherited key", child.get(parentKey));
    }

    @Test
    void multiInheritance() {
        ExtensionHolder root = new ExtensionHolder();
        ExtensionHolder middle = root.derive();
        ExtensionHolder child = middle.derive();

        var rootKey = root.requestKey(String.class);
        var childKey = child.requestKey(String.class);

        assertThrows(IllegalArgumentException.class, () -> root.set(childKey, "fail"));
        assertThrows(IllegalArgumentException.class, () -> middle.set(childKey, "fail"));

        root.set(rootKey, "root");
        middle.set(rootKey, "middle");
        child.set(rootKey, "child");

        assertEquals("root", root.get(rootKey));
        assertEquals("middle", middle.get(rootKey));
        assertEquals("child", child.get(rootKey));
    }

    @Test
    void derivationConsistency() {
        ExtensionHolder root = new ExtensionHolder();
        var rootKey = root.requestKey(String.class);

        ExtensionHolder rootDerivation = root.derive();

        var rootDerivationKey = rootDerivation.requestKey(Integer.class);

        ExtensionHolder mobKey = rootDerivation.derive();
        mobKey.set(rootKey, "rootKey");
        mobKey.set(rootDerivationKey, 0);

        assertEquals("rootKey", mobKey.get(rootKey));
        assertEquals(0, mobKey.get(rootDerivationKey));
    }

    @Test
    void derivationConsistency2() {
        ExtensionHolder root = new ExtensionHolder();
        var rootKey = root.requestKey(String.class);

        ExtensionHolder rootDerivation = root.derive();
        ExtensionHolder rootDerivation2 = root.derive();

        var firstKey = rootDerivation2.requestKey(String.class);
        var secondKey = rootDerivation2.requestKey(String.class);
        var thirdKey = rootDerivation2.requestKey(String.class);

        var rootDerivationKey = rootDerivation.requestKey(Integer.class);

        ExtensionHolder mobKey = rootDerivation.derive();
        mobKey.set(rootKey, "rootKey");
        mobKey.set(rootDerivationKey, 0);

        assertEquals("rootKey", mobKey.get(rootKey));
        assertEquals(0, mobKey.get(rootDerivationKey));
    }
}