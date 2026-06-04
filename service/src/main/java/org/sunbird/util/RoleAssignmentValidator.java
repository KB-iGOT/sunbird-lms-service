package org.sunbird.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.organisation.Organisation;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.RequestContext;
import org.sunbird.service.organisation.OrgService;
import org.sunbird.service.organisation.impl.OrgServiceImpl;
import org.sunbird.service.systemsettings.SystemSettingsService;
import org.sunbird.service.user.UserRoleService;
import org.sunbird.service.user.impl.UserRoleServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for validating role assignments.
 * Ensures users with _ADMIN roles cannot assign roles and validates role assignments
 * against organization type configuration.
 * Only validates NEW roles being added, not existing roles.
 */
public class RoleAssignmentValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final OrgService orgService = OrgServiceImpl.getInstance();
  private final SystemSettingsService systemSettingsService = new SystemSettingsService();

  /**
   * Validates role assignment - only new roles are validated, not existing ones.
   */
  public void validateRoleAssignment(String requestingUserId, String requestingUserOrgId, String targetOrgId, String targetUserId, List<String> rolesToAssign, RequestContext context) {
    validateRequestingUserRoles(requestingUserId, context);
    List<String> newRoles = getNewRoles(targetUserId, rolesToAssign, context);
    if (CollectionUtils.isNotEmpty(newRoles)) {
      validateRolesAgainstOrgType(requestingUserOrgId, targetOrgId, newRoles, context);
    }
  }

  /**
   * getnew roles that need validation (excludes existing roles if user exists).
   */
  private List<String> getNewRoles(String targetUserId, List<String> requestedRoles, RequestContext context) {
    // For new user creation, all roles are new
    if (StringUtils.isBlank(targetUserId)) {
      return requestedRoles;
    }

    // Fetch existing roles for target user
    List<Map<String, Object>> existingRoles = userRoleService.getUserRoles(targetUserId, null, context);
    List<String> existingRoleNames = existingRoles.stream()
            .map(roleMap -> (String) roleMap.get(JsonKey.ROLE))
            .filter(role -> role != null)
            .collect(Collectors.toList());

    // Return only roles not already assigned
    List<String> newRoles = new ArrayList<>();
    if (CollectionUtils.isNotEmpty(requestedRoles)) {
      newRoles = requestedRoles.stream()
              .filter(role -> !existingRoleNames.contains(role))
              .collect(Collectors.toList());
    }

    return newRoles;
  }

  private void validateRequestingUserRoles(String requestingUserId, RequestContext context) {
    List<Map<String, Object>> requestingUserRoles = userRoleService.getUserRoles(requestingUserId, null, context);

    if (CollectionUtils.isEmpty(requestingUserRoles)) {
      throw new ProjectCommonException(ResponseCode.unAuthorized, "Requesting user has no roles assigned to perform this operation",
              ResponseCode.UNAUTHORIZED.getResponseCode());
    }

    List<String> adminRoles = requestingUserRoles.stream()
            .map(roleMap -> (String) roleMap.get(JsonKey.ROLE))
            .filter(role -> role != null && role.endsWith(JsonKey.ADMIN_SUFFIX))
            .collect(Collectors.toList());

    if (CollectionUtils.isEmpty(adminRoles)) {
      throw new ProjectCommonException(
              ResponseCode.unAuthorized,
              "User is not authorized to create or assign roles. Only users with ADMIN roles can perform this action.",
              ResponseCode.UNAUTHORIZED.getResponseCode()
      );
    }
  }

  private void validateRolesAgainstOrgType(String requestingUserOrgId, String targetOrgId, List<String> rolesToAssign, RequestContext context) {

    Organisation targetOrg = orgService.getOrgObjById(targetOrgId, context);
    if (null == targetOrg) {
      throw new ProjectCommonException(
              ResponseCode.invalidParameterValue,
              "Target organization not found",
              ResponseCode.CLIENT_ERROR.getResponseCode()
      );
    }

    if (!requestingUserOrgId.equalsIgnoreCase(targetOrgId)) {
      if (!requestingUserOrgId.equalsIgnoreCase(targetOrg.getMinistryOrStateId())) {
        throw new ProjectCommonException(
                ResponseCode.unAuthorized,
                "Requesting user does not have authority over the target organization",
                ResponseCode.UNAUTHORIZED.getResponseCode()
        );
      }
    }

    String ministryOrStateType = targetOrg.getMinistryOrStateType();
    if (StringUtils.isBlank(ministryOrStateType)) {
      throw new ProjectCommonException(
              ResponseCode.invalidParameterValue,
              "Target organization does not have a ministryOrStateType defined",
              ResponseCode.CLIENT_ERROR.getResponseCode()
      );
    }

    SystemSetting orgTypeListSetting = systemSettingsService.getSystemSettingByKey(JsonKey.ORG_TYPE_LIST, context);
    if (null == orgTypeListSetting || StringUtils.isBlank(orgTypeListSetting.getValue())) {
      throw new ProjectCommonException(
              ResponseCode.SERVER_ERROR,
              "Organization type configuration not found in system settings",
              ResponseCode.SERVER_ERROR.getResponseCode()
      );
    }

    try {
      Map<String, Object> orgTypeListConfig = objectMapper.readValue(
              orgTypeListSetting.getValue(),
              new TypeReference<Map<String, Object>>() {
              }
      );

      List<Map<String, Object>> orgTypeList = (List<Map<String, Object>>) orgTypeListConfig.get(JsonKey.ORG_TYPE_LIST);

      if (CollectionUtils.isEmpty(orgTypeList)) {
        throw new ProjectCommonException(
                ResponseCode.SERVER_ERROR,
                "Organization type list is empty in system settings",
                ResponseCode.SERVER_ERROR.getResponseCode()
        );
      }

      Map<String, Object> matchingOrgType = orgTypeList.stream()
              .filter(orgType -> {
                String name = (String) orgType.get(JsonKey.NAME);
                return name != null && name.equalsIgnoreCase(ministryOrStateType);
              })
              .findFirst()
              .orElse(null);

      if (null == matchingOrgType) {
        throw new ProjectCommonException(
                ResponseCode.invalidParameterValue,
                String.format("No role configuration found for organization type: %s", ministryOrStateType),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }

      List<String> allowedRoles = (List<String>) matchingOrgType.get(JsonKey.ROLES);

      if (CollectionUtils.isEmpty(allowedRoles)) {
        throw new ProjectCommonException(
                ResponseCode.invalidParameterValue,
                String.format("No roles are defined for organization type: %s", ministryOrStateType),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }

      List<String> invalidRoles = rolesToAssign.stream()
              .filter(role -> !allowedRoles.contains(role))
              .collect(Collectors.toList());

      if (CollectionUtils.isNotEmpty(invalidRoles)) {
        throw new ProjectCommonException(
                ResponseCode.invalidParameterValue,
                String.format("The following roles are not allowed for organization type '%s': %s. Allowed roles are: %s",
                        ministryOrStateType,
                        String.join(", ", invalidRoles),
                        String.join(", ", allowedRoles)),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }
    } catch (Exception e) {
      if (e instanceof ProjectCommonException) {
        throw (ProjectCommonException) e;
      }
      throw new ProjectCommonException(
              ResponseCode.SERVER_ERROR,
              "Error validating roles against organization type: " + e.getMessage(),
              ResponseCode.SERVER_ERROR.getResponseCode()
      );
    }
  }
}

