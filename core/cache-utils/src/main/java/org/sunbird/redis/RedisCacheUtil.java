package org.sunbird.redis;

import org.apache.commons.lang3.StringUtils;
import org.sunbird.logging.LoggerUtil;
import redis.clients.jedis.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

public class RedisCacheUtil {

    private static final LoggerUtil logger = new LoggerUtil(RedisCacheUtil.class);

    private static final String redisHost = ConfigUtil.getString("sunbird_redis_host", "localhost");
    private static final int redisPort = ConfigUtil.getInteger("sunbird_redis_port", 6379);
    private static final int index = ConfigUtil.getInteger("redis.dbIndex", 0);

    private static JedisPool jedisPool = new JedisPool(buildPoolConfig(), redisHost, redisPort);

    private static JedisPoolConfig buildPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(ConfigUtil.getInteger("redis.connection.max", 2));
        config.setMaxIdle(ConfigUtil.getInteger("redis.connection.idle.max", 2));
        config.setMinIdle(ConfigUtil.getInteger("redis.connection.idle.min", 1));
        config.setTestWhileIdle(true);
        config.setMinEvictableIdleTime(Duration.ofSeconds(ConfigUtil.getLong("redis.connection.minEvictableIdleTimeSeconds", 120L)));
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(ConfigUtil.getLong("redis.connection.timeBetweenEvictionRunsSeconds", 300L)));
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
        try (Jedis conn = getConnection(2)) {
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
            logger.error("Redis set failed for key: " + key, e);
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
            logger.error("Redis get failed for key: " + key, e);
            throw e;
        }
    }

    public static CompletableFuture<String> getAsync(String key, Function<String, CompletableFuture<String>> asyncHandler, int ttl) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = getConnection()) {
                String data = jedis.get(key);
                if ((data == null || data.isEmpty()) && asyncHandler != null) {
                    return asyncHandler.apply(key).thenApply(value -> {
                        if (value != null && !value.isEmpty()) {
                            set(key, value, ttl);
                        }
                        return value;
                    }).join();
                } else {
                    return data;
                }
            } catch (Exception e) {
                logger.error("Redis async get failed for key: " + key, e);
                throw new CompletionException(e);
            }
        });
    }

    public static double incrementAndGet(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.incrByFloat(key, 1.0);
        } catch (Exception e) {
            logger.error("Redis increment failed for key: " + key, e);
            throw e;
        }
    }

    public static void saveList(String key, List<String> data, int ttl, boolean isPartialUpdate) {
        try (Jedis jedis = getConnection()) {
            if (!isPartialUpdate) jedis.del(key);
            for (String value : data) {
                jedis.sadd(key, value);
            }
            if (ttl > 0 && !isPartialUpdate) jedis.expire(key, ttl);
        } catch (Exception e) {
            logger.error("Redis save list failed for key: " + key, e);
            throw e;
        }
    }

    public static void addToList(String key, List<String> data) {
        saveList(key, data, 0, true);
    }

    public static List<String> getList(String key, Function<String, List<String>> handler, int ttl, int dbIndex) {
        try (Jedis jedis = getConnection(dbIndex)) {
            Set<String> redisSet = jedis.smembers(key);
            List<String> data = new ArrayList<>(redisSet);
            if ((data == null || data.isEmpty()) && handler != null) {
                data = handler.apply(key);
                if (data != null && !data.isEmpty()) {
                    saveList(key, data, ttl, false);
                }
            }
            return data;
        } catch (Exception e) {
            logger.error("Redis get list failed for key: " + key, e);
            throw e;
        }
    }

    public static void removeFromList(String key, List<String> data) {
        try (Jedis jedis = getConnection()) {
            for (String value : data) {
                jedis.srem(key, value);
            }
        } catch (Exception e) {
            logger.error("Redis remove from list failed for key: " + key, e);
            throw e;
        }
    }

    public static void delete(String... keys) {
        try (Jedis jedis = getConnection()) {
            jedis.del(keys);
        } catch (Exception e) {
            logger.error("Redis delete failed for keys: " + Arrays.toString(keys), e);
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
                logger.error("Redis delete by pattern failed for pattern: " + pattern, e);
                throw e;
            }
        }
    }

    public static String getUsingIndex(String key, Function<String, String> handler, int ttl, int dbIndex) {
        try (Jedis jedis = getConnection(dbIndex)) {
            String data = jedis.get(key);
            if ((data == null || data.isEmpty()) && handler != null) {
                data = handler.apply(key);
                if (data != null && !data.isEmpty()) {
                    set(key, data, ttl);
                }
            }
            return data;
        } catch (Exception e) {
            logger.error("Redis get using index failed for key: " + key, e);
            throw e;
        }
    }
}