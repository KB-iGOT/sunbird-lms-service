package org.sunbird.redis;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.List;

public class ConfigUtil {

    private static final Config defaultConf = ConfigFactory.load();
    private static final Config envConf = ConfigFactory.systemEnvironment();
    private static final Config config = envConf.withFallback(defaultConf);

    public static String getString(String key, String defaultValue) {
        return config.hasPath(key) ? config.getString(key) : defaultValue;
    }

    public static Integer getInteger(String key, Integer defaultValue) {
        return config.hasPath(key) ? config.getInt(key) : defaultValue;
    }

    public static Boolean getBoolean(String key, Boolean defaultValue) {
        return config.hasPath(key) ? config.getBoolean(key) : defaultValue;
    }

    public static List<String> getStringList(String key, List<String> defaultValue) {
        return config.hasPath(key) ? config.getStringList(key) : defaultValue;
    }

    public static Long getLong(String key, Long defaultValue) {
        return config.hasPath(key) ? config.getLong(key) : defaultValue;
    }

    public static Double getDouble(String key, Double defaultValue) {
        return config.hasPath(key) ? config.getDouble(key) : defaultValue;
    }
}
