package org.sunbird.actor.user;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.MessageFormat;
import java.util.*;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.user.validator.UserRequestValidator;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.dto.SearchDTO;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.user.User;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.service.organisation.OrgService;
import org.sunbird.service.organisation.impl.OrgServiceImpl;
import org.sunbird.service.user.AssociationMechanism;
import org.sunbird.service.user.SSOUserService;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.UserService;
import org.sunbird.service.user.impl.SSOUserServiceImpl;
import org.sunbird.service.user.impl.UserRoleServiceImpl;
import org.sunbird.service.user.impl.UserServiceImpl;
import org.sunbird.telemetry.dto.TelemetryEnvKey;
import org.sunbird.util.DataCacheHandler;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.UserFlagUtil;
import org.sunbird.util.Util;
import org.sunbird.util.user.UserUtil;

public class SSOUserCreateActor extends UserBaseActor {

  private final UserRequestValidator userRequestValidator = new UserRequestValidator();
  private final UserService userService = UserServiceImpl.getInstance();
  private final ObjectMapper mapper = new ObjectMapper();
  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final SSOUserService ssoUserService = SSOUserServiceImpl.getInstance();
  private final int ERROR_CODE = ResponseCode.CLIENT_ERROR.getResponseCode();
  private final OrgService orgService = OrgServiceImpl.getInstance();

  @Inject
  @Named("user_profile_update_actor")
  private ActorRef userProfileUpdateActor;

  @Inject
  @Named("background_job_manager_actor")
  private ActorRef backgroundJobManager;

  @Inject
  @Named("user_on_boarding_notification_actor")
  private ActorRef userOnBoardingNotificationActor;

  @Override
  public void onReceive(Request request) throws Throwable {
    Util.initializeContext(request, TelemetryEnvKey.USER);
    String operation = request.getOperation();
    switch (operation) {
      case "createUser": // create User [v1,v2,v3]
      case "createSSOUser":
        createSSOUser(request);
        break;
      case "createUserV5":
      case "selfRegisterUserV5":
      case "customRegisterUserV5":
      case "bulkCreateUserV5":
      case "parichayCreateUserV5":
        createUserV5(request);
      default:
        onReceiveUnsupportedOperation();
    }
  }

  /**
   * Method to create the new user , Username should be unique .
   *
   * @param actorMessage Request
   */

  private void createUserV5(Request actorMessage) throws JsonProcessingException {
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createV5User: starts : ");
    populateRoles(actorMessage, findRootOrgId(actorMessage));
    createBasisProfileDetails(actorMessage);
    createSSOUser(actorMessage);
  }

  private void createSSOUser(Request actorMessage) {
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createSSOUser: starts : ");
    actorMessage.toLower();
    Map<String, Object> userMap = actorMessage.getRequest();
    String callerId = (String) actorMessage.getContext().get(JsonKey.CALLER_ID);
    userRequestValidator.validateCreateUserRequest(actorMessage);
    if (StringUtils.isNotBlank(callerId)) {
      userMap.put(JsonKey.ROOT_ORG_ID, actorMessage.getContext().get(JsonKey.ROOT_ORG_ID));
    }
    if (actorMessage.getOperation().equalsIgnoreCase(ActorOperations.CREATE_SSO_USER.getValue())) {
      populateUserTypeAndSubType(userMap);
      populateLocationCodesFromProfileLocation(userMap);
    }
    validateAndGetLocationCodes(actorMessage);
    convertValidatedLocationCodesToIDs(userMap, actorMessage.getRequestContext());
    ssoUserService.validateOrgIdAndPrimaryRecoveryKeys(userMap, actorMessage);
    processSSOUser(userMap, callerId, actorMessage);
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createSSOUser: ends : ");
  }

