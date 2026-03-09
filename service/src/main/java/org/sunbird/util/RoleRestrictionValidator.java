package org.sunbird.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.dao.systemsettings.SystemSettingDao;
import org.sunbird.dao.systemsettings.impl.SystemSettingDaoImpl;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.RequestContext;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.impl.UserRoleServiceImpl;

import java.util.*;

/**
 * Utility class to validate role assignment authorization based on requesting user's roles
 * and configured role restrictions from system_settings database.
 *
 * Supports multiple roles per user with permissive approach - user can assign a role
 * if ANY of their roles allows it (only blocked if ALL roles restrict it).
 */
public class RoleRestrictionValidator {

  private static final LoggerUtil logger = new LoggerUtil(RoleRestrictionValidator.class);
  private static final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private static final SystemSettingDao systemSettingDao = new SystemSettingDaoImpl();
  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * Validates role assignment by fetching requesting user's roles from database.
   *
   * @param requestingUserId ID of the user requesting role assignment/creation
   * @param rolesToAssign List of roles to be assigned/created
   * @param context Request context for logging and DB access
   * @param errorCode Error code to use in exception (typically CLIENT_ERROR)
   * @throws ProjectCommonException if user is not authorized to assign the requested roles
   */
  public static void validateRoleAssignment(
          String requestingUserId,
          List<String> rolesToAssign,
          RequestContext context,
          int errorCode) {

    if (CollectionUtils.isEmpty(rolesToAssign)) {
      logger.info(context, "validateRoleAssignment: No roles to assign, skipping validation");
      return;
    }

    if (StringUtils.isBlank(requestingUserId)) {
      logger.error(context,
              "validateRoleAssignment: No requesting user ID provided - cannot validate", null);
      throw new ProjectCommonException(
              ResponseCode.unauthorizedRoleAssignment,
              "Cannot validate role assignment: requesting user not identified",
              errorCode);
    }

    // Fetch the roles of the requesting user
    List<Map<String, Object>> requestingUserRoles = userRoleService.getUserRoles(requestingUserId, context);

    validateRoleAssignmentWithFetchedRoles(requestingUserRoles, rolesToAssign, context, errorCode);
  }

  /**
   * Validates role assignment using pre-fetched roles (efficient when roles are already available).
   *
   * @param requestingUserRoles List of role maps for the requesting user (already fetched)
   * @param rolesToAssign       List of roles to be assigned/created
   * @param context             Request context for logging and DB access
   * @param errorCode           Error code to use in exception (typically CLIENT_ERROR)
   * @throws ProjectCommonException if user is not authorized to assign the requested roles
   */
  public static void validateRoleAssignmentWithFetchedRoles(
          List<Map<String, Object>> requestingUserRoles,
          List<String> rolesToAssign,
          RequestContext context,
          int errorCode) {

    if (CollectionUtils.isEmpty(rolesToAssign)) {
      logger.info(context, "validateRoleAssignment: No roles to assign, skipping validation");
      return;
    }

    if (CollectionUtils.isEmpty(requestingUserRoles)) {
      logger.error(context,
              "validateRoleAssignment: Requesting user has no roles assigned", null);
      throw new ProjectCommonException(
              ResponseCode.unauthorizedRoleAssignment,
              "You do not have any roles assigned to assign roles to users",
              errorCode);
    }

    List<String> requestingUserRoleNames = requestingUserRoles.stream()
            .map(role -> (String) role.get(JsonKey.ROLE))
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toList());

    logger.info(context,
            "validateRoleAssignment: Requesting user has roles: " + String.join(", ", requestingUserRoleNames));

    if (requestingUserRoleNames.contains(JsonKey.SPV_ADMIN)) {
      logger.info(context, "validateRoleAssignment: SPV_ADMIN can assign any role - validation passed");
      return;
    }

    Map<String, List<String>> roleRestrictionsMap = getRoleRestrictionsFromDB(context);

    if (MapUtils.isEmpty(roleRestrictionsMap)) {
      logger.error(context,
              "validateRoleAssignment: No role restrictions configuration found in system_settings", null);
      throw new ProjectCommonException(
              ResponseCode.unauthorizedRoleAssignment,
              "Role restrictions configuration not found. Please contact administrator.",
              errorCode);
    }

    Set<String> commonRestrictedRoles = null;
    boolean hasRoleWithNoRestrictions = false;

    for (String userRole : requestingUserRoleNames) {
      List<String> restrictedRoles = roleRestrictionsMap.get(userRole);

      if (restrictedRoles == null) {
        logger.info(context,
                "validateRoleAssignment: Role '" + userRole + "' not found in restrictions config");
        continue;
      }

      if (restrictedRoles.isEmpty()) {
        hasRoleWithNoRestrictions = true;
        logger.info(context,
                "validateRoleAssignment: User has role '" + userRole + "' with no restrictions, allowing all role assignments");
        break;
      }

      if (CollectionUtils.isEmpty(commonRestrictedRoles)) {
        commonRestrictedRoles = new HashSet<>(restrictedRoles);
      } else {
        commonRestrictedRoles.retainAll(restrictedRoles);
      }
    }

    if (hasRoleWithNoRestrictions) {
      logger.info(context,
              "validateRoleAssignment: Validation passed - user has role with no restrictions");
      return;
    }

    if (CollectionUtils.isEmpty(commonRestrictedRoles)) {
      logger.error(context,
              "validateRoleAssignment: None of the user's roles found in restrictions configuration", null);
      throw new ProjectCommonException(
              ResponseCode.unauthorizedRoleAssignment,
              "Your roles are not authorized to assign roles. Please contact administrator.",
              errorCode);
    }

    for (String roleToAssign : rolesToAssign) {
      if (commonRestrictedRoles.contains(roleToAssign)) {
        logger.error(context,
                "validateRoleAssignment: BLOCKED - Cannot assign role '" + roleToAssign, null);
        throw new ProjectCommonException(
                ResponseCode.unauthorizedRoleAssignment,
                "You are not authorized to assign role: " + roleToAssign,
                errorCode);
      }
    }

    logger.info(context,
            "validateRoleAssignment: Validation PASSED for user with roles: " + String.join(", ", requestingUserRoleNames));
  }

  private static Map<String, List<String>> getRoleRestrictionsFromDB(RequestContext context) {
    try {
      SystemSetting systemSetting =
              systemSettingDao.readByField(JsonKey.ROLE_RESTRICTIONS, context);

      if (systemSetting == null || StringUtils.isBlank(systemSetting.getValue())) {
        logger.info(context, "getRoleRestrictionsFromDB: roleRestrictions not found in system_settings");
        return Collections.emptyMap();
      }

      Map<String, Object> restrictionsConfig = mapper.readValue(
              systemSetting.getValue(),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
              }
      );

      Map<String, Object> roleRestrictionsObj =
              (Map<String, Object>) restrictionsConfig.get(JsonKey.ROLE_RESTRICTIONS);

      if (MapUtils.isEmpty(roleRestrictionsObj)) {
        return Collections.emptyMap();
      }

      Map<String, List<String>> roleRestrictionsMap = new HashMap<>();
      for (Map.Entry<String, Object> entry : roleRestrictionsObj.entrySet()) {
        List<String> restrictedRoles = (List<String>) entry.getValue();
        roleRestrictionsMap.put(entry.getKey(), restrictedRoles != null ? restrictedRoles : Collections.emptyList());
      }

      return roleRestrictionsMap;

    } catch (Exception e) {
      logger.error(context, "getRoleRestrictionsFromDB: Error reading role restrictions from DB", e);
      return Collections.emptyMap();
    }
  }
}
