package org.sunbird.actor.user;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.organisation.validator.OrgTypeValidator;
import org.sunbird.actor.user.validator.UserRequestValidator;
import org.sunbird.common.ElasticSearchHelper;
import org.sunbird.dao.user.UserDao;
import org.sunbird.dao.user.impl.UserDaoImpl;
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
import org.sunbird.util.*;
import org.sunbird.util.user.UserUtil;
import org.sunbird.redis.RedisCacheUtil;

public class SSOUserCreateActor extends UserBaseActor {

  private final UserRequestValidator userRequestValidator = new UserRequestValidator();
  private final UserService userService = UserServiceImpl.getInstance();
  private final ObjectMapper mapper = new ObjectMapper();
  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final SSOUserService ssoUserService = SSOUserServiceImpl.getInstance();
  private final int ERROR_CODE = ResponseCode.CLIENT_ERROR.getResponseCode();
  private final OrgService orgService = OrgServiceImpl.getInstance();
  private final UserDao userDao = UserDaoImpl.getInstance();
  private final RoleAssignmentValidator roleAssignmentValidator = new RoleAssignmentValidator();
  private static final String EMAIL_KEY_PREFIX = "sso:email:";
  private static final String PHONE_KEY_PREFIX = "sso:phone:";

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
        createUserV5ByAdmin(request);
        break;
      case "selfRegisterUserV5":
      case "customRegisterUserV5":
      case "supportCreateUserV5":
        createUserV5(request);
        break;
      case "oAuthCreateUserV5":
        createUserV5ForOAuthUser(request);
        break;
      case "bulkCreateUserV5":
        createBulkUsers(request);
        break;
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
    populatePublicRoles(actorMessage);
    createBasisProfileDetails(actorMessage);
    createSSOUser(actorMessage);
  }

  private void createSSOUser(Request actorMessage) {
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createSSOUser: starts : ");
    actorMessage.toLower();
    Map<String, Object> userMap = actorMessage.getRequest();
    String callerId = (String) actorMessage.getContext().get(JsonKey.CALLER_ID);
    userRequestValidator.validateCreateUserRequest(actorMessage);

    // Check Redis for email/phone and set if not exist
    int ttl = Integer.parseInt(PropertiesCache.getInstance().getProperty(JsonKey.USER_CREATION_REDIS_TTL));
    String email = normalizeStringValue(String.valueOf(userMap.get(JsonKey.EMAIL))).toLowerCase();
    String phone = normalizeStringValue(String.valueOf(userMap.get(JsonKey.PHONE))).toLowerCase();
    if (StringUtils.isNotBlank(email)) {
      String emailRedisKey = EMAIL_KEY_PREFIX + email;
      if (isKeyInRedis(emailRedisKey, ttl)) {
        String errorMsg = "Duplicate user creation request: " + email
            + " was processed recently and is still within the TTL window.";
        logger.info(errorMsg);
        ProjectCommonException.throwClientErrorException(
            ResponseCode.errorParamExists,
            MessageFormat.format(
                ResponseCode.errorUserCreationDuplicateRequest.getErrorMessage(), JsonKey.EMAIL_CAPS));
      } else {
        storeKeyInRedis(emailRedisKey, email, ttl);
      }
    }
    if (StringUtils.isNotBlank(phone)) {
      String phoneRedisKey = PHONE_KEY_PREFIX + phone;
      if (isKeyInRedis(phoneRedisKey, ttl)) {
        String errorMsg = "Duplicate user creation request: " + phone
            + " was processed recently and is still within the TTL window.";
        logger.info(errorMsg);
        ProjectCommonException.throwClientErrorException(
            ResponseCode.errorParamExists,
            MessageFormat.format(
                ResponseCode.errorUserCreationDuplicateRequest.getErrorMessage(), JsonKey.PHONE_CAPS));
      } else {
        storeKeyInRedis(phoneRedisKey, phone, ttl);
      }
    }
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

  private boolean isKeyInRedis(String key, int ttl) {
    String data = RedisCacheUtil.get(key, null, ttl);
    return StringUtils.isNotBlank(data);
  }

  private void storeKeyInRedis(String key, String value, int ttl) {
    if (StringUtils.isNotBlank(key)) {
      RedisCacheUtil.set(key, value, ttl);
    }
  }

  private String normalizeStringValue(String value) {
    return (value == null || value.trim().equalsIgnoreCase("null")) ? "" : value.trim();
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
    List<String> roles = (List<String>) requestMap.get(JsonKey.ROLES);

    if (CollectionUtils.isNotEmpty(roles)) {
      roles.replaceAll(String::toUpperCase);
    }

    removeUnwanted(requestMap);
    requestMap.put(JsonKey.IS_DELETED, false);
    Map<String, Boolean> userFlagsMap = new HashMap<>();
    // checks if the user is belongs to state and sets a validation flag
    setStateValidation(requestMap, userFlagsMap);
    int userFlagValue = userFlagsToNum(userFlagsMap);
    requestMap.put(JsonKey.FLAGS_VALUE, userFlagValue);
    logger.info(request.getRequestContext(), "SSOUserCreateActor:createUserAndPassword: " +userMap);
    Response response = ssoUserService.createUserAndPassword(requestMap, userMap, request);

    // Assign roles to user_roles AFTER user creation
    if (CollectionUtils.isNotEmpty(roles)) {
      requestMap.put(JsonKey.ROLES, roles);
      requestMap.put(JsonKey.ROLE_OPERATION, JsonKey.CREATE);
      List<Map<String, Object>> formattedRoles = userRoleService.updateUserRole(requestMap,
          request.getRequestContext());
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
      resp = userService.saveUserAttributes(
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
      Map<String, Object> userDetails = userService.getUserDetailsForES(userId, request.getRequestContext());
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
    // if the user is creating for non-custodian(i.e state) the value is set as true
    // else false
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
      rootOrgId = orgService.getRootOrgIdFromChannel((String) userMap.get(JsonKey.CHANNEL),
          actorMessage.getRequestContext());
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

  private void populateRoles(Request actorMessage, String rootOrgId) {
    Map<String, Object> userMap = (Map<String, Object>) actorMessage.getRequest();
    if (userMap.get(JsonKey.ROLES) == null || ((List<String>) userMap.get(JsonKey.ROLES)).isEmpty()) {
      userMap.put(JsonKey.ROLES, Arrays.asList(JsonKey.PUBLIC));
      if (StringUtils.isNotBlank((String) userMap.get(JsonKey.ORG_NAME))) {
        populatePublicRolesBasedOnOrgName(actorMessage, userMap);
      }
    } else {
      checkIfMDOLeaderExist(userMap, actorMessage, rootOrgId);
    }
  }

  private void createBasisProfileDetails(Request actorMessage) throws JsonProcessingException {
    Map<String, Object> userMap = actorMessage.getRequest();
    Map<String, Object> profileDetails = new HashMap<>();
    Map<String, Object> employmentDetails = Map.of(JsonKey.DEPARTMENT_NAME, userMap.getOrDefault(JsonKey.CHANNEL, ""));
    Map<String, Object> additionalProperties = new HashMap<>();
    List<Map<String, Object>> professionalDetailsList = new ArrayList<>();
    Map<String, Object> professionalDetails = new HashMap<>();
    Map<String, Object> personalDetails = new HashMap<>();

    Map<String, Object> personalDetailsRequest = (Map<String, Object>) userMap.getOrDefault(JsonKey.PERSONAL_DETAILS,
        Map.of());
    if (!personalDetailsRequest.isEmpty()) {
      personalDetailsRequest.forEach((key, value) -> addIfNotEmpty(personalDetails, key, value));

      Object tags = personalDetails.remove(JsonKey.TAGS);
      if (tags instanceof List && !((List<?>) tags).isEmpty()) {
        additionalProperties.put(JsonKey.TAGS, tags);
      }

      addIfNotEmpty(professionalDetails, JsonKey.DESIGNATION, personalDetails.remove(JsonKey.DESIGNATION));
      addIfNotEmpty(professionalDetails, JsonKey.GROUP, personalDetails.remove(JsonKey.GROUP));
    }

    if (!professionalDetails.isEmpty()) {
      professionalDetailsList.add(professionalDetails);
      profileDetails.put(JsonKey.PROFESSIONAL_DETAILS, professionalDetailsList);
    }

    profileDetails.put(JsonKey.EMPLOYMENT_DETAILS, employmentDetails);
    profileDetails.put(JsonKey.PROFILE_GROUP_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_DESIGNATION_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.MANDATORY_FIELDS_EXISTS, false);
    Map<String, String> ministryDetails = orgService.getMinistryInfoFromChannel(
        String.valueOf(actorMessage.getRequest().get(JsonKey.CHANNEL)), actorMessage.getRequestContext());
    profileDetails.put(JsonKey.MINISTRY_STATE_ID, ministryDetails.get(JsonKey.MINISTRY_STATE_ID));
    profileDetails.put(JsonKey.MINISTRY_STATE_ORG_NAME, ministryDetails.get(JsonKey.MINISTRY_STATE_NAME));
    profileDetails.put(JsonKey.MINISTRY_STATE_TYPE, ministryDetails.get(JsonKey.MINISTRY_STATE_TYPE));
    if (!additionalProperties.isEmpty()) {
      profileDetails.put(JsonKey.ADDITIONAL_PROPERTIES, additionalProperties);
    }

    if (!personalDetails.isEmpty()) {
      profileDetails.put(JsonKey.PERSONAL_DETAILS, personalDetails);
    }
    userMap.put(JsonKey.PROFILE_DETAILS, mapper.writeValueAsString(profileDetails));
  }

  private void addIfNotEmpty(Map<String, Object> map, String key, Object value) {
    if (value instanceof String && !((String) value).isBlank()) {
      map.put(key, value);
    } else if (value instanceof List && !((List<?>) value).isEmpty()) {
      map.put(key, value);
    } else if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
      map.put(key, value);
    } else if (value != null) {
      map.put(key, value);
    }
  }

  private void checkIfMDOLeaderExist(Map<String, Object> userMap, Request actorMessage, String rootOrgId) {
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
            ERROR_CODE);
      }
    }
  }

  private void createUserV5ByAdmin(Request actorMessage) throws JsonProcessingException {
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createV5User: starts : ");
    String rootOrgId = findRootOrgId(actorMessage);
    populateRoles(actorMessage, rootOrgId);
    createBasisProfileDetailsByAdmin(actorMessage);
    validateRoleAssignment(actorMessage, rootOrgId);
    createSSOUser(actorMessage);
  }

  private void createBasisProfileDetailsByAdmin(Request actorMessage) throws JsonProcessingException {
    Map<String, Object> userMap = actorMessage.getRequest();
    Map<String, Object> profileDetails = new HashMap<>();
    Map<String, Object> employmentDetails = Map.of(JsonKey.DEPARTMENT_NAME, userMap.getOrDefault(JsonKey.CHANNEL, ""));
    Map<String, Object> additionalProperties = new HashMap<>();
    List<Map<String, Object>> professionalDetailsList = new ArrayList<>();
    Map<String, Object> professionalDetails = new HashMap<>();
    Map<String, Object> personalDetails = new HashMap<>();

    Map<String, Object> profileDetailsRequest = (Map<String, Object>) userMap.getOrDefault(JsonKey.PROFILE_DETAILS,
        Map.of());
    if (!profileDetailsRequest.isEmpty()) {
      Map<String, Object> personalDetailsRequest = (Map<String, Object>) profileDetailsRequest.getOrDefault(
          JsonKey.PERSONAL_DETAILS,
          Map.of());
      if (!personalDetailsRequest.isEmpty()) {
        personalDetailsRequest.forEach((key, value) -> addIfNotEmpty(personalDetails, key, value));

        Object tags = personalDetails.remove(JsonKey.TAGS);
        if (tags instanceof List && !((List<?>) tags).isEmpty()) {
          additionalProperties.put(JsonKey.TAGS, tags);
        }
      }
      addIfNotEmpty(profileDetails, JsonKey.PROFILE_GROUP_STATUS,
          profileDetailsRequest.remove(JsonKey.PROFILE_GROUP_STATUS));
      addIfNotEmpty(profileDetails, JsonKey.PROFILE_DESIGNATION_STATUS,
          profileDetailsRequest.remove(JsonKey.PROFILE_DESIGNATION_STATUS));
      addIfNotEmpty(profileDetails, JsonKey.PROFILE_STATUS, profileDetailsRequest.remove(JsonKey.PROFILE_STATUS));
      addIfNotEmpty(profileDetails, JsonKey.PROFESSIONAL_DETAILS,
          profileDetailsRequest.remove(JsonKey.PROFESSIONAL_DETAILS));
    } else {
      profileDetails.put(JsonKey.PROFILE_GROUP_STATUS, "NOT-VERIFIED");
      profileDetails.put(JsonKey.PROFILE_DESIGNATION_STATUS, "NOT-VERIFIED");
      profileDetails.put(JsonKey.PROFILE_STATUS, "NOT-VERIFIED");
    }

    if (!professionalDetails.isEmpty()) {
      professionalDetailsList.add(professionalDetails);
      profileDetails.put(JsonKey.PROFESSIONAL_DETAILS, professionalDetailsList);
    }
    profileDetails.put(JsonKey.EMPLOYMENT_DETAILS, employmentDetails);
    profileDetails.put(JsonKey.MANDATORY_FIELDS_EXISTS, false);

    if (!additionalProperties.isEmpty()) {
      profileDetails.put(JsonKey.ADDITIONAL_PROPERTIES, additionalProperties);
    }

    if (!personalDetails.isEmpty()) {
      profileDetails.put(JsonKey.PERSONAL_DETAILS, personalDetails);
    }

    Map<String, String> ministryDetails = orgService.getMinistryInfoFromChannel(
        String.valueOf(actorMessage.getRequest().get(JsonKey.CHANNEL)), actorMessage.getRequestContext());
    profileDetails.put(JsonKey.MINISTRY_STATE_ID, ministryDetails.get(JsonKey.MINISTRY_STATE_ID));
    profileDetails.put(JsonKey.MINISTRY_STATE_ORG_NAME, ministryDetails.get(JsonKey.MINISTRY_STATE_NAME));
    profileDetails.put(JsonKey.MINISTRY_STATE_TYPE, ministryDetails.get(JsonKey.MINISTRY_STATE_TYPE));
    userMap.put(JsonKey.PROFILE_DETAILS, mapper.writeValueAsString(profileDetails));
  }

  private void createUserV5ForOAuthUser(Request actorMessage) throws JsonProcessingException {
    logger.debug(actorMessage.getRequestContext(), "SSOUserCreateActor:createV5User: starts : ");
    populatePublicRoles(actorMessage);
    createBasicProfileDetailsForParichayUser(actorMessage);;
    createSSOUser(actorMessage);
  }

  private void createBasicProfileDetailsForParichayUser(Request actorMessage) throws JsonProcessingException {
    Map<String, Object> userMap = actorMessage.getRequest();
    Map<String, Object> profileDetails = new HashMap<>();
    Map<String, Object> employmentDetails = Map.of(JsonKey.DEPARTMENT_NAME, userMap.getOrDefault(JsonKey.CHANNEL, ""));
    Map<String, Object> personalDetails = new HashMap<>();
    String email = (String) userMap.getOrDefault(JsonKey.EMAIL, "");
    if (StringUtils.isNotBlank(email)) {
      personalDetails.put(JsonKey.PRIMARY_EMAIL, email);
      userMap.put(JsonKey.EMAIL_VERIFIED, true);
    }

    String phone = (String) userMap.getOrDefault(JsonKey.PHONE, "");
    if (StringUtils.isNotBlank(phone)) {
      personalDetails.put(JsonKey.MOBILE, phone);
      userMap.put(JsonKey.PHONE_VERIFIED, true);
    }

    String firstName = (String) userMap.getOrDefault(JsonKey.FIRST_NAME, "");
    if (StringUtils.isNotBlank(firstName)) {
      personalDetails.put(JsonKey.FIRST_NAME, firstName);
    }

    profileDetails.put(JsonKey.PROFILE_GROUP_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_DESIGNATION_STATUS, "NOT-VERIFIED");
    profileDetails.put(JsonKey.PROFILE_STATUS, "NOT-VERIFIED");

    profileDetails.put(JsonKey.EMPLOYMENT_DETAILS, employmentDetails);
    profileDetails.put(JsonKey.MANDATORY_FIELDS_EXISTS, false);

    if (!personalDetails.isEmpty()) {
      profileDetails.put(JsonKey.PERSONAL_DETAILS, personalDetails);
    }
    userMap.put(JsonKey.PROFILE_DETAILS, mapper.writeValueAsString(profileDetails));
  }

  private void createBulkUsers(Request actorMessage) throws JsonProcessingException {
    logger.info(actorMessage.getRequestContext(), "SSOUserCreateActor:createBulkUsers: starts : " + actorMessage.getRequest());
    populatePublicRoles(actorMessage);
    updateMinistryDetailsForUsers(actorMessage);
    createSSOUser(actorMessage);
  }

  private void updateMinistryDetailsForUsers(Request actorMessage) throws JsonProcessingException {
    Map<String, Object> userMap = actorMessage.getRequest();
    Map<String, Object> profileDetailsMap = (Map<String, Object>) userMap.get(JsonKey.PROFILE_DETAILS);
    if (MapUtils.isEmpty(profileDetailsMap)) {
      ProjectCommonException.throwClientErrorException(ResponseCode.bulkUserCreateProfileValidation,
          ResponseCode.bulkUserCreateProfileValidation.getErrorMessage());
    }
    Map<String, String> ministryDetails = orgService.getMinistryInfoFromChannel(
        String.valueOf(actorMessage.getRequest().get(JsonKey.CHANNEL)),
        actorMessage.getRequestContext());
    profileDetailsMap.put(JsonKey.MINISTRY_STATE_ID, ministryDetails.get(JsonKey.MINISTRY_STATE_ID));
    profileDetailsMap.put(JsonKey.MINISTRY_STATE_ORG_NAME, ministryDetails.get(JsonKey.MINISTRY_STATE_NAME));
    profileDetailsMap.put(JsonKey.MINISTRY_STATE_TYPE, ministryDetails.get(JsonKey.MINISTRY_STATE_TYPE));
    userMap.put(JsonKey.PROFILE_DETAILS, mapper.writeValueAsString(profileDetailsMap));
  }

  private void populatePublicRoles(Request actorMessage) {
    Map<String, Object> userMap = actorMessage.getRequest();
    userMap.put(JsonKey.ROLES, Arrays.asList(JsonKey.PUBLIC));

    if (StringUtils.isNotBlank((String) userMap.get(JsonKey.ORG_NAME))) {
      populatePublicRolesBasedOnOrgName(actorMessage, userMap);
    }
  }

  private boolean populatePublicRolesBasedOnOrgName(Request actorMessage, Map<String, Object> userMap) {
    String orgName = (String) userMap.get(JsonKey.ORG_NAME);
    if (StringUtils.isBlank(orgName)) {
      return false;
    }
    orgName = orgName.trim();

    Map<String, Object> searchQueryMap = new HashMap<>();
    Map<String, Object> filters = new HashMap<>();
    filters.put(JsonKey.ORG_NAME, orgName);
    searchQueryMap.put(JsonKey.FILTERS, filters);
    SearchDTO searchDTO = ElasticSearchHelper.createSearchDTO(searchQueryMap);
    try {
      Map<String, Object> esResponse =
              (Map<String, Object>)
                      ElasticSearchHelper.getResponseFromFuture(
                              orgService.searchOrg(searchDTO, actorMessage.getRequestContext()));
      if (MapUtils.isNotEmpty(esResponse)) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) esResponse.get(JsonKey.CONTENT);
        if (CollectionUtils.isNotEmpty(content)) {
          Map<String, Object> organisation = content.get(0);
          String organisationId = (String) organisation.get(JsonKey.ID);
          String authOrganisationId = (String) userMap.get(JsonKey.X_AUTH_USER_ORG_ID);
          Map<String, Object> authOrganisation = null;
          if (StringUtils.isNotBlank(authOrganisationId)) {
            authOrganisation = orgService.getOrgById(authOrganisationId, actorMessage.getRequestContext());
          }

          /*if (!isSameMinistryOrState(authOrganisation, organisation)) {
            throw new ProjectCommonException(
                    ResponseCode.errorConflictingRootOrgId,
                    ResponseCode.errorConflictingRootOrgId.getErrorMessage(),
                    ResponseCode.CLIENT_ERROR.getResponseCode());
          }*/

          applyOrganisationRoleAndRootOrg(actorMessage, userMap, organisation, organisationId);
          return true;
        }
      }
    } catch (Exception ex) {
      logger.error(
              actorMessage.getRequestContext(),
              "SSOUserCreateActor:populatePublicRolesBasedOnOrgName: Exception while fetching organisation by orgName",
              ex);
    }
    return false;
  }

  private boolean isSameMinistryOrState(Map<String, Object> authOrganisation, Map<String, Object> organisation) {
    if (MapUtils.isEmpty(authOrganisation) || MapUtils.isEmpty(organisation)) {
      return true;
    }

    String authMinistryStateId = getStringValue(authOrganisation, JsonKey.MINISTRY_STATE_ID);
    String authMinistryStateName = getStringValue(authOrganisation, JsonKey.MINISTRY_STATE_NAME);
    String organisationMinistryStateId = getStringValue(organisation, JsonKey.MINISTRY_STATE_ID);
    String organisationMinistryStateName = getStringValue(organisation, JsonKey.MINISTRY_STATE_NAME);

    return StringUtils.equalsIgnoreCase(authMinistryStateId, organisationMinistryStateId)
            || StringUtils.equalsIgnoreCase(authMinistryStateName, organisationMinistryStateName);
  }

  private void applyOrganisationRoleAndRootOrg(
          Request actorMessage, Map<String, Object> userMap, Map<String, Object> organisation, String organisationId) {
    if (organisation != null && organisation.get(JsonKey.ORG_TYPE) != null) {
      Object orgType = organisation.get(JsonKey.ORG_TYPE);
      if (orgType instanceof Number) {
        int organisationType = ((Number) orgType).intValue();
        if (JsonKey.ORG_TYPE_NGO.equalsIgnoreCase(
                OrgTypeValidator.getInstance().getTypeByValue(organisationType))) {
          userMap.put(JsonKey.ROLES, Arrays.asList(JsonKey.VOLUNTEER));
        }
      }
    }
    if (StringUtils.isNotBlank(organisationId)) {
      userMap.put(JsonKey.ROOT_ORG_ID, organisationId);
    }
  }

  private String getStringValue(Map<String, Object> data, String key) {
    if (MapUtils.isEmpty(data)) {
      return StringUtils.EMPTY;
    }
    Object value = data.get(key);
    return value == null ? StringUtils.EMPTY : String.valueOf(value).trim();
  }

  private void validateRoleAssignment(Request actorMessage, String targetOrgId) {
    Map<String, Object> userMap = actorMessage.getRequest();
    List<String> roles = (List<String>) userMap.get(JsonKey.ROLES);

    if (CollectionUtils.isEmpty(roles)) {
      throw new ProjectCommonException(
              ResponseCode.mandatoryParamsMissing,
              MessageFormat.format(ResponseCode.mandatoryParamsMissing.getErrorMessage(), JsonKey.ROLES),
              ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    roles.replaceAll(String::toUpperCase);
    userMap.put(JsonKey.ROLES, roles);

    String requestingUserId = (String) actorMessage.getContext().get(JsonKey.REQUESTED_BY);

    if (StringUtils.isNotBlank(requestingUserId)) {
      String requestingUserOrgId = userDao.getUserRootOrgId(requestingUserId, actorMessage.getRequestContext());
      if (StringUtils.isBlank(requestingUserOrgId)) {
        throw new ProjectCommonException(
                ResponseCode.invalidRequestData,
                "Unable to fetch requesting user's root org ID",
                ResponseCode.CLIENT_ERROR.getResponseCode());
      }

      roleAssignmentValidator.validateRoleAssignment(
              requestingUserId,
              requestingUserOrgId,
              targetOrgId,
              null, // targetUserId is null for new user creation - all roles will be validated
              roles,
              actorMessage.getRequestContext());
    } else {
      throw new ProjectCommonException(
              ResponseCode.unAuthorized,
              "Invalid or missing X-Auth token. Unable to identify the requesting user (account creator)",
              ResponseCode.UNAUTHORIZED.getResponseCode());
    }
  }
}
