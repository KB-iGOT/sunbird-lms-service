package org.sunbird.sso.impl;

import static java.util.Arrays.asList;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.TokenVerifier;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.exception.ResponseMessage;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.sso.KeyCloakConnectionProvider;
import org.sunbird.sso.SSOManager;
import org.sunbird.util.ProjectUtil;

/**
 * Single sign out service implementation with Key Cloak.
 *
 * @author Manzarul
 */
public class KeyCloakServiceImpl implements SSOManager {
  private final LoggerUtil logger = new LoggerUtil(KeyCloakServiceImpl.class);
  private final Keycloak keycloak = KeyCloakConnectionProvider.getConnection();

  private static PublicKey SSO_PUBLIC_KEY = null;

  public PublicKey getPublicKey() {
    if (null == SSO_PUBLIC_KEY) {
      SSO_PUBLIC_KEY = toPublicKey(System.getenv(JsonKey.SSO_PUBLIC_KEY));
    }
    return SSO_PUBLIC_KEY;
  }

  @Override
  public String verifyToken(String accessToken, RequestContext context) {
    return verifyToken(accessToken, null, context);
  }

  /**
   * This method will generate Public key form keycloak realm publickey String
   *
   * @param publicKeyString String
   * @return PublicKey
   */
  private PublicKey toPublicKey(String publicKeyString) {
    try {
      byte[] publicBytes = Base64.getDecoder().decode(publicKeyString);
      X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicBytes);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePublic(keySpec);
    } catch (Exception e) {
      return null;
    }
  }

  private static final int UPDATE_PASSWORD_MAX_ATTEMPTS = 3;
  private static final long UPDATE_PASSWORD_RETRY_DELAY_MS = 500;

  @Override
  public boolean updatePassword(String userId, String password, RequestContext context) {
    try {
      String fedUserId = getFederatedUserId(userId);
      System.out.println("KeycloakServiceImpl: fedUserId:: " + fedUserId);
      UserResource ur = keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);

      // Newly created federated users can briefly 404 on lookup right after creation,
      // so retry a few times before giving up.
      UserRepresentation userRep = null;
      Exception lastException = null;
      for (int attempt = 1; attempt <= UPDATE_PASSWORD_MAX_ATTEMPTS; attempt++) {
        try {
          userRep = ur.toRepresentation();
          lastException = null;
          break;
        } catch (Exception e) {
          lastException = e;
          if (attempt < UPDATE_PASSWORD_MAX_ATTEMPTS) {
            logger.info(
                context,
                "updatePassword: attempt "
                    + attempt
                    + " failed to fetch user with fedUserId: "
                    + fedUserId
                    + ", retrying");
            try {
              Thread.sleep(UPDATE_PASSWORD_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      }

      if (lastException != null) {
        logger.error(
            context,
            "updatePassword: User not found with fedUserId: "
                + fedUserId
                + " after "
                + UPDATE_PASSWORD_MAX_ATTEMPTS
                + " attempts",
            lastException);
        return false;
      }

      if (userRep == null) {
        logger.error(
            context,
            "updatePassword: User representation is null for fedUserId: " + fedUserId,
            new Exception("User representation null"));
        return false;
      }

      CredentialRepresentation cr = new CredentialRepresentation();
      cr.setType(CredentialRepresentation.PASSWORD);
      cr.setValue(password);
      cr.setTemporary(false); // Set password as permanent, not temporary

      // For Keycloak 24.0.4, ensure the credential representation is properly configured
      ur.resetPassword(cr);

      logger.info(context, "updatePassword: Password updated successfully for userId: " + userId);
      return true;
    } catch (Exception e) {
      logger.error(
          context, "updatePassword: Exception occurred for userId: " + userId + ", error: ", e);
    }
    return false;
  }

  /**
   * Method to remove the user on basis of user id.
   *
   * @param request Map
   * @param context RequestContext
   * @return boolean true if success otherwise false .
   */
  @Override
  public String removeUser(Map<String, Object> request, RequestContext context) {
    Keycloak keycloak = KeyCloakConnectionProvider.getConnection();
    String userId = (String) request.get(JsonKey.USER_ID);
    try {
      String fedUserId = getFederatedUserId(userId);
      UserResource resource =
          keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);
      if (null != (resource)) {
        resource.remove();
      }
    } catch (Exception ex) {
      logger.error(context, "Error occurred : ", ex);
      String exMsg =
          String.format(ResponseMessage.Message.INVALID_PARAMETER_VALUE, userId, JsonKey.USER_ID);
      ProjectCommonException.throwClientErrorException(ResponseCode.invalidParameterValue, exMsg);
    }
    return JsonKey.SUCCESS;
  }

  /**
   * Method to deactivate the user on basis of user id.
   *
   * @param request Map
   * @param context
   * @return boolean true if success otherwise false .
   */
  @Override
  public String deactivateUser(Map<String, Object> request, RequestContext context) {
    String userId = (String) request.get(JsonKey.USER_ID);
    makeUserActiveOrInactive(userId, false, context);
    return JsonKey.SUCCESS;
  }

  /**
   * Method to activate the user on basis of user id.
   *
   * @param request Map
   * @param context
   * @return boolean true if success otherwise false .
   */
  @Override
  public String activateUser(Map<String, Object> request, RequestContext context) {
    String userId = (String) request.get(JsonKey.USER_ID);
    makeUserActiveOrInactive(userId, true, context);
    return JsonKey.SUCCESS;
  }

  /**
   * This method will take userid and boolean status to update user status
   *
   * @param userId String
   * @param status boolean
   * @throws ProjectCommonException
   */
  private void makeUserActiveOrInactive(String userId, boolean status, RequestContext context) {
    try {
      String fedUserId = getFederatedUserId(userId);
      logger.info(context, "makeUserActiveOrInactive: fedration id formed: " + fedUserId);
      validateUserId(fedUserId);
      Keycloak keycloak = KeyCloakConnectionProvider.getConnection();
      UserResource resource =
          keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);
      UserRepresentation ur = resource.toRepresentation();
      ur.setEnabled(status);
      resource.update(ur);
    } catch (Exception e) {
      logger.error(
          context,
          "makeUserActiveOrInactive:error occurred while blocking or unblocking user: "
              + e.getMessage(),
          e);
      String exMsg =
          String.format(ResponseMessage.Message.INVALID_PARAMETER_VALUE, userId, JsonKey.USER_ID);
      ProjectCommonException.throwClientErrorException(ResponseCode.invalidParameterValue, exMsg);
    }
  }

  /**
   * This method will check userId value, if value is null or empty then it will throw
   * ProjectCommonException
   *
   * @param userId String
   * @throws ProjectCommonException
   */
  private void validateUserId(String userId) {
    if (StringUtils.isBlank(userId)) {
      String exMsg =
          String.format(ResponseMessage.Message.INVALID_PARAMETER_VALUE, userId, JsonKey.USER_ID);
      ProjectCommonException.throwClientErrorException(ResponseCode.invalidParameterValue, exMsg);
    }
  }

  private String getFederatedUserId(String userId) {
    return String.join(
        ":",
        "f",
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_KEYCLOAK_USER_FEDERATION_PROVIDER_ID),
        userId);
  }

  @Override
  public void setRequiredAction(String userId, String requiredAction) {
    try {
      String fedUserId = getFederatedUserId(userId);
      UserResource resource =
          keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);

      // Check if user exists by getting representation
      UserRepresentation userRepresentation = resource.toRepresentation();
      if (userRepresentation == null) {
        throw new Exception("User not found with fedUserId: " + fedUserId);
      }

      userRepresentation.setRequiredActions(asList(requiredAction));
      resource.update(userRepresentation);
    } catch (Exception e) {
      // Log error but don't throw exception to maintain backward compatibility
      System.err.println(
          "setRequiredAction: Exception occurred for userId: "
              + userId
              + ", error: "
              + e.getMessage());
      e.printStackTrace();
    }
  }

  @Override
  public String verifyToken(String accessToken, String url, RequestContext context) {

    try {
      PublicKey publicKey = getPublicKey();
      if (publicKey != null) {
        String ssoUrl = (url != null ? url : KeyCloakConnectionProvider.SSO_URL);
        String issuer = ssoUrl + "realms/" + KeyCloakConnectionProvider.SSO_REALM;

        // Updated for Keycloak 24.0.4 - use newer verification methods
        AccessToken token =
            TokenVerifier.create(accessToken, AccessToken.class)
                .publicKey(publicKey)
                .verify()
                .getToken();

        // Verify issuer manually since realmUrl is deprecated
        if (!issuer.equals(token.getIssuer())) {
          throw new Exception("Token issuer does not match expected realm: " + issuer);
        }

        // Check if token is active and not expired
        if (!token.isActive() || token.isExpired()) {
          throw new Exception("Token is inactive or expired");
        }

        logger.info(
            context,
            token.getId()
                + " "
                + token.getIssuedFor()
                + " "
                + token.getProfile()
                + " "
                + token.getSubject()
                + " Active: "
                + token.isActive()
                + "  isExpired: "
                + token.isExpired()
                + " "
                + token.getExp());
        String tokenSubject = token.getSubject();
        if (StringUtils.isNotBlank(tokenSubject)) {
          int pos = tokenSubject.lastIndexOf(":");
          return tokenSubject.substring(pos + 1);
        }
        return token.getSubject();
      } else {
        logger.info(context, "verifyToken: SSO_PUBLIC_KEY is NULL.");
        throw new ProjectCommonException(
            ResponseCode.serverError,
            ResponseCode.serverError.getErrorMessage(),
            ResponseCode.SERVER_ERROR.getResponseCode());
      }
    } catch (Exception e) {
      logger.error(context, "verifyToken: Exception occurred: ", e);
      throw new ProjectCommonException(
          ResponseCode.unAuthorized,
          ResponseCode.unAuthorized.getErrorMessage(),
          ResponseCode.UNAUTHORIZED.getResponseCode());
    }
  }
}
