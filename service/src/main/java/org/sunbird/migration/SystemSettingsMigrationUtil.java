package org.sunbird.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.dao.systemsettings.impl.PostgreSQLSystemSettingDaoImpl;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

/**
 * Utility class to migrate system settings data from Cassandra to PostgreSQL This is a one-time
 * migration utility
 *
 * @author System Migration Team
 */
public class SystemSettingsMigrationUtil {

  private static final LoggerUtil logger = new LoggerUtil(SystemSettingsMigrationUtil.class);
  private final CassandraOperation cassandraOperation;
  private final PostgreSQLSystemSettingDaoImpl postgreSQLDao;
  private final ObjectMapper mapper;
  private static final String KEYSPACE_NAME = JsonKey.SUNBIRD;
  private static final String TABLE_NAME = JsonKey.SYSTEM_SETTINGS_DB;

  public SystemSettingsMigrationUtil() {
    this.cassandraOperation = ServiceFactory.getInstance();
    this.postgreSQLDao = new PostgreSQLSystemSettingDaoImpl();
    this.mapper = new ObjectMapper();
  }

  /**
   * Main migration method to copy all system settings from Cassandra to PostgreSQL
   *
   * @param context RequestContext for logging and tracing
   * @return Migration result with statistics
   */
  public MigrationResult migrateSystemSettings(RequestContext context) {
    logger.info(context, "Starting system settings migration from Cassandra to PostgreSQL");

    MigrationResult result = new MigrationResult();

    try {
      // Read all records from Cassandra
      Response cassandraResponse =
          cassandraOperation.getAllRecords(KEYSPACE_NAME, TABLE_NAME, context);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cassandraRecords =
          (List<Map<String, Object>>) cassandraResponse.get(JsonKey.RESPONSE);

      if (cassandraRecords == null || cassandraRecords.isEmpty()) {
        logger.info(context, "No system settings found in Cassandra to migrate");
        return result;
      }

      logger.info(context, "Found " + cassandraRecords.size() + " system settings in Cassandra");
      result.totalRecords = cassandraRecords.size();

      // Migrate each record
      for (Map<String, Object> record : cassandraRecords) {
        try {
          migrateRecord(record, context);
          result.successfulMigrations++;
          logger.debug(context, "Successfully migrated system setting: " + record.get("id"));
        } catch (Exception e) {
          result.failedMigrations++;
          result.errors.put(String.valueOf(record.get("id")), e.getMessage());
          logger.error(context, "Failed to migrate system setting: " + record.get("id"), e);
        }
      }

      logger.info(
          context,
          "Migration completed. Success: "
              + result.successfulMigrations
              + ", Failed: "
              + result.failedMigrations);

    } catch (Exception e) {
      logger.error(context, "Migration failed with error", e);
      result.migrationFailed = true;
      result.errors.put("MIGRATION_ERROR", e.getMessage());
    }

    return result;
  }

  /** Migrate a single record from Cassandra format to PostgreSQL */
  private void migrateRecord(Map<String, Object> cassandraRecord, RequestContext context) {
    // Convert Cassandra record to SystemSetting object
    SystemSetting systemSetting = mapper.convertValue(cassandraRecord, SystemSetting.class);

    // Ensure required fields are present
    if (systemSetting.getId() == null || systemSetting.getId().trim().isEmpty()) {
      throw new RuntimeException("System setting ID cannot be null or empty");
    }

    if (systemSetting.getField() == null || systemSetting.getField().trim().isEmpty()) {
      throw new RuntimeException("System setting field cannot be null or empty");
    }

    // Write to PostgreSQL
    postgreSQLDao.write(systemSetting, context);
  }

  /** Verify migration by comparing record counts */
  public boolean verifyMigration(RequestContext context) {
    try {
      // Get count from Cassandra
      Response cassandraResponse =
          cassandraOperation.getAllRecords(KEYSPACE_NAME, TABLE_NAME, context);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cassandraRecords =
          (List<Map<String, Object>>) cassandraResponse.get(JsonKey.RESPONSE);
      int cassandraCount = cassandraRecords != null ? cassandraRecords.size() : 0;

      // Get count from PostgreSQL
      List<SystemSetting> postgresRecords = postgreSQLDao.readAll(context);
      int postgresCount = postgresRecords != null ? postgresRecords.size() : 0;

      logger.info(
          context,
          "Migration verification - Cassandra: "
              + cassandraCount
              + ", PostgreSQL: "
              + postgresCount);

      return cassandraCount == postgresCount;

    } catch (Exception e) {
      logger.error(context, "Migration verification failed", e);
      return false;
    }
  }

  /** Sample a few records to verify data integrity */
  public boolean verifyDataIntegrity(RequestContext context, int sampleSize) {
    try {
      // Get sample records from Cassandra
      Response cassandraResponse =
          cassandraOperation.getAllRecords(KEYSPACE_NAME, TABLE_NAME, context);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cassandraRecords =
          (List<Map<String, Object>>) cassandraResponse.get(JsonKey.RESPONSE);

      if (cassandraRecords == null || cassandraRecords.isEmpty()) {
        return true; // No data to verify
      }

      int samplesToCheck = Math.min(sampleSize, cassandraRecords.size());

      for (int i = 0; i < samplesToCheck; i++) {
        Map<String, Object> cassandraRecord = cassandraRecords.get(i);
        String id = String.valueOf(cassandraRecord.get("id"));

        // Get corresponding record from PostgreSQL
        SystemSetting postgresRecord = postgreSQLDao.readById(id, context);

        if (postgresRecord == null) {
          logger.error(
              context,
              "Record not found in PostgreSQL: " + id,
              new RuntimeException("Record not found"));
          return false;
        }

        // Compare key fields
        if (!compareRecords(cassandraRecord, postgresRecord)) {
          logger.error(
              context, "Data mismatch for record: " + id, new RuntimeException("Data mismatch"));
          return false;
        }
      }

      logger.info(context, "Data integrity verification passed for " + samplesToCheck + " samples");
      return true;

    } catch (Exception e) {
      logger.error(context, "Data integrity verification failed", e);
      return false;
    }
  }

  private boolean compareRecords(
      Map<String, Object> cassandraRecord, SystemSetting postgresRecord) {
    String cassandraId = String.valueOf(cassandraRecord.get("id"));
    String cassandraField = String.valueOf(cassandraRecord.get("field"));
    String cassandraValue = String.valueOf(cassandraRecord.get("value"));

    return cassandraId.equals(postgresRecord.getId())
        && cassandraField.equals(postgresRecord.getField())
        && cassandraValue.equals(postgresRecord.getValue());
  }

  /** Result class to hold migration statistics */
  public static class MigrationResult {
    public int totalRecords = 0;
    public int successfulMigrations = 0;
    public int failedMigrations = 0;
    public boolean migrationFailed = false;
    public Map<String, String> errors = new HashMap<>();

    public boolean isSuccessful() {
      return !migrationFailed && failedMigrations == 0;
    }

    @Override
    public String toString() {
      return String.format(
          "MigrationResult{totalRecords=%d, successful=%d, failed=%d, errors=%d}",
          totalRecords, successfulMigrations, failedMigrations, errors.size());
    }
  }
}
