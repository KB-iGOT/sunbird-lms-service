package org.sunbird.dao.user.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.common.factory.EsClientFactory;
import org.sunbird.common.inf.ElasticSearchService;
import org.sunbird.dao.user.UserDao;
import org.sunbird.dto.SearchDTO;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.model.user.User;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.util.ProjectUtil;
import scala.concurrent.Future;

/**
 * Implementation class of UserDao interface.
 *
 * @author Amit Kumar
 */
public class UserDaoImpl implements UserDao {

  private final LoggerUtil logger = new LoggerUtil(UserDaoImpl.class);
  private static final String TABLE_NAME = JsonKey.USER;
  private static final String KEY_SPACE_NAME = JsonKey.SUNBIRD;
  private final ElasticSearchService esService = EsClientFactory.getInstance(JsonKey.REST);
  private final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private final ObjectMapper mapper = new ObjectMapper();
  private static UserDao userDao = null;

  public static UserDao getInstance() {
    if (userDao == null) {
      userDao = new UserDaoImpl();
    }
    return userDao;
  }

  @Override
  public Response createUser(Map<String, Object> user, RequestContext context) {
    return cassandraOperation.insertRecord(KEY_SPACE_NAME, TABLE_NAME, user, context);
  }

  @Override
  public Response updateUser(User user, RequestContext context) {
    Map<String, Object> map = mapper.convertValue(user, Map.class);
    return cassandraOperation.updateRecord(KEY_SPACE_NAME, TABLE_NAME, map, context);
  }

  @Override
  public Response updateUser(Map<String, Object> userMap, RequestContext context) {
    return cassandraOperation.updateRecord(KEY_SPACE_NAME, TABLE_NAME, userMap, context);
  }

  @Override
  public User getUserById(String userId, RequestContext context) {
    Map<String, Object> user = getUserDetailsById(userId, context);
    if (MapUtils.isNotEmpty(user)) {
      return mapper.convertValue(user, User.class);
    }
    return null;
  }

  @Override
  public Map<String, Object> getUserDetailsById(String userId, RequestContext context) {
    Response response =
        cassandraOperation.getRecordById(KEY_SPACE_NAME, TABLE_NAME, userId, context);
    List<Map<String, Object>> responseList =
        (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
    if (CollectionUtils.isNotEmpty(responseList)) {
      Map<String, Object> userDetail = responseList.get(0);
      // Enrich user details with last_login information
      Map<String, Object> loginInfo = getLastLoginInfoById(userId, context);
      if (MapUtils.isNotEmpty(loginInfo)) {
        userDetail.put("last_login", loginInfo.get("last_login"));
        userDetail.put("first_login", loginInfo.get("first_login"));
      }
      return userDetail;
    }
    return null;
  }

  @Override
  public Response getUserPropertiesById(
      List<String> userIds, List<String> properties, RequestContext context) {
    return cassandraOperation.getPropertiesValueById(
        KEY_SPACE_NAME, TABLE_NAME, userIds, properties, context);
  }
  /**
   * Retrieves last login information for a specific user
   *
   * @param userId Unique identifier of the user
   * @param context Request context
   * @return Map containing last_login, last_login_ip, login_count, etc.
   */
  public Map<String, Object> getLastLoginInfoById(String userId, RequestContext context) {
    try {
      Response response =
              cassandraOperation.getRecordsByProperty(KEY_SPACE_NAME, JsonKey.USER_LOGIN, JsonKey.CONSENT_USER_ID, Collections.singletonList(userId), context);
      List<Map<String, Object>> responseList =
              (List<Map<String, Object>>) response.get(JsonKey.RESPONSE);
      if (CollectionUtils.isNotEmpty(responseList)) {
        logger.debug(
                context,
                "getLastLoginInfoById: Successfully retrieved login info for user: " + userId);
        return responseList.get(0);
      }
    } catch (Exception e) {
      logger.info(
              context,
              "getLastLoginInfoById: Error retrieving login info for user: " + userId + ", Error: " + e.getMessage());
    }
    return null;
  }

  @Override
  public Map<String, Object> search(SearchDTO searchDTO, RequestContext context) {
    Future<Map<String, Object>> esResultF =
        esService.search(searchDTO, ProjectUtil.EsType.user.getTypeName(), context);
    return (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(esResultF);
  }

  @Override
  public Map<String, Object> getEsUserById(String userId, RequestContext context) {
    Future<Map<String, Object>> esResultF =
        esService.getDataByIdentifier(ProjectUtil.EsType.user.getTypeName(), userId, context);
    Map<String, Object> esResult =
        (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(esResultF);
    if (MapUtils.isEmpty(esResult)) {
      throw new ProjectCommonException(
          ResponseCode.resourceNotFound,
          MessageFormat.format(ResponseCode.resourceNotFound.getErrorMessage(), JsonKey.USER),
          ResponseCode.RESOURCE_NOT_FOUND.getResponseCode());
    }
    return esResult;
  }

  @Override
  public boolean updateUserDataToES(
      String identifier, Map<String, Object> data, RequestContext context) {
    Future<Boolean> responseF =
        esService.update(ProjectUtil.EsType.user.getTypeName(), identifier, data, context);
    if ((boolean) ElasticSearchHelper.getResponseFromFuture(responseF)) {
      return true;
    }
    logger.info(
        context,
        "updateUserDataToES:unable to save the user data to ES with identifier " + identifier);
    return false;
  }

  @Override
  public String saveUserToES(String identifier, Map<String, Object> data, RequestContext context) {
    String type = ProjectUtil.EsType.user.getTypeName();
    Future<String> responseF = esService.save(type, identifier, data, context);
    return (String) ElasticSearchHelper.getResponseFromFuture(responseF);
  }

  @Override
  public String getUserRootOrgId(String userId, RequestContext context) {
    if (org.apache.commons.lang3.StringUtils.isBlank(userId)) {
      return null;
    }
    List<String> properties = Arrays.asList(JsonKey.ID, JsonKey.ROOT_ORG_ID);
    Response userPropertiesResponse = getUserPropertiesById(
            Collections.singletonList(userId),
            properties,
            context
    );
    List<Map<String, Object>> userList = (List<Map<String, Object>>) userPropertiesResponse.get(JsonKey.RESPONSE);
    if (CollectionUtils.isNotEmpty(userList)) {
      return (String) userList.get(0).get(JsonKey.ROOT_ORG_ID);
    }
    return null;
  }
}
