package org.sunbird.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.util.ProjectUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Read-through cache for raw {@code sunbird.organisation} rows, keyed {@code org:{orgId}}.
 *
 * <p>Configured via {@link ProjectUtil#getConfigValue} (env var first, then
 * externalresource.properties) using the {@code org_info_redis_*} keys. These are deliberately
 * separate from {@code user_profile_redis_*} so the org cache can be moved to its own Redis server
 * without a code change, even though both point to the same server today.
 *
 * <p>Disabled (every call is a no-op / miss) when {@code org_info_redis_host} is blank, so unit
 * tests and environments without the config behave exactly as before. Every Redis failure is
 * logged and treated as a miss; callers must never see an exception from here.
 */
public final class OrgInfoCache {

  public static final String KEY_PREFIX = "org:";

  public static final String HOST = "org_info_redis_host";
  public static final String PORT = "org_info_redis_port";
  public static final String INDEX = "org_info_redis_index";
  public static final String PASSWORD_ENABLED = "org_info_redis_password_enabled";
  public static final String USERNAME = "org_info_redis_username";
  public static final String PASSWORD = "org_info_redis_password";
  public static final String TTL = "org_info_redis_ttl";

  private static final int DEFAULT_PORT = 6379;
  private static final int DEFAULT_INDEX = 0;
  private static final int DEFAULT_TTL_SECONDS = 86400;
  private static final int CONNECTION_TIMEOUT_MS = 2000;
  private static final int POOL_MAX_TOTAL = 64;
  private static final int POOL_MAX_IDLE = 16;
  private static final int POOL_MIN_IDLE = 4;

