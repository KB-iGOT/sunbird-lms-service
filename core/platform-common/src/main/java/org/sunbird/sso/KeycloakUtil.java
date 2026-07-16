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

public class KeycloakUtil {
  private static final LoggerUtil logger = new LoggerUtil(KeycloakUtil.class);

  private KeycloakUtil() {}

  public static String getAdminAccessToken(RequestContext context, String url) throws Exception {
    Map<String, String> headers = new HashMap<>();
    headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED);
    Map<String, String> fields = new HashMap<>();
    fields.put("client_id", ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_CLIENT_ID));
    fields.put("client_secret", ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_CLIENT_SECRET));
    fields.put("grant_type", "client_credentials");
    // logger.info(context, "KeycloakUtil:getAdminAccessToken: url = " + url);
    String response = HttpClientUtil.postFormData(url, fields, headers, context);

    // Check if response is empty or null (indicates an error response)
    if (response == null || response.trim().isEmpty()) {
      Exception ex = new Exception("Empty response from Keycloak token endpoint");
      logger.error(
          context,
          "KeycloakUtil:getAdminAccessToken: Empty or null response from Keycloak token endpoint. URL: "
              + url,
          ex);
      throw new Exception("Failed to get admin access token: Empty response from Keycloak");
    }

    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> responseMap = new ObjectMapper().readValue(response, Map.class);
      String accessToken = (String) responseMap.get("access_token");
      if (accessToken == null || accessToken.trim().isEmpty()) {
        Exception ex = new Exception("No access_token found in response");
        logger.error(
            context,
            "KeycloakUtil:getAdminAccessToken: No access_token found in response. Response: "
                + response,
            ex);
        throw new Exception("Failed to get admin access token: No access_token in response");
      }
      return accessToken;
    } catch (Exception ex) {
      logger.error(
          context,
          "KeycloakUtil:getAdminAccessToken: Error parsing JSON response. Response: " + response,
          ex);
      throw new Exception("Failed to get admin access token: Error parsing response", ex);
    }
  }

  public static String getAdminAccessTokenWithDomain(RequestContext context) throws Exception {
    String url =
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_URL)
            + "realms/"
            + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_RELAM)
            + "/protocol/openid-connect/token";
    String token = getAdminAccessToken(context, url);
    return token;
  }

  public static String getAdminAccessTokenWithoutDomain(RequestContext context) throws Exception {
    String url =
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_LB_IP)
            + "/auth/realms/"
            + ProjectUtil.getConfigValue(JsonKey.SUNBIRD_SSO_RELAM)
            + "/protocol/openid-connect/token";
    return getAdminAccessToken(context, url);
  }
}
