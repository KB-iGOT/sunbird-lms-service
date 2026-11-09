package org.sunbird.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the contract that matters for every environment without org.info.redis.host: the cache is
 * disabled and every call is a safe no-op / miss, so OrgDaoImpl behaves exactly as before.
 */
class OrgInfoCacheTest {

  @BeforeEach
  void reset() {
    OrgInfoCache.resetForTests();
  }

  @AfterEach
  void cleanup() {
    OrgInfoCache.resetForTests();
  }

  @Test
  void disabledWhenHostNotConfigured() {
    // org.info.redis.host is blank in externalresource.properties and unset in the test env
    assertFalse(OrgInfoCache.isEnabled());
  }

  @Test
  void allOperationsAreNoOpsWhenDisabled() {
    assertNull(OrgInfoCache.get("org-1"));
    assertTrue(OrgInfoCache.getAll(Arrays.asList("org-1", "org-2")).isEmpty());
    OrgInfoCache.put("org-1", Map.of("id", "org-1"));
    OrgInfoCache.evict("org-1");
    assertNull(OrgInfoCache.get("org-1"));
  }

  @Test
  void keyPrefixIsStable() {
    assertEquals("org:", OrgInfoCache.KEY_PREFIX);
  }
}