  private void processSSOUser(Map<String, Object> userMap, String callerId, Request request) {
    Map<String, Object> requestMap;
    UserUtil.setUserDefaultValue(userMap, request.getRequestContext());
    // Update external ids provider with OrgId
    UserUtil.updateExternalIdsProviderWithOrgId(userMap, request.getRequestContext());
    User user = mapper.convertValue(userMap, User.class);
    UserUtil.validateExternalIds(user, JsonKey.CREATE, request.getRequestContext());
    userMap.put(JsonKey.EXTERNAL_IDS, user.getExternalIds());
    UserUtil.toLower(userMap);
    UserUtil.validateUserPhoneAndEmailUniqueness(user, JsonKey.CREATE, request.getRequestContext());
    UserUtil.addMaskEmailAndMaskPhone(userMap);
    String userId = ProjectUtil.generateUniqueId();
    userMap.put(JsonKey.ID, userId);
    userMap.put(JsonKey.USER_ID, userId);
    requestMap = UserUtil.encryptUserData(userMap);
    // removing roles from requestMap, so it won't get save in user table
    List<String> roles = (List<String>) requestMap.get(JsonKey.ROLES);
    removeUnwanted(requestMap);
    requestMap.put(JsonKey.IS_DELETED, false);
    Map<String, Boolean> userFlagsMap = new HashMap<>();
    // checks if the user is belongs to state and sets a validation flag
    setStateValidation(requestMap, userFlagsMap);
    int userFlagValue = userFlagsToNum(userFlagsMap);
    requestMap.put(JsonKey.FLAGS_VALUE, userFlagValue);
    Response response = ssoUserService.createUserAndPassword(requestMap, userMap, request);
    // update roles to user_roles
    if (CollectionUtils.isNotEmpty(roles)) {
      requestMap.put(JsonKey.ROLES, roles);
      requestMap.put(JsonKey.ROLE_OPERATION, JsonKey.CREATE);
      List<Map<String, Object>> formattedRoles =
          userRoleService.updateUserRole(requestMap, request.getRequestContext());
      requestMap.put(JsonKey.ROLES, formattedRoles);
    }
    Response resp = null;
    if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
      Map<String, Object> userRequest = new HashMap<>();
      userRequest.putAll(userMap);
      userRequest.put(JsonKey.OPERATION_TYPE, JsonKey.CREATE);
      userRequest.put(JsonKey.CALLER_ID, callerId);
      userRequest.put(JsonKey.ASSOCIATION_TYPE, AssociationMechanism.SSO);
      if (StringUtils.isNotBlank(callerId) && callerId.equalsIgnoreCase(JsonKey.BULK_USER_UPLOAD)) {
        userRequest.put(JsonKey.ASSOCIATION_TYPE, AssociationMechanism.SYSTEM_UPLOAD);
      }
      resp =
          userService.saveUserAttributes(
              userRequest, userProfileUpdateActor, request.getRequestContext());
    } else {
      logger.info(
          request.getRequestContext(), "SSOUserCreateActor:processSSOUser: User creation failure");
    }
    Map<String, Object> esResponse = new HashMap<>();
    if (null != resp) {
      esResponse.putAll((Map<String, Object>) resp.getResult().get(JsonKey.RESPONSE));
      esResponse.putAll(requestMap);
      response.put(
          JsonKey.ERRORS,
          ((Map<String, Object>) resp.getResult().get(JsonKey.RESPONSE)).get(JsonKey.ERRORS));
    }
    Response syncResponse = new Response();
    syncResponse.putAll(response.getResult());

