package org.sunbird.validator.orgvalidator;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.request.Request;
import org.sunbird.util.ProjectUtil;

public class OrgRequestValidator extends BaseOrgRequestValidator {

  private static final int ERROR_CODE = ResponseCode.CLIENT_ERROR.getResponseCode();
  private final List<String> orgHierarchySearchAllowedRequestFields = List.of(ProjectUtil.getConfigValue(JsonKey.ORG_HIERARCHY_SEARCH_ALLOWED_REQUEST_FIELDS).split(","));
  private final List<String> orgHierarchySearchFiltersAllowedFields = List.of(ProjectUtil.getConfigValue(JsonKey.ORG_HIERARCHY_SEARCH_FILTERS_ALLOWED_FIELDS).split(","));
  private final List<String> orgHierarchySearchAllowedRequestAllFields = Stream.concat(orgHierarchySearchAllowedRequestFields.stream(), Stream.of("id", "userId", "requestedBy")).collect(Collectors.toList());


  public void validateCreateOrgRequest(Request orgRequest) {
    validateParam(
        (String) orgRequest.getRequest().get(JsonKey.ORG_TYPE),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.ORG_TYPE);
    validateParam(
        (String) orgRequest.getRequest().get(JsonKey.ORG_NAME),
        ResponseCode.mandatoryParamsMissing,
        JsonKey.ORG_NAME);

    validateOrgNameAndDescription(orgRequest);

    if (!(orgRequest.getRequest().containsKey(JsonKey.IS_TENANT))
        || (orgRequest.getRequest().containsKey(JsonKey.IS_TENANT)
            && null == orgRequest.getRequest().get(JsonKey.IS_TENANT))) {
      throw new ProjectCommonException(
          ResponseCode.mandatoryParamsMissing,
          MessageFormat.format(
              ResponseCode.mandatoryParamsMissing.getErrorMessage(), JsonKey.IS_TENANT),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }
    validateTenantOrgChannel(orgRequest);
    validateLicense(orgRequest);
    validateLocationIdOrCode(orgRequest);
  }

  private void validateLicense(Request orgRequest) {
    if (orgRequest.getRequest().containsKey(JsonKey.IS_TENANT)
        && (boolean) orgRequest.getRequest().get(JsonKey.IS_TENANT)
        && orgRequest.getRequest().containsKey(JsonKey.LICENSE)
        && StringUtils.isBlank((String) orgRequest.getRequest().get(JsonKey.LICENSE))) {
      throw new ProjectCommonException(
          ResponseCode.invalidParameterValue,
          MessageFormat.format(
              ResponseCode.invalidParameterValue.getErrorMessage(),
              orgRequest.getRequest().get(JsonKey.LICENSE),
              JsonKey.LICENSE),
          ERROR_CODE);
    }
  }

  private void validateOrgNameAndDescription(Request orgRequest) {
    if (orgRequest.getRequest().containsKey(JsonKey.ORG_NAME)) {
      String orgName = (String) orgRequest.getRequest().get(JsonKey.ORG_NAME);
      if (StringUtils.isNotBlank(orgName)) {
          validateOrganizationField(orgName);
          validateFieldLength(orgName, JsonKey.ORG_NAME, StringUtils.isNotBlank(ProjectUtil.getConfigValue(JsonKey.ORG_NAME_MAX_LENGTH))
                  ? Integer.parseInt(ProjectUtil.getConfigValue(JsonKey.ORG_NAME_MAX_LENGTH))
                  : JsonKey.DEFAULT_ORG_NAME_MAX_LENGTH);
      }
    }

    if (orgRequest.getRequest().containsKey(JsonKey.DESCRIPTION)) {
      String description = (String) orgRequest.getRequest().get(JsonKey.DESCRIPTION);
      if (StringUtils.isNotBlank(description)) {
          validateOrganizationField(description);
        int maxLength = StringUtils.isNotBlank(ProjectUtil.getConfigValue(JsonKey.ORG_DESCRIPTION_MAX_LENGTH))
            ? Integer.parseInt(ProjectUtil.getConfigValue(JsonKey.ORG_DESCRIPTION_MAX_LENGTH))
            : JsonKey.DEFAULT_ORG_DESCRIPTION_MAX_LENGTH;
        validateFieldLength(description, JsonKey.DESCRIPTION, maxLength);
      }
    }
  }

  /**
   * Validates the organization name to prevent HTML, JavaScript, or markup content.
   * Allowed characters: alphanumeric, spaces, and common business name characters (&, ., ,, -, ', (, ))
   *
   * @param orgName the organization name to validate
   * @throws ProjectCommonException if the organization name contains invalid characters
   */
  private void validateOrganizationField(String orgName) {
    String patternStr = ProjectUtil.getConfigValue(JsonKey.ORG_FIELD_VALIDATION_PATTERN);
    String pattern = StringUtils.isNotBlank(patternStr) ? patternStr : JsonKey.DEFAULT_ORG_FIELD_PATTERN;

    if (!orgName.matches(pattern)) {
      throw new ProjectCommonException(
          ResponseCode.invalidParameterValue,
          MessageFormat.format(
              ResponseCode.invalidParameterValue.getErrorMessage(),
              orgName) + " - Only alphanumeric characters, spaces are allowed.",
          ERROR_CODE);
    }
  }

  private void validateFieldLength(String fieldValue, String fieldName, int maxLength) {
    if (StringUtils.isNotBlank(fieldValue) && fieldValue.length() > maxLength) {
      throw new ProjectCommonException(
          ResponseCode.invalidParameterValue,
          MessageFormat.format(
              ResponseCode.invalidParameterValue.getErrorMessage(),
              fieldValue,
              fieldName) + " - Maximum allowed length is " + maxLength + " characters",
          ERROR_CODE);
    }
  }

  public void validateUpdateOrgRequest(Request request) {
    validateOrgReference(request);
    validateOrgNameAndDescription(request);

    if (request.getRequest().containsKey(JsonKey.ROOT_ORG_ID)
        && StringUtils.isEmpty((String) request.getRequest().get(JsonKey.ROOT_ORG_ID))) {
      throw new ProjectCommonException(
          ResponseCode.invalidParameterValue,
          String.format(ResponseCode.invalidParameterValue.getErrorMessage(), JsonKey.ROOT_ORG_ID),
          ERROR_CODE);
    }
    if (request.getRequest().get(JsonKey.STATUS) != null) {
      throw new ProjectCommonException(
          ResponseCode.invalidRequestParameter,
          ProjectUtil.formatMessage(
              ResponseCode.invalidRequestParameter.getErrorMessage(), JsonKey.STATUS),
          ERROR_CODE);
    }

    validateTenantOrgChannel(request);
    validateLocationIdOrCode(request);
  }

  public void validateUpdateOrgStatusRequest(Request request) {
    validateOrgReference(request);

    if (!request.getRequest().containsKey(JsonKey.STATUS)) {
      throw new ProjectCommonException(
          ResponseCode.invalidRequestData,
          ResponseCode.invalidRequestData.getErrorMessage(),
          ERROR_CODE);
    }

    if (!(request.getRequest().get(JsonKey.STATUS) instanceof Integer)) {
      throw new ProjectCommonException(
          ResponseCode.invalidRequestData,
          ResponseCode.invalidRequestData.getErrorMessage(),
          ERROR_CODE);
    }
  }

  private void validateLocationIdOrCode(Request orgRequest) {
    validateListParam(orgRequest.getRequest(), JsonKey.LOCATION_IDS, JsonKey.LOCATION_CODE);
    if (orgRequest.getRequest().get(JsonKey.LOCATION_IDS) != null
        && orgRequest.getRequest().get(JsonKey.LOCATION_CODE) != null) {
      ProjectCommonException.throwClientErrorException(
          ResponseCode.errorAttributeConflict,
          MessageFormat.format(
              ResponseCode.errorAttributeConflict.getErrorMessage(),
              JsonKey.LOCATION_CODE,
              JsonKey.LOCATION_IDS));
    }
  }

  public void validateHierarchySearchRequest(Request request) {
      validateSearchRequest(request);

      Object rawRequest = request.getRequest();
      Map<String, Object> requestMap = safeCastToMap(rawRequest, "request.getRequest() must be a Map");
      validateRequestKeys(requestMap, orgHierarchySearchAllowedRequestAllFields);
      if (MapUtils.isNotEmpty(requestMap)) {
          Object filtersObj = requestMap.get(JsonKey.FILTERS);
          if (filtersObj != null) {
              Map<String, Object> filtersMap = safeCastToMap(filtersObj, "filters must be a Map when present");
              validateRequestKeys(filtersMap, orgHierarchySearchFiltersAllowedFields);
          }
      }
  }

  /**
   * Validates that all keys present in requestMap are part of allowedKeys.
   * Throws a single ProjectCommonException listing all invalid keys (if any).
   */
  private void validateRequestKeys(Map<String, Object> requestMap, List<String> allowedKeys) {
      if (requestMap == null) {
          // nothing to validate
          return;
      }
      if (allowedKeys == null || allowedKeys.isEmpty()) {
          throw new IllegalArgumentException("allowedKeys must not be null or empty");
      }

      // use a HashSet for faster contains checks and to avoid case-sensitivity surprises
      Set<String> allowedSet = new HashSet<>(allowedKeys);

      // collect invalid keys
      List<String> invalidKeys = requestMap.keySet().stream()
              .filter(k -> k == null || !allowedSet.contains(k))
              .map(String::valueOf)
              .collect(Collectors.toList());

      if (!invalidKeys.isEmpty()) {
          String msg = ResponseCode.invalidRequestData.getErrorMessage()
                  + " - invalid key(s): " + String.join(", ", invalidKeys);
          throw new ProjectCommonException(ResponseCode.invalidRequestData, msg, ERROR_CODE);
      }
  }

  /** Helper to safely cast an object to Map<String, Object> with a better error message. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> safeCastToMap(Object obj, String errorMessage) {
      if (obj == null) return null;
      if (!(obj instanceof Map)) {
          throw new ProjectCommonException(
                  ResponseCode.invalidRequestData,
                  ResponseCode.invalidRequestData.getErrorMessage() + " " + errorMessage,
                  ERROR_CODE);
      }
      try {
          return (Map<String, Object>) obj;
      } catch (ClassCastException e) {
          throw new ProjectCommonException(
                  ResponseCode.invalidRequestData,
                  ResponseCode.invalidRequestData.getErrorMessage() + " " + errorMessage,
                  ERROR_CODE);
      }
  }
}
