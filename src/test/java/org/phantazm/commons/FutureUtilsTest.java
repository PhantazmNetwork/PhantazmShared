package org.phantazm.commons;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FutureUtilsTest {
    private static CompletableFuture<List<String>> list() {
        return FutureUtils.emptyUnmodifiableListCompletedFuture();
    }

    private static CompletableFuture<Optional<String>> optional() {
        return FutureUtils.emptyOptionalCompletedFuture();
    }

    @Test
    void testEmptyList() {
        List<String> emptyList = list().join();
        assertEquals(0, emptyList.size());
    }

    @Test
    void testEmptyOptional() {
        Optional<String> emptyOptional = optional().join();
        assertTrue(emptyOptional.isEmpty());
    }
}