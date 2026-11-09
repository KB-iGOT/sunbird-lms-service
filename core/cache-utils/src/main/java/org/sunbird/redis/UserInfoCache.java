package org.sunbird.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import redis.clients.jedis.Pipeline;

/**
 * One Redis hash per user, key {@code user:{userId}}, holding the raw Cassandra rows the user read
 * APIs need, one field per source table:
 *
 * <pre>
 *   info   -> sunbird.user row                       (evict on user update)
 *   roles  -> sunbird.user_roles rows (list)         (evict on role assign/update/delete)
 *   org    -> sunbird.user_organisation row for the user's rootOrgId   (evict on user_org writes)
 *   login  -> sunbird.user_login row                 (evict on login upsert)
 * </pre>
 *
 * Values are the rows exactly as {@code CassandraOperation} returns them (before any enrichment),
 * so a cache hit is indistinguishable from a Cassandra read to the calling code. A single hash per
 * user keeps the keyspace to one entry per user and lets a whole user be fetched with one HMGET.
 * TTL is per key (Redis has no per-field TTL before 7.4) and is refreshed on every field write.
 *
 * <p>Config via {@link ProjectUtil#getConfigValue} ({@code user.profile.redis.*}); deliberately
 * separate from {@code org.info.redis.*}. Disabled when the host is blank; every failure is a miss.
 */
public final class UserInfoCache {

  public static final String KEY_PREFIX = "user:";
  public static final String FIELD_INFO = "info";
  public static final String FIELD_ROLES = "roles";
  public static final String FIELD_ORG = "org";
  public static final String FIELD_LOGIN = "login";

  public static final String HOST = "user.profile.redis.host";
  public static final String PORT = "user.profile.redis.port";
  public static final String INDEX = "user.profile.redis.index";
  public static final String PASSWORD_ENABLED = "user.profile.redis.password.enabled";
  public static final String USERNAME = "user.profile.redis.username";
  public static final String PASSWORD = "user.profile.redis.password";
  public static final String TTL = "user.profile.redis.ttl";

  private static final int DEFAULT_PORT = 6379;
  private static final int DEFAULT_INDEX = 0;
  private static final int DEFAULT_TTL_SECONDS = 14400;
  private static final int CONNECTION_TIMEOUT_MS = 2000;
  private static final int POOL_MAX_TOTAL = 128;
  private static final int POOL_MAX_IDLE = 32;
  private static final int POOL_MIN_IDLE = 8;

  private static final LoggerUtil logger = new LoggerUtil(UserInfoCache.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
      new TypeReference<>() {};

  private static volatile JedisPool pool;
  private static volatile boolean initialised = false;
  private static volatile boolean enabled = false;
  private static volatile int ttlSeconds = DEFAULT_TTL_SECONDS;

  private UserInfoCache() {}

  public static boolean isEnabled() {
    init();
    return enabled;
  }

  /** @return the row stored under {@code field}, or {@code null} on miss / disabled / error. */
  public static Map<String, Object> getRow(String userId, String field) {
    String json = getRaw(userId, field);
    if (json == null) {
      return null;
    }
    try {
      return mapper.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      logger.error("UserInfoCache: bad row json for " + key(userId) + "/" + field, e);
      return null;
    }
  }

  /** @return the row list stored under {@code field}, or {@code null} on miss / disabled / error. */
  public static List<Map<String, Object>> getRows(String userId, String field) {
    String json = getRaw(userId, field);
    if (json == null) {
      return null;
    }
    try {
      return mapper.readValue(json, LIST_TYPE);
    } catch (Exception e) {
      logger.error("UserInfoCache: bad list json for " + key(userId) + "/" + field, e);
      return null;
    }
  }

  /** HMGET of several fields in one round trip; absent fields are simply missing from the map. */
  public static Map<String, String> getRaw(String userId, String... fields) {
    if (!isEnabled() || StringUtils.isBlank(userId) || fields == null || fields.length == 0) {
      return Collections.emptyMap();
    }
    try (Jedis jedis = pool.getResource()) {
      List<String> values = jedis.hmget(key(userId), fields);
      Map<String, String> result = new LinkedHashMap<>();
      for (int i = 0; i < fields.length && i < values.size(); i++) {
        if (values.get(i) != null) {
          result.put(fields[i], values.get(i));
        }
      }
      return result;
    } catch (Exception e) {
      logger.error("UserInfoCache: hmget failed for " + key(userId) + ": " + e.getMessage(), e);
      return Collections.emptyMap();
    }
  }

  private static String getRaw(String userId, String field) {
    return getRaw(userId, new String[] {field}).get(field);
  }

  /** Stores {@code value} (a row or row list) under {@code field} and refreshes the key TTL. */
  public static void put(String userId, String field, Object value) {
    if (!isEnabled() || StringUtils.isBlank(userId) || StringUtils.isBlank(field) || value == null) {
      return;
    }
    try (Jedis jedis = pool.getResource()) {
      String json = mapper.writeValueAsString(value);
      Pipeline p = jedis.pipelined();
      p.hset(key(userId), field, json);
      p.expire(key(userId), ttlSeconds);
      p.sync();
    } catch (Exception e) {
      logger.error("UserInfoCache: hset failed for " + key(userId) + "/" + field + ": " + e.getMessage(), e);
    }
  }

  /** Drops the given fields only (HDEL); other fields of the user stay warm. */
  public static void evictFields(String userId, String... fields) {
    if (!isEnabled() || StringUtils.isBlank(userId) || fields == null || fields.length == 0) {
      return;
    }
    try (Jedis jedis = pool.getResource()) {
      jedis.hdel(key(userId), fields);
    } catch (Exception e) {
      logger.error("UserInfoCache: hdel failed for " + key(userId) + ": " + e.getMessage(), e);
    }
  }

  /** Drops the whole user hash (use on merge / delete). */
  public static void evict(String userId) {
    if (!isEnabled() || StringUtils.isBlank(userId)) {
      return;
    }
    try (Jedis jedis = pool.getResource()) {
      jedis.del(key(userId));
    } catch (Exception e) {
      logger.error("UserInfoCache: del failed for " + key(userId) + ": " + e.getMessage(), e);
    }
  }

  private static String key(String userId) {
    return KEY_PREFIX + userId;
  }

  private static void init() {
    if (initialised) {
      return;
    }
    synchronized (UserInfoCache.class) {
      if (initialised) {
        return;
      }
      try {
        String host = ProjectUtil.getConfigValue(HOST);
        if (StringUtils.isBlank(host)) {
          logger.info("UserInfoCache: " + HOST + " not set, user cache disabled");
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
            "UserInfoCache: enabled host=" + host.trim() + " port=" + port + " index=" + index
                + " ttl=" + ttlSeconds + "s auth=" + passwordEnabled);
      } catch (Exception e) {
        logger.error("UserInfoCache: initialisation failed, user cache disabled: " + e.getMessage(), e);
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
      logger.error("UserInfoCache: invalid integer for " + key + "='" + value + "', using " + defaultValue, e);
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
    synchronized (UserInfoCache.class) {
      if (pool != null) {
        pool.close();
      }
      pool = null;
      initialised = false;
      enabled = false;
      ttlSeconds = DEFAULT_TTL_SECONDS;
    }
  }
}