    if (null != resp && userMap.containsKey("sync") && (boolean) userMap.get("sync")) {
      Map<String, Object> userDetails =
          userService.getUserDetailsForES(userId, request.getRequestContext());
      userService.saveUserToES(
          (String) userDetails.get(JsonKey.USER_ID), userDetails, request.getRequestContext());
      sender().tell(syncResponse, sender());
    } else {
      if (null != resp) {
        saveUserDetailsToEs(esResponse, request.getRequestContext());
      }
      sender().tell(response, self());
    }
    requestMap.put(JsonKey.PASSWORD, userMap.get(JsonKey.PASSWORD));
    if (StringUtils.isNotBlank(callerId)) {
      sendEmailAndSms(requestMap, request.getRequestContext());
    }
    generateUserTelemetry(userMap, request, userId, JsonKey.CREATE);
  }

  private void setStateValidation(
      Map<String, Object> requestMap, Map<String, Boolean> userBooleanMap) {
    String rootOrgId = (String) requestMap.get(JsonKey.ROOT_ORG_ID);
    String custodianRootOrgId = DataCacheHandler.getConfigSettings().get(JsonKey.CUSTODIAN_ORG_ID);
    // if the user is creating for non-custodian(i.e state) the value is set as true else false
    userBooleanMap.put(JsonKey.STATE_VALIDATED, !custodianRootOrgId.equals(rootOrgId));
  }

  private int userFlagsToNum(Map<String, Boolean> userBooleanMap) {
    int userFlagValue = 0;
    Set<Map.Entry<String, Boolean>> mapEntry = userBooleanMap.entrySet();
    for (Map.Entry<String, Boolean> entry : mapEntry) {
      if (StringUtils.isNotEmpty(entry.getKey())) {
        userFlagValue += UserFlagUtil.getFlagValue(entry.getKey(), entry.getValue());
      }
    }
    return userFlagValue;
  }

  private void saveUserDetailsToEs(Map<String, Object> completeUserMap, RequestContext context) {
    Request userRequest = new Request();
    userRequest.setRequestContext(context);
    userRequest.setOperation(ActorOperations.UPDATE_USER_INFO_ELASTIC.getValue());
    userRequest.getRequest().put(JsonKey.ID, completeUserMap.get(JsonKey.ID));
    logger.info(
        context, "SSOUserCreateActor:saveUserDetailsToEs: Trigger sync of user details to ES");
    try {
      backgroundJobManager.tell(userRequest, self());
    } catch (Exception ex) {
      logger.error(context, "Exception while saving user data to ES", ex);
    }
  }

  private void sendEmailAndSms(Map<String, Object> userMap, RequestContext context) {
    // sendEmailAndSms
    Request EmailAndSmsRequest = new Request();
    EmailAndSmsRequest.getRequest().putAll(userMap);
    EmailAndSmsRequest.setRequestContext(context);
    EmailAndSmsRequest.setOperation(ActorOperations.PROCESS_ONBOARDING_MAIL_AND_SMS.getValue());
    try {
      userOnBoardingNotificationActor.tell(EmailAndSmsRequest, self());
    } catch (Exception ex) {
      logger.error(context, "Exception while sending notification", ex);
    }
  }

  private void modifySearchQueryReqForNewRoleStructure(Map<String, Object> searchQueryMap) {
    Map<String, Object> filterMap = (Map<String, Object>) searchQueryMap.get(JsonKey.FILTERS);
    Object roles = filterMap.remove(JsonKey.ORGANISATIONS + "." + JsonKey.ROLES);
    if (null != roles) {
      filterMap.put(JsonKey.ROLES + "." + JsonKey.ROLE, roles);
    }
  }

  private String findRootOrgId(Request actorMessage) {
    Map<String, Object> userMap = actorMessage.getRequest();
    String rootOrgId = "";
    if (userMap.get(JsonKey.CHANNEL) != null) {
      rootOrgId = orgService.getRootOrgIdFromChannel((String) userMap.get(JsonKey.CHANNEL), actorMessage.getRequestContext());
      if (StringUtils.isBlank(rootOrgId)) {
        throw new ProjectCommonException(
                ResponseCode.invalidParameterValue,
                ProjectUtil.formatMessage(
                        ResponseCode.invalidParameterValue.getErrorMessage(),
                        userMap.get(JsonKey.CHANNEL),
                        JsonKey.CHANNEL),
                ResponseCode.CLIENT_ERROR.getResponseCode());
      }
    } else {
      ProjectCommonException.throwClientErrorException(
              ResponseCode.invalidParameter,
              MessageFormat.format(
                      ResponseCode.invalidParameter.getErrorMessage(),
                      JsonKey.CHANNEL));
    }
    return rootOrgId;
  }

  private void populateRoles(Request actorMessage, String rootOrgId ) {
    Map<String, Object> userMap = actorMessage.getRequest();
    if (userMap.get(JsonKey.ROLES) == null || ((List<String>) userMap.get(JsonKey.ROLES)).isEmpty()) {
      userMap.put(JsonKey.ROLES, Arrays.asList(JsonKey.PUBLIC));
    } else {
      checkIfMDOLeaderExist(userMap, actorMessage, rootOrgId);
    }
  }

  private void createBasisProfileDetails(Request actorMessage) throws JsonProcessingException {
    Map<String, Object> userMap = actorMessage.getRequest();
    Map<String, Object> profileDetails = new HashMap<>();
    profileDetails.put(JsonKey.PROFILE_GROUP_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_DESIGNATION_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.MANDATORY_FIELDS_EXISTS, false);
    ObjectMapper objectMapper = new ObjectMapper();
    String profileDetailsJson = objectMapper.writeValueAsString(profileDetails);
    userMap.put(JsonKey.PROFILE_DETAILS, profileDetailsJson);
  }

  private void checkIfMDOLeaderExist(Map<String,Object> userMap, Request actorMessage, String rootOrgId){
    List<String> roles = (List<String>) userMap.get(JsonKey.ROLES);
    if (roles.contains(JsonKey.MDO_LEADER)) {
      System.out.println("Role MDO_LEADER is present");
      Map<String, Object> requestMaps = new HashMap<>();
      Map<String, Object> filtersMap = new HashMap<>();
      filtersMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
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
        throw new ProjectCommonException(
                ResponseCode.dataTypeError,
                ProjectUtil.formatMessage(
                        "MDO Leader already exist in org", JsonKey.ROLES, JsonKey.LIST),
                ERROR_CODE);        }
    }
  }

}
