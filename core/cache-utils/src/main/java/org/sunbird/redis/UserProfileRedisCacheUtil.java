package org.sunbird.redis;

import org.apache.commons.lang3.StringUtils;
import org.sunbird.logging.LoggerUtil;
import redis.clients.jedis.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Dedicated Redis connection for user-profile data, kept deliberately separate from the generic
 * shared {@link RedisCacheUtil} (which backs org-details, SSO dedup, etc.). User-profile data is
 * read far more widely across services than any other cached entity in this codebase, so it gets
 * its own connection pool and its own config namespace — a noisy neighbour on the generic Redis
 * (or an outage isolated to it) should not be able to affect user-profile reads, and vice versa.
 *
 * <p>Config keys (see {@link ConfigUtil} for resolution order — real OS environment variables
 * win over {@code application.conf}):
 *
 * <ul>
 *   <li>{@code userprofile.redis.host} (default {@code localhost})
 *   <li>{@code userprofile.redis.port} (default {@code 6379})
 *   <li>{@code userprofile.redis.index} (default {@code 0}) — the Redis logical DB index to
 *       select on each connection, matching the {@code redis.dbIndex} pattern already used by
 *       {@link RedisCacheUtil}.
 * </ul>
 *
 * <p>This class only establishes the connection and exposes the same cache-aside primitives as
 * {@link RedisCacheUtil} (get/set/delete) — it intentionally does not yet decide what gets stored
 * under it or who writes to it. That is a separate, follow-up integration step once it's decided
 * which service (cb-ext-userprofile-service's existing basic/extended-profile cache, or
 * sunbird-lms-service's /user/read cache) is the canonical source other services should read from.
 */
public class UserProfileRedisCacheUtil {

    private static final LoggerUtil logger = new LoggerUtil(UserProfileRedisCacheUtil.class);

    // Falls back to the generic cache's sunbird_redis_host/port (the real, already-deployed
    // Redis endpoint) when userprofile.redis.host/port aren't set, so this connects to the
    // actual production Redis by default instead of silently pointing at localhost. Set
    // userprofile.redis.host/port explicitly only if this should move to its own Redis instance.
    private static final String redisHost =
            ConfigUtil.getString("userprofile.redis.host", ConfigUtil.getString("sunbird_redis_host", "localhost"));
    private static final int redisPort =
            ConfigUtil.getInteger("userprofile.redis.port", ConfigUtil.getInteger("sunbird_redis_port", 6379));
    // Explicitly pinned to a DB index distinct from the generic cache's redis.dbIndex (see
    // controller/conf/application.conf), so the two caches stay logically separated even when
    // they share the same physical Redis server by default.
    private static final int index = ConfigUtil.getInteger("userprofile.redis.index", 1);

    private static JedisPool jedisPool = new JedisPool(buildPoolConfig(), redisHost, redisPort);

    private static JedisPoolConfig buildPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(ConfigUtil.getInteger("userprofile.redis.connection.max", 2));
        config.setMaxIdle(ConfigUtil.getInteger("userprofile.redis.connection.idle.max", 2));
        config.setMinIdle(ConfigUtil.getInteger("userprofile.redis.connection.idle.min", 1));
        config.setTestWhileIdle(true);
        config.setMinEvictableIdleTime(
                Duration.ofSeconds(ConfigUtil.getLong("userprofile.redis.connection.minEvictableIdleTimeSeconds", 120L)));
        config.setTimeBetweenEvictionRuns(
                Duration.ofSeconds(ConfigUtil.getLong("userprofile.redis.connection.timeBetweenEvictionRunsSeconds", 300L)));
        config.setBlockWhenExhausted(true);
        return config;
    }

    public static Jedis getConnection() {
        Jedis jedis = jedisPool.getResource();
        if (index > 0) jedis.select(index);
        return jedis;
    }

    public static Jedis getConnection(int dbIndex) {
        Jedis jedis = jedisPool.getResource();
        jedis.select(dbIndex);
        return jedis;
    }

    public static void resetConnection() {
        jedisPool.close();
        jedisPool = new JedisPool(buildPoolConfig(), redisHost, redisPort);
    }

    public static boolean checkConnection() {
        try (Jedis conn = getConnection()) {
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static void set(String key, String data, int ttl) {
        try (Jedis jedis = getConnection()) {
            jedis.del(key);
            jedis.set(key, data);
            if (ttl > 0) jedis.expire(key, ttl);
        } catch (Exception e) {
            logger.error("UserProfileRedisCacheUtil: set failed for key: " + key, e);
            throw e;
        }
    }

    public static String get(String key, Function<String, String> handler, int ttl) {
        try (Jedis jedis = getConnection()) {
            String data = jedis.get(key);
            if ((data == null || data.isEmpty()) && handler != null) {
                data = handler.apply(key);
                if (data != null && !data.isEmpty()) {
                    set(key, data, ttl);
                }
            }
            return data;
        } catch (Exception e) {
            logger.error("UserProfileRedisCacheUtil: get failed for key: " + key, e);
            throw e;
        }
    }

    public static void delete(String... keys) {
        try (Jedis jedis = getConnection()) {
            jedis.del(keys);
        } catch (Exception e) {
            logger.error("UserProfileRedisCacheUtil: delete failed for keys: " + Arrays.toString(keys), e);
            throw e;
        }
    }

    public static void deleteByPattern(String pattern) {
        if (StringUtils.isNotBlank(pattern) && !StringUtils.equalsIgnoreCase(pattern, "*")) {
            try (Jedis jedis = getConnection()) {
                Set<String> keys = jedis.keys(pattern);
                if (keys != null && !keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
            } catch (Exception e) {
                logger.error("UserProfileRedisCacheUtil: delete by pattern failed for pattern: " + pattern, e);
                throw e;
            }
        }
    }
}