  private static final LoggerUtil logger = new LoggerUtil(OrgInfoCache.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static volatile JedisPool pool;
  private static volatile boolean initialised = false;
  private static volatile boolean enabled = false;
  private static volatile int ttlSeconds = DEFAULT_TTL_SECONDS;
  private static volatile boolean wrongTypeReported = false;

  private OrgInfoCache() {}

  public static boolean isEnabled() {
    init();
    return enabled;
  }

  /** @return the raw org row, or {@code null} on miss / disabled / error. */
  public static Map<String, Object> get(String orgId) {
    if (!isEnabled() || StringUtils.isBlank(orgId)) {
      return null;
    }
    try (Jedis jedis = pool.getResource()) {
      String json = jedis.get(key(orgId));
      return json == null ? null : mapper.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      logFailure("get", orgId, e);
      return null;
    }
  }

  /** @return orgId -> raw row for the ids found; ids not in cache are simply absent. */
  public static Map<String, Map<String, Object>> getAll(List<String> orgIds) {
    if (!isEnabled() || orgIds == null || orgIds.isEmpty()) {
      return Collections.emptyMap();
    }
    String[] keys = orgIds.stream().map(OrgInfoCache::key).toArray(String[]::new);
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    try (Jedis jedis = pool.getResource()) {
      List<String> values = jedis.mget(keys);
      for (int i = 0; i < orgIds.size() && i < values.size(); i++) {
        String json = values.get(i);
        if (json != null) {
          result.put(orgIds.get(i), mapper.readValue(json, MAP_TYPE));
        }
      }
    } catch (Exception e) {
      logFailure("mget", orgIds.get(0), e);
      return Collections.emptyMap();
    }
    return result;
  }

  /** Stores the raw row exactly as Cassandra returned it (before any enrichment). */
  public static void put(String orgId, Map<String, Object> rawOrgRow) {
    if (!isEnabled() || StringUtils.isBlank(orgId) || rawOrgRow == null) {
      return;
    }
    try (Jedis jedis = pool.getResource()) {
      jedis.setex(key(orgId), ttlSeconds, mapper.writeValueAsString(rawOrgRow));
    } catch (Exception e) {
      logFailure("setex", orgId, e);
    }
  }

  /** Call after every successful write to the organisation table. */
  public static void evict(String orgId) {
    if (!isEnabled() || StringUtils.isBlank(orgId)) {
      return;
    }
    try (Jedis jedis = pool.getResource()) {
      jedis.del(key(orgId));
    } catch (Exception e) {
      logFailure("del", orgId, e);
    }
  }

  private static String key(String orgId) {
    return KEY_PREFIX + orgId;
  }

  /** WRONGTYPE = another writer owns this key name with a different type; report once, not per call. */
  private static void logFailure(String op, String orgId, Exception e) {
    String msg = e.getMessage();
    if (msg != null && msg.startsWith("WRONGTYPE")) {
      if (!wrongTypeReported) {
        wrongTypeReported = true;
        logger.error(
            "OrgInfoCache: key namespace '" + KEY_PREFIX + "*' clashes with a non-string key ("
                + key(orgId) + "); treating as cache miss. Further WRONGTYPE errors suppressed.", e);
      }
      return;
    }
    logger.error("OrgInfoCache: " + op + " failed for " + key(orgId) + ": " + msg, e);
  }

  private static void init() {
    if (initialised) {
      return;
    }
    synchronized (OrgInfoCache.class) {
      if (initialised) {
        return;
      }
      try {
        String host = ProjectUtil.getConfigValue(HOST);
        if (StringUtils.isBlank(host)) {
          logger.info("OrgInfoCache: " + HOST + " not set, org cache disabled");
          enabled = false;
          return;
        }
        int port = intValue(PORT, DEFAULT_PORT);
        int index = intValue(INDEX, DEFAULT_INDEX);
        ttlSeconds = intValue(TTL, DEFAULT_TTL_SECONDS);
        boolean passwordEnabled =
            Boolean.parseBoolean(StringUtils.trimToEmpty(ProjectUtil.getConfigValue(PASSWORD_ENABLED)));
        JedisPoolConfig config = buildPoolConfig();
        if (passwordEnabled) {
          String username = StringUtils.trimToNull(ProjectUtil.getConfigValue(USERNAME));
          String password = ProjectUtil.getConfigValue(PASSWORD);
          pool =
              username == null
                  ? new JedisPool(config, host.trim(), port, CONNECTION_TIMEOUT_MS, password, index)
                  : new JedisPool(
                      config, host.trim(), port, CONNECTION_TIMEOUT_MS, username, password, index);
        } else {
          pool = new JedisPool(config, host.trim(), port, CONNECTION_TIMEOUT_MS, null, index);
        }
        enabled = true;
        logger.info(
            "OrgInfoCache: enabled host=" + host.trim() + " port=" + port + " index=" + index
                + " ttl=" + ttlSeconds + "s auth=" + passwordEnabled);
      } catch (Exception e) {
        logger.error("OrgInfoCache: initialisation failed, org cache disabled: " + e.getMessage(), e);
        enabled = false;
      } finally {
        initialised = true;
      }
    }
  }

  private static int intValue(String key, int defaultValue) {
    String value = ProjectUtil.getConfigValue(key);
    if (StringUtils.isBlank(value)) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      logger.error("OrgInfoCache: invalid integer for " + key + "='" + value + "', using " + defaultValue, e);
      return defaultValue;
    }
  }

  private static JedisPoolConfig buildPoolConfig() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(POOL_MAX_TOTAL);
    config.setMaxIdle(POOL_MAX_IDLE);
    config.setMinIdle(POOL_MIN_IDLE);
    config.setTestWhileIdle(true);
    config.setMinEvictableIdleTime(Duration.ofSeconds(120));
    config.setTimeBetweenEvictionRuns(Duration.ofSeconds(300));
    config.setBlockWhenExhausted(true);
    config.setMaxWait(Duration.ofMillis(CONNECTION_TIMEOUT_MS));
    return config;
  }

  /** Test hook: forget configuration so the next call re-reads it. */
  static void resetForTests() {
    synchronized (OrgInfoCache.class) {
      if (pool != null) {
        pool.close();
      }
      pool = null;
      initialised = false;
      enabled = false;
      ttlSeconds = DEFAULT_TTL_SECONDS;
      wrongTypeReported = false;
    }
  }
}
