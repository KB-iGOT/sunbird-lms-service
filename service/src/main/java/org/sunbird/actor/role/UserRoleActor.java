package org.sunbird.actor.role;

import akka.actor.ActorRef;

import java.util.*;
import javax.inject.Inject;
import javax.inject.Named;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.user.UserBaseActor;
import org.sunbird.common.ElasticSearchHelper;
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
import org.sunbird.util.Util;

public class UserRoleActor extends UserBaseActor {

  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final UserProfileReadService profileReadService = new UserProfileReadService();
  private final UserService userService = UserServiceImpl.getInstance();
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
      logger.info(actorMessage.getRequestContext(), "MDO Leader already exist in org");
      Response response = new Response();
      response.put(JsonKey.RESPONSE, "MDO Leader already exist in org");
      sender().tell(response, self());
      return;
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
        InstructionEventGenerator.mentorshipUserUpdateEvent("", topic, userDetails);
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
