package org.sunbird.dao.user.impl;

import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.common.factory.EsClientFactory;
import org.sunbird.common.inf.ElasticSearchService;
import org.sunbird.dao.user.UserRoleDao;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.redis.UserInfoCache;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.util.ProjectUtil;
import scala.concurrent.Future;

public final class UserRoleDaoImpl implements UserRoleDao {

  private final LoggerUtil logger = new LoggerUtil(UserRoleDaoImpl.class);
  private static final String TABLE_NAME = JsonKey.USER_ROLES;
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private final ElasticSearchService esService = EsClientFactory.getInstance(JsonKey.REST);

  private static UserRoleDaoImpl instance;

  private UserRoleDaoImpl() {}

  public static UserRoleDao getInstance() {
    if (instance == null) {
      instance = new UserRoleDaoImpl();
    }
    return instance;
  }

  @Override
  public Response assignUserRole(List<Map<String, Object>> userRoleMap, RequestContext context) {
    Response response =
        cassandraOperation.batchInsert(JsonKey.SUNBIRD, TABLE_NAME, userRoleMap, context);
    evictRolesCache(userRoleMap);
    return response;
  }

  private void evictRolesCache(List<? extends Map<String, ?>> userRoleMap) {
    if (userRoleMap == null) {
      return;
    }
    userRoleMap.stream()
        .map(m -> (String) m.get(JsonKey.USER_ID))
        .filter(StringUtils::isNotBlank)
        .distinct()
        .forEach(userId -> UserInfoCache.evictFields(userId, UserInfoCache.FIELD_ROLES));
  }

  @Override
  public Response updateRoleScope(List<Map<String, Object>> userRoleMap, RequestContext context) {
    Response result = null;
    for (Map<String, Object> dataMap : userRoleMap) {
      Map<String, Object> compositeKey = new LinkedHashMap<>(2);
      compositeKey.put(JsonKey.USER_ID, dataMap.remove(JsonKey.USER_ID));
      compositeKey.put(JsonKey.ROLE, dataMap.remove(JsonKey.ROLE));
      result =
          cassandraOperation.updateRecord(
              JsonKey.SUNBIRD, TABLE_NAME, dataMap, compositeKey, context);
      UserInfoCache.evictFields(
          (String) compositeKey.get(JsonKey.USER_ID), UserInfoCache.FIELD_ROLES);
    }
    return result;
  }

  @Override
  public void deleteUserRole(List<Map<String, String>> userRoleMap, RequestContext context) {
    for (Map<String, String> dataMap : userRoleMap) {
      cassandraOperation.deleteRecord(JsonKey.SUNBIRD, TABLE_NAME, dataMap, context);
    }
    evictRolesCache(userRoleMap);
  }

  @Override
  public List<Map<String, Object>> getUserRoles(
      String userId, String role, RequestContext context) {
    boolean wholeList = StringUtils.isEmpty(role);
    if (wholeList) {
      // Read-through on the user's full role list (user:{id} / roles).
      List<Map<String, Object>> cached = UserInfoCache.getRows(userId, UserInfoCache.FIELD_ROLES);
      if (cached != null) {
        return cached;
      }
    }
    Map compositeKeyMap = new HashMap<String, Object>();
    compositeKeyMap.put(JsonKey.USER_ID, userId);
    if (!wholeList) {
      compositeKeyMap.put(JsonKey.ROLE, role);
    }
    Response existingRecord =
        cassandraOperation.getRecordById(JsonKey.SUNBIRD, TABLE_NAME, compositeKeyMap, context);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) existingRecord.get(JsonKey.RESPONSE);
    if (wholeList && responseList != null) {
      UserInfoCache.put(userId, UserInfoCache.FIELD_ROLES, responseList);
    }
    return responseList;
  }

  @Override
  public boolean updateUserRoleToES(
      String identifier, Map<String, Object> data, RequestContext context) {
    Future<Boolean> responseF =
        esService.update(ProjectUtil.EsType.user.getTypeName(), identifier, data, context);
    if ((boolean) ElasticSearchHelper.getResponseFromFuture(responseF)) {
      return true;
    }
    logger.info(
        context,
        "UserRoleDaoImpl:updateUserRoleToES:unable to save the user role data to ES with identifier "
            + identifier);
    return false;
  }
}
