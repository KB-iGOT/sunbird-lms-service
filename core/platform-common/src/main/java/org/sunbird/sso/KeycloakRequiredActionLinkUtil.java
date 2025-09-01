package org.sunbird.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.sunbird.http.HttpClientUtil;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

/**
 * Keycloak utility to create required action links.
 *
 * @author Amit Kumar
 */
public class KeycloakRequiredActionLinkUtil {
  private static final LoggerUtil logger = new LoggerUtil(KeycloakRequiredActionLinkUtil.class);
  public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
  public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
  private static final String CLIENT_ID = "clientId";
  private static final String REQUIRED_ACTION = "requiredAction";
  private static final String USERNAME = "userName";
  private static final String EXPIRATION_IN_SEC = "expirationInSecs";
  private static final String REDIRECT_URI = "redirectUri";
  private static final String SUNBIRD_KEYCLOAK_LINK_EXPIRATION_TIME =
      "sunbird_keycloak_required_action_link_expiration_seconds";

  private static ObjectMapper mapper = new ObjectMapper();

  /**
   * Get generated link for specified type and user from Keycloak service.
   *
   * @param userName User name
   * @param requiredAction Type of link to be generated. Supported types are UPDATE_PASSWORD and
   *     VERIFY_EMAIL.
   * @return Generated link from Keycloak service
   */
  public static String getLink(
      String userId,
      String userName,
      String redirectUri,
      String requiredAction,
      RequestContext context) {
    Map<String, String> request = new HashMap<>();

    request.put(CLIENT_ID, ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_CLIENT_ID));
    request.put(USERNAME, userName);
    request.put(REQUIRED_ACTION, requiredAction);

    String expirationInSecs = ProjectUtil.getConfigValue(SUNBIRD_KEYCLOAK_LINK_EXPIRATION_TIME);
    if (StringUtils.isNotBlank(expirationInSecs)) {
      request.put(EXPIRATION_IN_SEC, expirationInSecs);
    }
    request.put(REDIRECT_URI, redirectUri);

    try {
      Thread.sleep(
          Integer.parseInt(ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SYNC_READ_WAIT_TIME)));
      return generateLink(userId, request, context);
    } catch (Exception ex) {
      logger.error(
          context,
          "KeycloakRequiredActionLinkUtil:getLink: Exception occurred with error message = "
              + ex.getMessage(),
          ex);
    }
    return null;
  }

  private static String generateLink(
      String userId, Map<String, String> request, RequestContext context) throws Exception {
    Map<String, String> headers = new HashMap<>();

    headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
    headers.put(
        JsonKey.AUTHORIZATION,
        JsonKey.BEARER + KeycloakUtil.getAdminAccessTokenWithDomain(context));

    // Get federated user ID from username
    String fedUserId = getFederatedUserId(userId);

    System.out.println(
        "KeycloakRequiredActionLinkUtil:generateLink:: User Id: "
            + userId
            + ", Federated User ID: "
            + fedUserId);

    // Build URL for Keycloak 24.0.4 execute-actions-email endpoint
    String url =
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_URL)
            + "admin/realms/"
            + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_RELAM)
            + "/users/"
            + fedUserId
            + "/execute-actions-email";

    // Prepare query parameters
    StringBuilder queryParams = new StringBuilder();
    queryParams.append("?client_id=").append(request.get(CLIENT_ID));
    queryParams.append("&redirect_uri=").append(request.get(REDIRECT_URI));
    if (request.containsKey(EXPIRATION_IN_SEC)) {
      queryParams.append("&lifespan=").append(request.get(EXPIRATION_IN_SEC));
    }

    String completeUrl = url + queryParams.toString();

    logger.info(
        context, "KeycloakRequiredActionLinkUtil:generateLink: complete URL " + completeUrl);

    // Prepare request body with required action as array
    String[] requiredActions = {request.get(REQUIRED_ACTION)};
    String requestBody = mapper.writeValueAsString(requiredActions);

    logger.info(
        context, "KeycloakRequiredActionLinkUtil:generateLink: request body " + requestBody);

    String response = HttpClientUtil.post(completeUrl, requestBody, headers, context);

    logger.info(context, "KeycloakRequiredActionLinkUtil:generateLink: Response = " + response);

    // The new API doesn't return a link, it sends the email directly
    // Return success message or empty string as the method signature expects a String
    return "Email sent successfully";
  }

  /**
   * Helper method to get federated user ID from username
   *
   * @param userId String
   * @return String federated user ID
   */
  private static String getFederatedUserId(String userId) {
    return String.join(
        ":",
        "f",
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_KEYCLOAK_USER_FEDERATION_PROVIDER_ID),
        userId);
  }
}
