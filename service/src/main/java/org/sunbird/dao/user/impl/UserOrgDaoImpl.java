package org.sunbird.dao.user.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.dao.user.UserOrgDao;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.user.UserOrg;
import org.sunbird.redis.UserInfoCache;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;

public final class UserOrgDaoImpl implements UserOrgDao {

  private static final String TABLE_NAME = JsonKey.USER_ORG;
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private final ObjectMapper mapper = new ObjectMapper();

  private static UserOrgDaoImpl instance;

  private UserOrgDaoImpl() {}

  public static UserOrgDao getInstance() {
    if (instance == null) {
      // To make thread safe
      synchronized (UserOrgDaoImpl.class) {
        // check again as multiple threads
        // can reach above step
        if (instance == null) instance = new UserOrgDaoImpl();
      }
    }
    return instance;
  }

  @Override
  public Response updateUserOrg(UserOrg userOrg, RequestContext context) {
    Map<String, Object> compositeKey = new LinkedHashMap<>(2);
    Map<String, Object> request = mapper.convertValue(userOrg, Map.class);
    compositeKey.put(JsonKey.USER_ID, request.remove(JsonKey.USER_ID));
    compositeKey.put(JsonKey.ORGANISATION_ID, request.remove(JsonKey.ORGANISATION_ID));
    Response response =
        cassandraOperation.updateRecord(JsonKey.SUNBIRD, TABLE_NAME, request, compositeKey, context);
    UserInfoCache.evictFields((String) compositeKey.get(JsonKey.USER_ID), UserInfoCache.FIELD_ORG);
    return response;
  }

  @Override
  public Response createUserOrg(UserOrg userOrg, RequestContext context) {
    Response response =
        cassandraOperation.insertRecord(
            JsonKey.SUNBIRD, TABLE_NAME, mapper.convertValue(userOrg, Map.class), context);
    UserInfoCache.evictFields(userOrg.getUserId(), UserInfoCache.FIELD_ORG);
    return response;
  }

  @Override
  public Response getUserOrgListByUserId(String userId, RequestContext context) {
    Map<String, Object> compositeKey = new LinkedHashMap<>(2);
    compositeKey.put(JsonKey.USER_ID, userId);
    return cassandraOperation.getRecordById(JsonKey.SUNBIRD, TABLE_NAME, compositeKey, context);
  }

  @Override
  public Response getUserOrgDetails(String userId, String organisationId, RequestContext context) {
    // The user:{id}/org field holds the single mapping row for the user's rootOrgId. It is only
    // used when the caller asks for one specific org AND the cached row is for that org; any other
    // request goes to Cassandra unchanged.
    boolean singleOrg = StringUtils.isNotEmpty(organisationId);
    if (singleOrg) {
      Map<String, Object> cached = UserInfoCache.getRow(userId, UserInfoCache.FIELD_ORG);
      if (cached != null && organisationId.equals(cached.get(JsonKey.ORGANISATION_ID))) {
        Response response = new Response();
        response.put(JsonKey.RESPONSE, new ArrayList<>(Collections.singletonList(cached)));
        return response;
      }
    }
    Map<String, Object> searchMap = new LinkedHashMap<>(2);
    searchMap.put(JsonKey.USER_ID, userId);
    if (singleOrg) {
      searchMap.put(JsonKey.ORGANISATION_ID, organisationId);
    }
    Response response =
        cassandraOperation.getRecordsByCompositeKey(
            JsonKey.SUNBIRD, JsonKey.USER_ORG, searchMap, context);
    if (singleOrg) {
      List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (rows != null && rows.size() == 1) {
        UserInfoCache.put(userId, UserInfoCache.FIELD_ORG, rows.get(0));
      }
    }
    return response;
  }

  @Override
  public Response insertRecord(Map reqMap, RequestContext context) {
    Response response =
        cassandraOperation.insertRecord(JsonKey.SUNBIRD, JsonKey.USER_ORG, reqMap, context);
    UserInfoCache.evictFields((String) reqMap.get(JsonKey.USER_ID), UserInfoCache.FIELD_ORG);
    return response;
  }

  public void deleteUserOrgMapping(List<Map<String, Object>> userOrgList, RequestContext context) {
    for (Map<String, Object> userOrg : userOrgList) {
      Map<String, String> compositeKey = new LinkedHashMap<>(2);
      compositeKey.put(JsonKey.USER_ID, (String) userOrg.get(JsonKey.USER_ID));
      compositeKey.put(JsonKey.ORGANISATION_ID, (String) userOrg.get(JsonKey.ORGANISATION_ID));
      cassandraOperation.deleteRecord(JsonKey.SUNBIRD, JsonKey.USER_ORG, compositeKey, context);
      UserInfoCache.evictFields(compositeKey.get(JsonKey.USER_ID), UserInfoCache.FIELD_ORG);
    }
  }
}
