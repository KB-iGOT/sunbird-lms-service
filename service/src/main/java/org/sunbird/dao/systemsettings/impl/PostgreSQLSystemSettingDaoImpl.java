package org.sunbird.dao.systemsettings.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.sunbird.dao.systemsettings.SystemSettingDao;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.postgresql.PostgreSQLOperation;
import org.sunbird.postgresql.impl.PostgreSQLOperationImpl;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

/**
 * PostgreSQL implementation of SystemSettingDao Replaces Cassandra implementation for system
 * settings storage
 *
 * @author System Migration Team
 */
public class PostgreSQLSystemSettingDaoImpl implements SystemSettingDao {

  private final PostgreSQLOperation postgreSQLOperation = new PostgreSQLOperationImpl();
  private final ObjectMapper mapper = new ObjectMapper();
  private static final String SCHEMA_NAME = "sunbird";
  private static final String TABLE_NAME = "system_settings";

  @Override
  public Response write(SystemSetting systemSetting, RequestContext context) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = mapper.convertValue(systemSetting, Map.class);
    Response response = postgreSQLOperation.upsertRecord(SCHEMA_NAME, TABLE_NAME, map, context);
    response.put(JsonKey.ID, map.get(JsonKey.ID));
    return response;
  }

  @Override
  public SystemSetting readById(String id, RequestContext context) {
    Response response = postgreSQLOperation.getRecordById(SCHEMA_NAME, TABLE_NAME, id, context);
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
    Response response = postgreSQLOperation.getAllRecords(SCHEMA_NAME, TABLE_NAME, context);
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
