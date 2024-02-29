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
        ExtensionHolder child = parent.derive(false);

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
        ExtensionHolder middle = root.derive(false);
        ExtensionHolder child = middle.derive(false);

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

        ExtensionHolder rootDerivation = root.derive(false);

        var rootDerivationKey = rootDerivation.requestKey(Integer.class);

        ExtensionHolder mobKey = rootDerivation.derive(false);
        mobKey.set(rootKey, "rootKey");
        mobKey.set(rootDerivationKey, 0);

        assertEquals("rootKey", mobKey.get(rootKey));
        assertEquals(0, mobKey.get(rootDerivationKey));
    }

    @Test
    void derivationConsistency2() {
        ExtensionHolder root = new ExtensionHolder();
        var rootKey = root.requestKey(String.class);

        ExtensionHolder rootDerivation = root.derive(false);
        ExtensionHolder rootDerivation2 = root.derive(false);

        var firstKey = rootDerivation2.requestKey(String.class);
        var secondKey = rootDerivation2.requestKey(String.class);
        var thirdKey = rootDerivation2.requestKey(String.class);

        var rootDerivationKey = rootDerivation.requestKey(Integer.class);

        ExtensionHolder mobKey = rootDerivation.derive(false);
        mobKey.set(rootKey, "rootKey");
        mobKey.set(rootDerivationKey, 0);

        mobKey.trimToSize();
        assertEquals("rootKey", mobKey.get(rootKey));
        assertEquals(0, mobKey.get(rootDerivationKey));
    }

    @Test
    void derivationConsistency3() {
        ExtensionHolder mobRoot = new ExtensionHolder();

        var globalStringKey = mobRoot.requestKey(String.class);
        var globalIntegerKey = mobRoot.requestKey(Integer.class);

        ExtensionHolder mobTypeRoot = mobRoot.derive(false);
        ExtensionHolder mobTypeRoot1 = mobRoot.derive(false);

        var mobTypeRootKey = mobTypeRoot.requestKey(Boolean.class);
        var mobTypeRootKey1 = mobTypeRoot.requestKey(Float.class);

        var mobTypeRootKey_1 = mobTypeRoot1.requestKey(Boolean.class);
        var mobTypeRootKey1_1 = mobTypeRoot1.requestKey(Float.class);

        ExtensionHolder mobHolder1 = mobTypeRoot.derive(false);
        ExtensionHolder mobHolder2 = mobTypeRoot1.derive(false);

        mobHolder1.set(globalStringKey, "global value for mob 1");
        mobHolder1.set(globalIntegerKey, 69);

        mobHolder1.set(mobTypeRootKey, true);
        mobHolder1.set(mobTypeRootKey1, 0.69F);

        mobHolder1.set(mobTypeRootKey_1, true);
        mobHolder1.set(mobTypeRootKey1_1, 0.420F);

        mobHolder2.set(globalStringKey, "global value for mob 2");
        mobHolder2.set(globalIntegerKey, 69);

        mobHolder2.set(mobTypeRootKey, true);
        mobHolder2.set(mobTypeRootKey1, 0.69F);

        mobHolder2.set(mobTypeRootKey_1, true);
        mobHolder2.set(mobTypeRootKey1_1, 0.420F);

        mobHolder1.trimToSize();
        mobHolder2.trimToSize();

        assertEquals("global value for mob 1", mobHolder1.get(globalStringKey));
        assertEquals(69, mobHolder1.get(globalIntegerKey));

        assertEquals(true, mobHolder1.get(mobTypeRootKey));
        assertEquals(0.69F, mobHolder1.get(mobTypeRootKey1));

        assertEquals(true, mobHolder1.get(mobTypeRootKey_1));
        assertEquals(0.420F, mobHolder1.get(mobTypeRootKey1_1));

        assertEquals("global value for mob 2", mobHolder2.get(globalStringKey));
        assertEquals(69, mobHolder2.get(globalIntegerKey));

        assertEquals(true, mobHolder2.get(mobTypeRootKey));
        assertEquals(0.69F, mobHolder2.get(mobTypeRootKey1));

        assertEquals(true, mobHolder2.get(mobTypeRootKey_1));
        assertEquals(0.420F, mobHolder2.get(mobTypeRootKey1_1));
    }
}