package org.sunbird.postgresql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.util.PropertiesCache;

/**
 * PostgreSQL connection manager using HikariCP connection pool
 *
 * @author System Migration Team
 */
public class PostgreSQLConnectionManager {

  private static final LoggerUtil logger = new LoggerUtil(PostgreSQLConnectionManager.class);
  private static PostgreSQLConnectionManager instance;
  private HikariDataSource dataSource;
  private final PropertiesCache propertiesCache = PropertiesCache.getInstance();

  private PostgreSQLConnectionManager() {
    initializeDataSource();
  }

  public static synchronized PostgreSQLConnectionManager getInstance() {
    if (instance == null) {
      instance = new PostgreSQLConnectionManager();
    }
    return instance;
  }

  private void initializeDataSource() {
    try {
      HikariConfig config = new HikariConfig();

      // Read configuration from environment variables or properties
      String host = getProperty("sunbird_pg_host", "localhost");
      String port = getProperty("sunbird_pg_port", "5432");
      String database = getProperty("sunbird_pg_db", "sunbird");
      String username = getProperty("sunbird_pg_user", "postgres");
      String password = getProperty("sunbird_pg_password", "password");

      String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);

      config.setJdbcUrl(jdbcUrl);
      config.setUsername(username);
      config.setPassword(password);
      config.setDriverClassName("org.postgresql.Driver");

      // Connection pool settings
      config.setMaximumPoolSize(Integer.parseInt(getProperty("sunbird_pg_pool_max_size", "10")));
      config.setMinimumIdle(Integer.parseInt(getProperty("sunbird_pg_pool_min_idle", "2")));
      config.setConnectionTimeout(
          Long.parseLong(getProperty("sunbird_pg_connection_timeout", "30000")));
      config.setIdleTimeout(Long.parseLong(getProperty("sunbird_pg_idle_timeout", "600000")));
      config.setMaxLifetime(Long.parseLong(getProperty("sunbird_pg_max_lifetime", "1800000")));

      // Additional PostgreSQL specific settings
      config.addDataSourceProperty("cachePrepStmts", "true");
      config.addDataSourceProperty("prepStmtCacheSize", "250");
      config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
      config.addDataSourceProperty("useServerPrepStmts", "true");
      config.addDataSourceProperty("reWriteBatchedInserts", "true");

      dataSource = new HikariDataSource(config);

      logger.info(null, "PostgreSQL connection pool initialized successfully");

    } catch (Exception e) {
      logger.error(null, "Failed to initialize PostgreSQL connection pool", e);
      throw new RuntimeException("Failed to initialize PostgreSQL connection pool", e);
    }
  }

  private String getProperty(String key, String defaultValue) {
    String value = System.getenv(key);
    if (value == null || value.trim().isEmpty()) {
      try {
        value = propertiesCache.getProperty(key);
      } catch (Exception e) {
        logger.debug(null, "Property not found in cache: " + key);
      }
    }
    return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
  }

  public Connection getConnection() throws SQLException {
    if (dataSource == null) {
      throw new SQLException("DataSource is not initialized");
    }
    return dataSource.getConnection();
  }

  public void close() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
      logger.info(null, "PostgreSQL connection pool closed");
    }
  }

  public boolean isHealthy() {
    try (Connection connection = getConnection()) {
      return connection != null && connection.isValid(5);
    } catch (SQLException e) {
      logger.error(null, "PostgreSQL health check failed", e);
      return false;
    }
  }
}
