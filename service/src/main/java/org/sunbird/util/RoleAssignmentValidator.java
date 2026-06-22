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

public class RoleAssignmentValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private final UserRoleService userRoleService = UserRoleServiceImpl.getInstance();
  private final OrgService orgService = OrgServiceImpl.getInstance();
  private final SystemSettingsService systemSettingsService = new SystemSettingsService();

  public void validateRoleAssignment(String requestingUserId, String requestingUserOrgId, String targetOrgId, String targetUserId, List<String> rolesToAssign, RequestContext context) {
    List<String> requestingUserRoles = validateRequestingUserRoles(requestingUserId, context);
    boolean isSpv = false;
    if (requestingUserRoles.contains(JsonKey.SPV_ADMIN)) {
      isSpv = true;
    }

    List<String> newRoles = getNewRoles(targetUserId, rolesToAssign, context);
    Organisation targetOrg = orgService.getOrgObjById(targetOrgId, context);
    if (null == targetOrg) {
      throw new ProjectCommonException(
              ResponseCode.targetOrgNotFound,
              ResponseCode.targetOrgNotFound.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode()
      );
    }
    isAuthorizedForOrg(isSpv,targetOrgId, requestingUserOrgId, targetOrg);

    if (CollectionUtils.isNotEmpty(newRoles)) {
      validateRolesAgainstOrgType(requestingUserOrgId, targetOrgId, newRoles, isSpv, targetOrg, context);
    }
  }

  private List<String> getNewRoles(String targetUserId, List<String> requestedRoles, RequestContext context) {
    // For new user creation, all roles are new
    if (StringUtils.isBlank(targetUserId)) {
      return requestedRoles;
    }

    List<Map<String, Object>> existingRoles = userRoleService.getUserRoles(targetUserId, null, context);
    List<String> existingRoleNames = existingRoles.stream()
            .map(roleMap -> (String) roleMap.get(JsonKey.ROLE))
            .filter(role -> role != null)
            .collect(Collectors.toList());

    List<String> newRoles = new ArrayList<>();
    if (CollectionUtils.isNotEmpty(requestedRoles)) {
      newRoles = requestedRoles.stream()
              .filter(role -> !existingRoleNames.contains(role))
              .collect(Collectors.toList());
    }
    return newRoles;
  }

  private List<String> validateRequestingUserRoles(String requestingUserId, RequestContext context) {
    List<Map<String, Object>> requestingUserRoles = userRoleService.getUserRoles(requestingUserId, null, context);

    if (CollectionUtils.isEmpty(requestingUserRoles)) {
      throw new ProjectCommonException(ResponseCode.userNoRolesAssigned,
              ResponseCode.userNoRolesAssigned.getErrorMessage(),
              ResponseCode.UNAUTHORIZED.getResponseCode());
    }

    List<String> adminRoles = requestingUserRoles.stream()
            .map(roleMap -> (String) roleMap.get(JsonKey.ROLE))
            .filter(role -> role != null && role.endsWith(JsonKey.ADMIN_SUFFIX))
            .collect(Collectors.toList());

    if (CollectionUtils.isEmpty(adminRoles)) {
      throw new ProjectCommonException(
              ResponseCode.userNotAuthorizedAdminRolesRequired,
              ResponseCode.userNotAuthorizedAdminRolesRequired.getErrorMessage(),
              ResponseCode.UNAUTHORIZED.getResponseCode()
      );
    }
    return adminRoles;
  }

  private void isAuthorizedForOrg(boolean isSpv, String targetOrgId, String requestingUserOrgId, Organisation targetOrg) {
    if (!isSpv) {
      if (!requestingUserOrgId.equalsIgnoreCase(targetOrgId)) {
        String ministryOrStateId = targetOrg.getMinistryOrStateId();
        if (StringUtils.isBlank(ministryOrStateId)) {
          throw new ProjectCommonException(
                  ResponseCode.targetOrgNoMinistryStateId,
                  ResponseCode.targetOrgNoMinistryStateId.getErrorMessage(),
                  ResponseCode.CLIENT_ERROR.getResponseCode()
          );
        }
        if (!requestingUserOrgId.equalsIgnoreCase(targetOrg.getMinistryOrStateId())) {
          throw new ProjectCommonException(
                  ResponseCode.userNoAuthorityOverTargetOrg,
                  ResponseCode.userNoAuthorityOverTargetOrg.getErrorMessage(),
                  ResponseCode.UNAUTHORIZED.getResponseCode()
          );
        }
      }
    }
  }

  private void validateRolesAgainstOrgType(String requestingUserOrgId, String targetOrgId, List<String> rolesToAssign, boolean isSpv, Organisation targetOrg, RequestContext context) {
    Integer organisationType = targetOrg.getOrganisationType();
    if (null == organisationType) {
      throw new ProjectCommonException(
              ResponseCode.orgTypeNotFound,
              ResponseCode.orgTypeNotFound.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode()
      );
    }

    SystemSetting orgTypeConfig = systemSettingsService.getSystemSettingByKey(JsonKey.ORG_TYPE_CONFIG, context);
    if (null == orgTypeConfig || StringUtils.isBlank(orgTypeConfig.getValue())) {
      throw new ProjectCommonException(
              ResponseCode.orgTypeConfigNotFound,
              ResponseCode.orgTypeConfigNotFound.getErrorMessage(),
              ResponseCode.SERVER_ERROR.getResponseCode()
      );
    }

    String orgTypeName;
    try {
      Map<String, Object> orgTypeConfigMap = objectMapper.readValue(
              orgTypeConfig.getValue(),
              new TypeReference<Map<String, Object>>() {
              }
      );

      List<Map<String, Object>> fields = (List<Map<String, Object>>) orgTypeConfigMap.get(JsonKey.FIELDS);
      if (CollectionUtils.isEmpty(fields)) {
        throw new ProjectCommonException(
                ResponseCode.orgTypeConfigNotFound,
                ResponseCode.orgTypeConfigNotFound.getErrorMessage(),
                ResponseCode.SERVER_ERROR.getResponseCode()
        );
      }

      Map<String, Object> matchingField = fields.stream()
              .filter(field -> {
                Object value = field.get(JsonKey.VALUE);
                if (value instanceof Integer) {
                  return ((Integer) value).equals(organisationType);
                }
                return false;
              })
              .findFirst()
              .orElse(null);

      if (null == matchingField) {
        throw new ProjectCommonException(
                ResponseCode.orgTypeNotFound,
                String.format(ResponseCode.orgTypeNotFound.getErrorMessage(), organisationType),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }

      orgTypeName = (String) matchingField.get(JsonKey.NAME);
      if (StringUtils.isBlank(orgTypeName)) {
        throw new ProjectCommonException(
                ResponseCode.orgTypeNameNotFound,
                ResponseCode.orgTypeNameNotFound.getErrorMessage(),
                ResponseCode.SERVER_ERROR.getResponseCode()
        );
      }
    } catch (Exception e) {
      if (e instanceof ProjectCommonException) {
        throw (ProjectCommonException) e;
      }
      throw new ProjectCommonException(
              ResponseCode.errorValidatingRolesAgainstOrgType,
              String.format(ResponseCode.errorValidatingRolesAgainstOrgType.getErrorMessage(), e.getMessage()),
              ResponseCode.SERVER_ERROR.getResponseCode()
      );
    }

    SystemSetting orgTypeListSetting = systemSettingsService.getSystemSettingByKey(JsonKey.ORG_TYPE_LIST, context);
    if (null == orgTypeListSetting || StringUtils.isBlank(orgTypeListSetting.getValue())) {
      throw new ProjectCommonException(
              ResponseCode.orgTypeConfigNotFound,
              ResponseCode.orgTypeConfigNotFound.getErrorMessage(),
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
                ResponseCode.orgTypeListEmpty,
                ResponseCode.orgTypeListEmpty.getErrorMessage(),
                ResponseCode.SERVER_ERROR.getResponseCode()
        );
      }

      Map<String, Object> matchingOrgType = orgTypeList.stream()
              .filter(orgType -> {
                String name = (String) orgType.get(JsonKey.NAME);
                return name != null && name.equalsIgnoreCase(orgTypeName);
              })
              .findFirst()
              .orElse(null);

      if (null == matchingOrgType) {
        throw new ProjectCommonException(
                ResponseCode.noRoleConfigForOrgType,
                String.format(ResponseCode.noRoleConfigForOrgType.getErrorMessage(), orgTypeName),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }

      List<String> allowedRoles = (List<String>) matchingOrgType.get(JsonKey.ROLES);

      if (CollectionUtils.isEmpty(allowedRoles)) {
        throw new ProjectCommonException(
                ResponseCode.noRolesDefinedForOrgType,
                String.format(ResponseCode.noRolesDefinedForOrgType.getErrorMessage(), orgTypeName),
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }

      List<String> invalidRoles = rolesToAssign.stream()
              .filter(role -> !allowedRoles.contains(role))
              .collect(Collectors.toList());

      if (CollectionUtils.isNotEmpty(invalidRoles)) {
        String restrictedRoles = String.join(", ", invalidRoles);
        String allowedRolesStr = String.join(", ", allowedRoles);
        String errorMessage = String.format("%s. Invalid: [%s]. Allowed: [%s]",
                ResponseCode.rolesNotAllowedForOrgType.getErrorMessage(),
                restrictedRoles, allowedRolesStr);
        throw new ProjectCommonException(
                ResponseCode.rolesNotAllowedForOrgType,
                errorMessage,
                ResponseCode.CLIENT_ERROR.getResponseCode()
        );
      }
    } catch (Exception e) {
      if (e instanceof ProjectCommonException) {
        throw (ProjectCommonException) e;
      }
      throw new ProjectCommonException(
              ResponseCode.errorValidatingRolesAgainstOrgType,
              String.format(ResponseCode.errorValidatingRolesAgainstOrgType.getErrorMessage(), e.getMessage()),
              ResponseCode.SERVER_ERROR.getResponseCode()
      );
    }
  }

  public void validateRoleAssignmentForSelfRegistration(String targetOrgId, List<String> rolesToAssign, RequestContext context) {
    Organisation targetOrg = orgService.getOrgObjById(targetOrgId, context);
    if (null == targetOrg) {
      throw new ProjectCommonException(
              ResponseCode.targetOrgNotFound,
              ResponseCode.targetOrgNotFound.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode()
      );
    }

    if (CollectionUtils.isNotEmpty(rolesToAssign)) {
      // For self-registration, we only validate roles against org type (no requesting user authority check)
      validateRolesAgainstOrgType(null, targetOrgId, rolesToAssign, false, targetOrg, context);
    }
  }

}
