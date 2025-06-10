package org.sunbird.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class RedisCacheUtilTest {

    @AfterAll
    static void tearDown() {
        // Clean up keys used in testing
        RedisCacheUtil.delete("test-key-1", "test-key-2", "list-key");
        RedisCacheUtil.deleteByPattern("async-key-*");
    }

    @Test
    void testSetAndGetWithoutTTL() {
        RedisCacheUtil.set("test-key-1", "test-value-1", 0);
        String result = RedisCacheUtil.get("test-key-1", key -> "", 0);
        Assertions.assertEquals("test-value-1", result);
    }

    @Test
    void testSetAndGetWithTTLAndHandler() {
        String key = "test-key-2";
        RedisCacheUtil.set(key, "", 1);

        String result = RedisCacheUtil.get(key, k -> "value-from-handler", 10);
        Assertions.assertEquals("value-from-handler", result);

        String result2 = RedisCacheUtil.get(key, k -> "", 0);
        Assertions.assertEquals("value-from-handler", result2);
    }

    @Test
    void testAsyncGetWithHandler() throws Exception {
        String key = "async-key-1";
        RedisCacheUtil.set(key, "", 1);

        CompletableFuture<String> future = RedisCacheUtil.getAsync(key, k ->
                CompletableFuture.completedFuture("async-handler-value"), 10
        );

        String result = future.get();
        Assertions.assertEquals("async-handler-value", result);
    }

    @Test
    void testIncrementAndGet() {
        String key = "counter-key";
        RedisCacheUtil.delete(key); // Reset
        double counter = RedisCacheUtil.incrementAndGet(key);
        Assertions.assertEquals(1.0, counter);
    }

    @Test
    void testSaveAndGetList() {
        String key = "list-key";
        RedisCacheUtil.saveList(key, Arrays.asList("A", "B", "C"), 0, false);
        List<String> result = RedisCacheUtil.getList(key, k -> Arrays.asList("D", "E"), 0, 0);
        Assertions.assertTrue(result.contains("A"));
        Assertions.assertTrue(result.contains("B"));
        Assertions.assertTrue(result.contains("C"));
    }

    @Test
    void testAddToListAndRemoveFromList() {
        String key = "list-key";
        RedisCacheUtil.addToList(key, Arrays.asList("D", "E"));
        List<String> updated = RedisCacheUtil.getList(key, null, 0, 0);
        Assertions.assertTrue(updated.contains("D"));
        Assertions.assertTrue(updated.contains("E"));

        RedisCacheUtil.removeFromList(key, Arrays.asList("A", "D"));
        List<String> finalList = RedisCacheUtil.getList(key, null, 0, 0);
        Assertions.assertFalse(finalList.contains("A"));
        Assertions.assertFalse(finalList.contains("D"));
    }

    @Test
    void testDeleteByPattern() {
        String key1 = "async-key-abc";
        String key2 = "async-key-def";
        RedisCacheUtil.set(key1, "1", 0);
        RedisCacheUtil.set(key2, "2", 0);

        RedisCacheUtil.deleteByPattern("async-key-*");
        String result1 = RedisCacheUtil.get(key1, k -> "", 0);
        String result2 = RedisCacheUtil.get(key2, k -> "", 0);

        Assertions.assertEquals("", result1);
        Assertions.assertEquals("", result2);
    }
}