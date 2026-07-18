package org.sunbird.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import org.apache.http.HttpHeaders;
import org.sunbird.http.HttpClientUtil;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

public class KeycloakBruteForceAttackUtil {
  private static final LoggerUtil logger = new LoggerUtil(KeycloakBruteForceAttackUtil.class);

  private KeycloakBruteForceAttackUtil() {}

  private static String fedUserPrefix =
      "f:" + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_KEYCLOAK_USER_FEDERATION_PROVIDER_ID) + ":";
  /**
   * Get status of a user in brute force detection
   *
   * @param userId
   * @return
   */
  public static boolean isUserAccountDisabled(String userId, RequestContext context)
      throws Exception {
    String url =
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_LB_IP)
            + "/auth/admin/realms/"
            + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_RELAM)
            + "/attack-detection/brute-force/users/"
            + fedUserPrefix
            + userId;
    String response = HttpClientUtil.get(url, getHeaders(context), context);
    logger.info(context, "KeycloakBruteForceAttackUtil:getUserStatus: Response = " + response);

    // Check if response is empty or null (indicates an error response)
    if (response == null || response.trim().isEmpty()) {
      return false;
    }

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> attackStatus = new ObjectMapper().readValue(response, Map.class);
      boolean isDisabled = ((boolean) attackStatus.get("disabled"));
      if (isDisabled) {
        logger.info(context, "check attack detection for userId : " + userId + ", " + attackStatus);
      }
      return isDisabled;
    } catch (Exception ex) {
      logger.error(
          context,
          "KeycloakBruteForceAttackUtil:isUserAccountDisabled: Error parsing JSON response for userId: "
              + userId
              + ". Response: "
              + response,
          ex);
      // In case of JSON parsing error, assume user is not disabled to allow the reset password flow
      // to continue
      return false;
    }
  }

  /**
   * @param userId
   * @param context
   * @return
   */
  public static boolean unlockTempDisabledUser(String userId, RequestContext context)
      throws Exception {
    String url =
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_LB_IP)
            + "/auth/admin/realms/"
            + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_RELAM)
            + "/attack-detection/brute-force/users/"
            + fedUserPrefix
            + userId;
    try {
      String response = HttpClientUtil.delete(url, getHeaders(context), context);
      logger.info(
          context, "clear Brute Force For User for userId : " + userId + ", response: " + response);
      return true;
    } catch (Exception ex) {
      logger.error(
          context,
          "KeycloakBruteForceAttackUtil:unlockTempDisabledUser: Error clearing brute force attack for userId: "
              + userId,
          ex);
      // Return false to indicate the operation failed
      return false;
    }
  }

  private static Map<String, String> getHeaders(RequestContext context) throws Exception {
    Map<String, String> headers = new HashMap<>();
    headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
    headers.put(
        JsonKey.AUTHORIZATION,
        JsonKey.BEARER + KeycloakUtil.getAdminAccessTokenWithoutDomain(context));
    return headers;
  }
}
