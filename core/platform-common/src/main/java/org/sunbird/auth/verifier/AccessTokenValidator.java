package org.sunbird.auth.verifier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.common.util.Time;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;

public class AccessTokenValidator {
  private static final LoggerUtil logger = new LoggerUtil(AccessTokenValidator.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String sso_url = System.getenv(JsonKey.SUNBIRD_SSO_URL);
  private static final String realm = System.getenv(JsonKey.SUNBIRD_SSO_RELAM);

  private static Map<String, Object> validateToken(String token, Map<String, Object> requestContext)
      throws JsonProcessingException {
    logger.info("validateToken: Starting token validation for keycloak24, request context: " + requestContext);
    logger.info("validateToken: SSO URL: " + sso_url + ", Realm: " + realm);
    
    String[] tokenElements = token.split("\\.");
    logger.info("validateToken: Token split into " + tokenElements.length + " parts");
    
    String header = tokenElements[0];
    logger.info("validateToken: Header component length: " + (header != null ? header.length() : 0));
    
    String body = tokenElements[1];
    logger.info("validateToken: Body component length: " + (body != null ? body.length() : 0));
    
    String signature = tokenElements[2];
    logger.info("validateToken: Signature component length: " + (signature != null ? signature.length() : 0));
    
    String payLoad = header + JsonKey.DOT_SEPARATOR + body;
    logger.info("validateToken: Combined payload length: " + (payLoad != null ? payLoad.length() : 0));
    
    logger.info("validateToken: Decoding header for keycloak24 compatibility");
    Map<Object, Object> headerData =
        mapper.readValue(new String(decodeFromBase64(header)), Map.class);
    logger.info("validateToken: Header data parsed successfully: " + headerData);
    
    String keyId = headerData.get("kid").toString();
    logger.info("Key ID: " + keyId);
    
    logger.info("validateToken: Attempting RSA signature verification with keyId: " + keyId);
    boolean isValid =
        CryptoUtil.verifyRSASign(
            payLoad,
            decodeFromBase64(signature),
            KeyManager.getPublicKey(keyId).getPublicKey(),
            JsonKey.SHA_256_WITH_RSA,
            requestContext);
    logger.info("validateToken: RSA signature verification result: " + isValid);
    
    if (isValid) {
      logger.info("validateToken: Signature valid, parsing token body for keycloak24");
      Map<String, Object> tokenBody =
          mapper.readValue(new String(decodeFromBase64(body)), Map.class);
      logger.info("validateToken: Token body parsed successfully. Claims: " + tokenBody.keySet());
      
      Integer expiration = (Integer) tokenBody.get("exp");
      logger.info("validateToken: Token expiration: " + expiration + ", Current time: " + Time.currentTime());
      
      boolean isExp = isExpired(expiration);
      logger.info("validateToken: Token expired check: " + isExp);
      
      if (isExp) {
        logger.info("Token is expired " + token + ", request context data :" + requestContext);
        return Collections.EMPTY_MAP;
      }
      logger.info("validateToken: Token validation successful for keycloak24");
      return tokenBody;
    }
    logger.info("validateToken: Token validation failed - invalid signature");
    return Collections.EMPTY_MAP;
  }

  /**
   * managedtoken is validated and requestedByUserID, requestedForUserID values are validated
   * aganist the managedEncToken
   *
   * @param managedEncToken
   * @param requestedByUserId
   * @param requestedForUserId
   * @return
   */
  public static String verifyManagedUserToken(
      String managedEncToken,
      String requestedByUserId,
      String requestedForUserId,
      Map<String, Object> requestContext) {
    logger.info("verifyManagedUserToken: Starting managed token verification for keycloak24");
    logger.info("verifyManagedUserToken: requestedByUserId: " + requestedByUserId + ", requestedForUserId: " + requestedForUserId);
    
    String managedFor = JsonKey.UNAUTHORIZED;
    try {
      Map<String, Object> payload = validateToken(managedEncToken, requestContext);
      logger.info("verifyManagedUserToken: Token validation completed, payload empty: " + MapUtils.isEmpty(payload));
      
      if (MapUtils.isNotEmpty(payload)) {
        String parentId = (String) payload.get(JsonKey.PARENT_ID);
        String muaId = (String) payload.get(JsonKey.SUB);
        logger.info(
            "AccessTokenValidator: parent uuid: "
                + parentId
                + " managedBy uuid: "
                + muaId
                + " requestedByUserID: "
                + requestedByUserId
                + " requestedForUserId: "
                + requestedForUserId
                + " request context data : "
                + requestContext);
        boolean isValid =
            parentId.equalsIgnoreCase(requestedByUserId)
                && muaId.equalsIgnoreCase(requestedForUserId);
        logger.info("verifyManagedUserToken: Validation result: " + isValid);
        
        if (isValid) {
          managedFor = muaId;
          logger.info("verifyManagedUserToken: Successfully validated managed token for user: " + muaId);
        } else {
          logger.info("verifyManagedUserToken: Validation failed - user IDs do not match expected values");
        }
      } else {
        logger.info("verifyManagedUserToken: Validation failed - empty payload from token validation");
      }
    } catch (Exception ex) {
      logger.error(
          "Exception in verifyManagedUserToken: Token : "
              + managedEncToken
              + ", request context data :"
              + requestContext,
          ex);
    }
    logger.info("verifyManagedUserToken: Final result: " + managedFor);
    return managedFor;
  }

  public static String verifyUserToken(String token, Map<String, Object> requestContext) {
    logger.info("verifyUserToken: Starting user token verification for keycloak24 upgrade");
    
    String userId = JsonKey.UNAUTHORIZED;
    try {
      Map<String, Object> payload = validateToken(token, requestContext);
      logger.info(
          "learner access token validateToken() :"
              + payload.toString()
              + ", request context data : "
              + requestContext);

      logger.info("verifyUserToken:: Payload: " + payload);
      
      if (MapUtils.isNotEmpty(payload)) {
        String issuer = (String) payload.get("iss");
        logger.info("verifyUserToken: Token issuer: " + issuer);
        boolean issuerValid = checkIss(issuer);
        logger.info("verifyUserToken: Issuer validation result: " + issuerValid);
        
        if (issuerValid) {
          userId = (String) payload.get(JsonKey.SUB);
          logger.info("verifyUserToken:: Raw User ID from token: " + userId);
          
          if (StringUtils.isNotBlank(userId)) {
            int pos = userId.lastIndexOf(":");
            if (pos >= 0) {
              String extractedUserId = userId.substring(pos + 1);
              logger.info("verifyUserToken: Extracted user ID: " + extractedUserId + " from position: " + pos);
              userId = extractedUserId;
            }
          }
          logger.info("verifyUserToken:: Final User ID: " + userId);
        } else {
          logger.info("verifyUserToken: Invalid issuer, authentication failed");
        }
      } else {
        logger.info("verifyUserToken: Empty payload from token validation");
      }
    } catch (Exception ex) {
      logger.error(
          "Exception in verifyUserAccessToken: Token : "
              + token
              + ", request context data : "
              + requestContext,
          ex);
    }
    if (JsonKey.UNAUTHORIZED.equalsIgnoreCase(userId)) {
      logger.info(
          "verifyUserAccessToken: Invalid User Token: "
              + token
              + ", request context data : "
              + requestContext);
    } else {
      logger.info("verifyUserToken: Successfully verified user token for userId: " + userId);
    }
    return userId;
  }

  public static String verifySourceUserToken(
      String token, String url, Map<String, Object> requestContext) {
    logger.info("verifySourceUserToken: Starting source user token verification with URL: " + url);
    
    String userId = JsonKey.UNAUTHORIZED;
    try {
      Map<String, Object> payload = validateToken(token, requestContext);
      logger.info(
          "verifySourceUserToken: Token validation completed, payload size: " + (MapUtils.isNotEmpty(payload) ? payload.size() : 0));
      
      if (MapUtils.isNotEmpty(payload)) {
        String issuer = (String) payload.get("iss");
        logger.info("verifySourceUserToken: Token issuer: " + issuer + ", provided URL: " + url);
        
        boolean issuerValid = checkSourceIss(issuer, url);
        logger.info("verifySourceUserToken: Source issuer validation result: " + issuerValid);
        
        if (issuerValid) {
          userId = (String) payload.get(JsonKey.SUB);
          logger.info("verifySourceUserToken: Raw user ID: " + userId);
          
          if (StringUtils.isNotBlank(userId)) {
            int pos = userId.lastIndexOf(":");
            if (pos >= 0) {
              String extractedUserId = userId.substring(pos + 1);
              logger.info("verifySourceUserToken: Extracted user ID: " + extractedUserId);
              userId = extractedUserId;
            }
          }
          logger.info("verifySourceUserToken: Final user ID: " + userId);
        }
      }
    } catch (Exception ex) {
      logger.error(
          "Exception in verifySourceUserToken: Token : "
              + token
              + ", request context data : "
              + requestContext,
          ex);
    }
    if (JsonKey.UNAUTHORIZED.equalsIgnoreCase(userId)) {
      logger.info(
          "verifySourceUserToken: Invalid source user Token: "
              + token
              + ", request context data : "
              + requestContext);
    } else {
      logger.info("verifySourceUserToken: Successfully verified source user token for userId: " + userId);
    }
    return userId;
  }

  private static boolean checkIss(String iss) {
    String realmUrl = sso_url + "realms/" + realm;
    logger.info("checkIss:: Realm URL: " + realmUrl + "|| iss: " + iss);
    return (realmUrl.equalsIgnoreCase(iss));
  }

  private static boolean checkSourceIss(String iss, String url) {
    String ssoUrl = (url != null ? url : sso_url);
    logger.info("checkSourceIss: Using SSO URL: " + ssoUrl + " (provided URL: " + url + ", default: " + sso_url + ")");
    
    String realmUrl = ssoUrl + "realms/" + realm;
    logger.info("checkSourceIss: Constructed realm URL: " + realmUrl + ", comparing with issuer: " + iss);
    
    boolean result = (realmUrl.equalsIgnoreCase(iss));
    logger.info("checkSourceIss: Source issuer validation result: " + result);
    
    return result;
  }

  private static boolean isExpired(Integer expiration) {
    int currentTime = Time.currentTime();
    boolean expired = (currentTime > expiration);
    logger.info("isExpired: Current time: " + currentTime + ", Token expiration: " + expiration + ", Expired: " + expired);
    return expired;
  }

  private static byte[] decodeFromBase64(String data) {
    logger.info("decodeFromBase64: Decoding data of length: " + (data != null ? data.length() : 0));
    try {
      byte[] decoded = Base64Util.decode(data, 11);
      logger.info("decodeFromBase64: Successfully decoded to " + (decoded != null ? decoded.length : 0) + " bytes");
      return decoded;
    } catch (Exception e) {
      logger.error("decodeFromBase64: Failed to decode base64 data", e);
      throw e;
    }
  }
}
