package org.sunbird.dao.systemsettings.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.dao.systemsettings.SystemSettingDao;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.util.PropertiesCache;

/**
 * System Setting DAO implementation with configurable backend Supports both Cassandra and
 * PostgreSQL based on configuration Set system property 'sunbird_system_settings_db_type' to
 * 'postgresql' to use PostgreSQL Default is Cassandra for backward compatibility
 */
public class SystemSettingDaoImpl implements SystemSettingDao {

  private final SystemSettingDao actualDao;

  public SystemSettingDaoImpl() {
    // Check configuration to decide which implementation to use
    String dbType = getDbType();
    if ("postgresql".equalsIgnoreCase(dbType)) {
      this.actualDao = new PostgreSQLSystemSettingDaoImpl();
    } else {
      // Default to Cassandra for backward compatibility
      this.actualDao = new CassandraSystemSettingDaoImpl();
    }
  }

  private String getDbType() {
    // Check system property first
    String dbType = System.getProperty("sunbird_system_settings_db_type");
    if (dbType != null && !dbType.trim().isEmpty()) {
      return dbType.trim();
    }

    // Check environment variable
    dbType = System.getenv("SUNBIRD_SYSTEM_SETTINGS_DB_TYPE");
    if (dbType != null && !dbType.trim().isEmpty()) {
      return dbType.trim();
    }

    // Check properties cache
    try {
      PropertiesCache propertiesCache = PropertiesCache.getInstance();
      dbType = propertiesCache.getProperty("sunbird_system_settings_db_type");
      if (dbType != null && !dbType.trim().isEmpty()) {
        return dbType.trim();
      }
    } catch (Exception e) {
      // Ignore and use default
    }

    // Default to cassandra
    return "cassandra";
  }

  @Override
  public Response write(SystemSetting systemSetting, RequestContext context) {
    return actualDao.write(systemSetting, context);
  }

  @Override
  public SystemSetting readById(String id, RequestContext context) {
    return actualDao.readById(id, context);
  }

  @Override
  public SystemSetting readByField(String field, RequestContext context) {
    return actualDao.readByField(field, context);
  }

  @Override
  public List<SystemSetting> readAll(RequestContext context) {
    return actualDao.readAll(context);
  }

  /** Legacy Cassandra implementation */
  private static class CassandraSystemSettingDaoImpl implements SystemSettingDao {

    private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String KEYSPACE_NAME = JsonKey.SUNBIRD;
    private static final String TABLE_NAME = JsonKey.SYSTEM_SETTINGS_DB;

    @Override
    public Response write(SystemSetting systemSetting, RequestContext context) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = mapper.convertValue(systemSetting, Map.class);
      Response response = cassandraOperation.upsertRecord(KEYSPACE_NAME, TABLE_NAME, map, context);
      response.put(JsonKey.ID, map.get(JsonKey.ID));
      return response;
    }

    @Override
    public SystemSetting readById(String id, RequestContext context) {
      Response response = cassandraOperation.getRecordById(KEYSPACE_NAME, TABLE_NAME, id, context);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (CollectionUtils.isEmpty(list)) {
        return null;
      }
      return getSystemSetting(list);
    }

    @Override
    public SystemSetting readByField(String field, RequestContext context) {
      return readById(field, context);
    }

    @Override
    public List<SystemSetting> readAll(RequestContext context) {
      Response response = cassandraOperation.getAllRecords(KEYSPACE_NAME, TABLE_NAME, context);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> list = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      List<SystemSetting> systemSettings = new ArrayList<>();
      list.forEach(
          map -> {
            SystemSetting systemSetting = mapper.convertValue(map, SystemSetting.class);
            systemSettings.add(systemSetting);
          });
      return systemSettings;
    }

    private SystemSetting getSystemSetting(List<Map<String, Object>> list) {
      return mapper.convertValue(list.get(0), SystemSetting.class);
    }
  }
}
