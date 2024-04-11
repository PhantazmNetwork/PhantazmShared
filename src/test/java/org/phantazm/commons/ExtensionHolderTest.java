package org.phantazm.commons;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtensionHolderTest {

    @Test
    void simpleStorage() {
        ExtensionHolder extensionHolder = new ExtensionHolder();
        var string = ExtensionHolder.requestKey(String.class);
        var string1 = ExtensionHolder.requestKey(String.class);
        var integer = ExtensionHolder.requestKey(Integer.class);


        assertNull(extensionHolder.get(string));
        assertNull(extensionHolder.get(string1));
        assertNull(extensionHolder.get(integer));

        extensionHolder.set(string, "this is a string");
        assertEquals("this is a string", extensionHolder.get(string));
        assertNull(extensionHolder.get(string1));
        assertNull(extensionHolder.get(integer));

        extensionHolder.set(string1, "this is another string");
        assertEquals("this is a string", extensionHolder.get(string));
        assertEquals("this is another string", extensionHolder.get(string1));
        assertNull(extensionHolder.get(integer));

        extensionHolder.set(integer, 69);
        assertEquals("this is a string", extensionHolder.get(string));
        assertEquals("this is another string", extensionHolder.get(string1));
        assertEquals(69, extensionHolder.get(integer));
    }
}