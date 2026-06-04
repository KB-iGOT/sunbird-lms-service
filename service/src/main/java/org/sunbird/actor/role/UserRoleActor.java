package org.sunbird.actor.role;

import akka.actor.ActorRef;

import java.util.*;
import javax.inject.Inject;
import javax.inject.Named;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.user.UserBaseActor;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.dao.user.UserDao;
import org.sunbird.dao.user.impl.UserDaoImpl;
import org.sunbird.dto.SearchDTO;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.kafka.InstructionEventGenerator;
import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.service.role.RoleService;
import org.sunbird.service.user.UserProfileReadService;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.UserService;
import org.sunbird.service.user.impl.UserRoleServiceImpl;
import org.sunbird.service.user.impl.UserServiceImpl;
import org.sunbird.telemetry.dto.TelemetryEnvKey;
import org.sunbird.util.DataCacheHandler;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.PropertiesCache;
import org.sunbird.util.RoleAssignmentValidator;
import org.sunbird.util.Util;

public class UserRoleActor extends UserBaseActor {

  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final UserProfileReadService profileReadService = new UserProfileReadService();
  private final UserService userService = UserServiceImpl.getInstance();
  private final UserDao userDao = UserDaoImpl.getInstance();
  private final RoleAssignmentValidator roleAssignmentValidator = new RoleAssignmentValidator();
  @Inject
  @Named("user_role_background_actor")
  private ActorRef userRoleBackgroundActor;

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, TelemetryEnvKey.USER);
    String operation = request.getOperation();

    switch (operation) {
      case "getRoles":
        getRoles(request.getRequestContext());
        break;

      case "assignRoles":
      case "assignRolesV2":
        assignRoles(request);
        break;

      default:
        onReceiveUnsupportedOperation();
    }
  }

  private void getRoles(RequestContext context) {
    Response response = DataCacheHandler.getRoleResponse();
    if (response == null) {
      response = new RoleService().getUserRoles(context);
      DataCacheHandler.setRoleResponse(response);
    }
    sender().tell(response, self());
  }

  private void assignRoles(Request actorMessage) {
    List<Map<String, Object>> userRolesList;

    Map<String, Object> requestMap = actorMessage.getRequest();
    requestMap.put(JsonKey.REQUESTED_BY, actorMessage.getContext().get(JsonKey.USER_ID));

    String userId = (String) requestMap.get(JsonKey.USER_ID);
    String requestingUserId = (String) actorMessage.getContext().get(JsonKey.USER_ID);
    String targetOrgId = (String) requestMap.get(JsonKey.ORGANISATION_ID);
    String requestingUserOrgId = null;

    boolean isPrivate = actorMessage.getContext().get(JsonKey.PRIVATE) != null
        && (boolean) actorMessage.getContext().get(JsonKey.PRIVATE);

    actorMessage.getContext().put(JsonKey.USER_ID, userId);
    Response userProfileDataResponse = profileReadService.getUserProfileData(actorMessage);
    Map<String, Object> userProfileDataMap = (Map<String, Object>) userProfileDataResponse.get(JsonKey.RESPONSE);
    String orgId = ((List<Map<String, Object>>) userProfileDataMap.get(JsonKey.ORGANISATIONS))
            .stream()
            .findFirst()
            .map(org -> (String) org.get(JsonKey.ORGANISATION_ID))
            .orElse(null);
    if (StringUtils.isNotEmpty(orgId) && !orgId.equalsIgnoreCase((String) requestMap.get(JsonKey.ORGANISATION_ID))) {
      logger.info(actorMessage.getRequestContext(), "User and org is not same");
      Response response = new Response();
      response.put(JsonKey.RESPONSE, "User Organisation Id and Assigner organisation Id mismatch");
      sender().tell(response, self());
      return;
    }

    if (StringUtils.isNotBlank(requestingUserId)) {
      List<String> properties = new ArrayList<>();
      properties.add(JsonKey.ID);
      properties.add(JsonKey.ROOT_ORG_ID);
      Response userPropertiesResponse = userDao.getUserPropertiesById(Arrays.asList(requestingUserId), properties, actorMessage.getRequestContext());

      List<Map<String, Object>> userList = (List<Map<String, Object>>) userPropertiesResponse.get(JsonKey.RESPONSE);
      if (CollectionUtils.isNotEmpty(userList)) {
        requestingUserOrgId = (String) userList.get(0).get(JsonKey.ROOT_ORG_ID);
      }
    }

    List<String> assignRoles = null;
    if (actorMessage.getOperation().equals(ActorOperations.ASSIGN_ROLES.getValue())) {
      assignRoles = (List<String>) requestMap.get(JsonKey.ROLES);
    } else {
      List<Map<String, Object>> roleList = (List<Map<String, Object>>) requestMap.get(JsonKey.ROLES);
      if (CollectionUtils.isNotEmpty(roleList)) {
        assignRoles = new ArrayList<>();
        for (Map<String, Object> roleObj : roleList) {
          String role = (String) roleObj.get(JsonKey.ROLE);
          if (role != null) {
            assignRoles.add(role);
          }
        }
      }
    }


    if (CollectionUtils.isNotEmpty(assignRoles) && StringUtils.isNotBlank(requestingUserId)) {
      String targetUserIdForValidation = isPrivate ? null : userId;

      roleAssignmentValidator.validateRoleAssignment(requestingUserId, requestingUserOrgId, targetOrgId,
              targetUserIdForValidation,
              assignRoles,
              actorMessage.getRequestContext()
      );
    }
    if (CollectionUtils.isNotEmpty(assignRoles)) {
      boolean isTryingToAssignMdoLeader = assignRoles.contains(JsonKey.MDO_LEADER);
      List<Map<String, Object>> existingRoles = userRoleService.readUserRole(userId, actorMessage.getRequestContext());
      boolean isExistingMdoLeader = existingRoles.stream()
              .anyMatch(role -> JsonKey.MDO_LEADER.equals(role.get(JsonKey.ROLE)));
      if (isTryingToAssignMdoLeader && !isExistingMdoLeader) {
        Map<String, Object> requestMaps = new HashMap<>();
        Map<String, Object> filtersMap = new HashMap<>();
        filtersMap.put(JsonKey.ROOT_ORG_ID, requestMap.get(JsonKey.ORGANISATION_ID));
        filtersMap.put(JsonKey.STATUS, 1);
        List<String> rolesList = new ArrayList<>();
        rolesList.add(JsonKey.MDO_LEADER);
        filtersMap.put(JsonKey.ORGANISATION_ROLES, rolesList);
        requestMaps.put(JsonKey.FILTERS, filtersMap);
        modifySearchQueryReqForNewRoleStructure(requestMaps);
        SearchDTO searchDto = ElasticSearchHelper.createSearchDTO(requestMaps);
        searchDto.setExcludedFields(Arrays.asList(ProjectUtil.excludes));
        Map<String, Object> result = userService.searchUser(searchDto, actorMessage.getRequestContext());
        Number count = (Number) result.get(JsonKey.COUNT);

        if (count.longValue() >= 1) {
          logger.info(actorMessage.getRequestContext(), "MDO Leader already exists in org");
          Response response = new Response();
          response.put(JsonKey.RESPONSE, "MDO Leader already exists in org");
          sender().tell(response, self());
          return;
        }
      }
    }
    if (actorMessage.getOperation().equals(ActorOperations.ASSIGN_ROLES.getValue())) {
      requestMap.put(JsonKey.ROLE_OPERATION, "assignRole");
      List<String> roles = (List<String>) requestMap.get(JsonKey.ROLES);
      RoleService.validateRoles(roles);
      String configValue = PropertiesCache.getInstance().getProperty(JsonKey.DISABLE_MULTIPLE_ORG_ROLE);
      if(Boolean.parseBoolean(configValue)) {
        validateRequest(userRoleService.getUserRoles((String) requestMap.get(JsonKey.USER_ID), actorMessage.getRequestContext()),
                (String) requestMap.get(JsonKey.ORGANISATION_ID), actorMessage.getRequestContext());
      }
      userRolesList = userRoleService.updateUserRole(requestMap, actorMessage.getRequestContext());
    } else {
      List<Map<String, Object>> roleList =
          (List<Map<String, Object>>) requestMap.get(JsonKey.ROLES);
      RoleService.validateRolesV2(roleList);
      userRolesList =
          userRoleService.updateUserRoleV2(requestMap, actorMessage.getRequestContext());
    }
    Response response = new Response();
    response.put(JsonKey.RESPONSE, JsonKey.SUCCESS);

    sender().tell(response, self());
    userRolesList = userRoleService.readUserRole((String) requestMap.get(JsonKey.USER_ID), actorMessage.getRequestContext());
    ObjectMapper mapper = new ObjectMapper();
    userRolesList
            .stream()
            .forEach(
                    userRole -> {
                      try {
                        String dbScope = (String) userRole.get(JsonKey.SCOPE);
                        if (StringUtils.isNotBlank(dbScope)) {
                          List<Map<String, String>> scope = mapper.readValue(dbScope, ArrayList.class);
                          userRole.put(JsonKey.SCOPE, scope);
                        }
                      } catch (Exception e) {
                        logger.error(
                                actorMessage.getRequestContext(),
                                "Exception because of mapper read value" + userRole.get(JsonKey.SCOPE),
                                e);
                      }
                    });
    syncUserRoles(
        JsonKey.USER,
        (String) requestMap.get(JsonKey.USER_ID),
        userRolesList,
        actorMessage.getRequestContext());
    if (response.get(JsonKey.RESPONSE).equals(JsonKey.SUCCESS)) {
      String topic = ProjectUtil.getConfigValue("kafka_mentorship_user_update_topic");
      try {
        HashMap<String,String> userDetails = new HashMap<>();
        userDetails.put(JsonKey.USER_ID,(String) requestMap.get(JsonKey.USER_ID));
        InstructionEventGenerator.userUpdateEvent("", topic, userDetails);
        logger.info("kafka_mentorship_user_update_topic event pushed after role change");
      }catch (Exception e){
        logger.error("error while generating mentorship event :", e);
      }
    }
    generateTelemetryEvent(
        requestMap,
        (String) requestMap.get(JsonKey.USER_ID),
        "userLevel",
        actorMessage.getContext());
  }

  private void syncUserRoles(
      String type, String userId, List<Map<String, Object>> userRolesList, RequestContext context) {
    Request request = new Request();
    request.setRequestContext(context);
    request.setOperation(ActorOperations.UPDATE_USER_ROLES_ES.getValue());
    request.getRequest().put(JsonKey.TYPE, type);
    request.getRequest().put(JsonKey.USER_ID, userId);
    request.getRequest().put(JsonKey.ROLES, userRolesList);
    logger.debug(context, "UserRoleActor:syncUserRoles: Syncing to ES");
    try {
      userRoleBackgroundActor.tell(request, self());
    } catch (Exception ex) {
      logger.error(
          context,
          "UserRoleActor:syncUserRoles: Exception occurred with error message = " + ex.getMessage(),
          ex);
    }
  }

  private void validateRequest(List<Map<String, Object>> userRolesList, String organisationId, RequestContext context) {
    ObjectMapper mapper = new ObjectMapper();
    userRolesList
            .stream()
            .forEach(
                    userRole -> {
                      try {
                        String dbScope = (String) userRole.get(JsonKey.SCOPE);
                        if (StringUtils.isNotBlank(dbScope)) {
                          List<Map<String, String>> scope = mapper.readValue(dbScope, ArrayList.class);
                          userRole.put(JsonKey.SCOPE, scope);
                          for(Map<String, String> orgScope : scope) {
                            String oldOrgId = orgScope.get("organisationId");
                            if(StringUtils.isNotBlank(oldOrgId) && !oldOrgId.equalsIgnoreCase(organisationId)) {
                              logger.info(context, "UserRoleActor: Given OrganisationId is different than existing one.");
                              throw new ProjectCommonException(
                                      ResponseCode.roleProcessingInvalidOrgError,
                                      ResponseCode.roleProcessingInvalidOrgError.getErrorMessage(),
                                      ResponseCode.SERVER_ERROR.getResponseCode());
                            }
                          }
                        }
                      } catch(ProjectCommonException pce) {
                        throw pce;
                      } catch (Exception e) {
                        logger.error(
                                context,
                                "Exception because of mapper read value" + userRole.get(JsonKey.SCOPE),
                                e);
                      }
                    });
  }

  private void modifySearchQueryReqForNewRoleStructure(Map<String, Object> searchQueryMap) {
    Map<String, Object> filterMap = (Map<String, Object>) searchQueryMap.get(JsonKey.FILTERS);
    Object roles = filterMap.remove(JsonKey.ORGANISATIONS + "." + JsonKey.ROLES);
    if (null != roles) {
      filterMap.put(JsonKey.ROLES + "." + JsonKey.ROLE, roles);
    }
  }
}
