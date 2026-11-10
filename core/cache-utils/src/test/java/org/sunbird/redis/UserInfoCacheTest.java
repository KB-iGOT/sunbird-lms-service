package org.sunbird.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Without user_profile_redis_host the cache must be disabled and every call a safe no-op / miss. */
class UserInfoCacheTest {

  @BeforeEach
  void reset() {
    UserInfoCache.resetForTests();
  }

  @AfterEach
  void cleanup() {
    UserInfoCache.resetForTests();
  }

  @Test
  void disabledWhenHostNotConfigured() {
    assertFalse(UserInfoCache.isEnabled());
  }

  @Test
  void allOperationsAreNoOpsWhenDisabled() {
    assertNull(UserInfoCache.getRow("u1", UserInfoCache.FIELD_INFO));
    assertNull(UserInfoCache.getRows("u1", UserInfoCache.FIELD_ROLES));
    assertTrue(UserInfoCache.getRaw("u1", UserInfoCache.FIELD_INFO, UserInfoCache.FIELD_ORG).isEmpty());
    UserInfoCache.put("u1", UserInfoCache.FIELD_INFO, Map.of("id", "u1"));
    UserInfoCache.put("u1", UserInfoCache.FIELD_ROLES, List.of(Map.of("role", "PUBLIC")));
    UserInfoCache.evictFields("u1", UserInfoCache.FIELD_INFO);
    UserInfoCache.evict("u1");
    assertNull(UserInfoCache.getRow("u1", UserInfoCache.FIELD_INFO));
  }
}
