package org.sunbird.service.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.keys.JsonKey;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.user.ProfileTokenGenerator;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers addProfileToken, which attaches the profileToken to the user read response. The surrounding
 * read path needs a full Cassandra/ES harness, so the method is exercised directly.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ProjectUtil.class, ServiceFactory.class})
@PowerMockIgnore({
  "javax.management.*",
  "javax.net.ssl.*",
  "javax.security.*",
  "jdk.internal.reflect.*",
  "javax.crypto.*",
  // jjwt base64-encodes through javax.xml.bind.DatatypeConverter; loading the javax.xml classes
  // in PowerMock's classloader breaks their access to the JDK's java.xml module internals.
  "javax.xml.*",
  "org.xml.*",
  "org.w3c.dom.*"
})
public class UserProfileReadServiceTest {

  private static final String SECRET = "unit-test-profile-token-secret-value-01";
  private static final String USER_ID = "3348dc18-9980-4850-8073-75e18639140d";

  private final RequestContext context = new RequestContext();

  @Before
  public void setUp() {
    PowerMockito.mockStatic(ProjectUtil.class);
    PowerMockito.mockStatic(ServiceFactory.class);
    configureSecret(SECRET);
    clearCachedKey();
  }

  @Test
  public void addProfileTokenPutsASignedTokenOnTheReadResponse() throws Exception {
    Map<String, Object> result = readResponse();

    addProfileToken(result);

    String profileToken = (String) result.get(JsonKey.PROFILE_TOKEN);
    Assert.assertTrue(StringUtils.isNotBlank(profileToken));
    Claims claims = parse(profileToken);
    Assert.assertEquals(USER_ID, claims.get(JsonKey.USER));
    Assert.assertEquals("ACCOUNTANT", claims.get(JsonKey.DESIGNATION));
    Assert.assertEquals(
        Arrays.asList("COMMUNITY_MODERATOR", "MDO_LEADER", "PUBLIC"), claims.get(JsonKey.ROLES));
  }

  /** The rest of the profile must still be returned when the token cannot be built. */
  @Test
  public void addProfileTokenLeavesTheResponseUntouchedWhenTheSecretIsNotConfigured()
      throws Exception {
    configureSecret(null);
    clearCachedKey();
    Map<String, Object> result = readResponse();

    addProfileToken(result);

    Assert.assertFalse(result.containsKey(JsonKey.PROFILE_TOKEN));
    Assert.assertEquals(USER_ID, result.get(JsonKey.USER_ID));
  }

  private void addProfileToken(Map<String, Object> result) throws Exception {
    // The constructor wires up every DAO singleton; addProfileToken uses none of them, so skip it.
    UserProfileReadService service = Whitebox.newInstance(UserProfileReadService.class);
    Whitebox.invokeMethod(service, "addProfileToken", result, USER_ID, context);
  }

  private static void configureSecret(String secret) {
    PowerMockito.when(ProjectUtil.getConfigValue(JsonKey.PROFILE_TOKEN_KEY)).thenReturn(secret);
  }

  @SuppressWarnings("unchecked")
  private static void clearCachedKey() {
    AtomicReference<SecretKey> cachedKey =
        Whitebox.getInternalState(ProfileTokenGenerator.class, "TOKEN_KEY");
    cachedKey.set(null);
  }

  private static Claims parse(String token) {
    try {
      byte[] keyBytes =
          MessageDigest.getInstance(JsonKey.PROFILE_TOKEN_KEY_DIGEST)
              .digest(SECRET.getBytes(StandardCharsets.UTF_8));
      SecretKey key = new SecretKeySpec(keyBytes, JsonKey.PROFILE_TOKEN_HMAC_ALGORITHM);
      return Jwts.parser().setSigningKey(key).parseClaimsJws(token).getBody();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Shaped after an actual /v2/user/read response. */
  private static Map<String, Object> readResponse() {
    Map<String, Object> personalDetails = new HashMap<>();
    personalDetails.put(JsonKey.FIRST_NAME, "User One");

    Map<String, Object> professionalDetails = new HashMap<>();
    professionalDetails.put(JsonKey.DESIGNATION, "ACCOUNTANT");
    professionalDetails.put(JsonKey.GROUP, "Group A");

    Map<String, Object> profileDetails = new HashMap<>();
    profileDetails.put(JsonKey.PROFILE_STATUS, "VERIFIED");
    profileDetails.put(JsonKey.PERSONAL_DETAILS, personalDetails);
    profileDetails.put(
        JsonKey.PROFESSIONAL_DETAILS, new ArrayList<>(Arrays.asList(professionalDetails)));
    profileDetails.put(JsonKey.MINISTRY_STATE_ID, "0135071359030722569");

    Map<String, Object> rootOrg = new HashMap<>();
    rootOrg.put(JsonKey.MINISTRY_STATE_TYPE, "SPV");

    Map<String, Object> result = new HashMap<>();
    result.put(JsonKey.USER_ID, USER_ID);
    result.put(JsonKey.ROOT_ORG_ID, "0137972066730393602019");
    result.put(JsonKey.PROFILE_DETAILS, profileDetails);
    result.put(JsonKey.ROOT_ORG, rootOrg);
    result.put(
        JsonKey.ROLES, (List<String>) Arrays.asList("COMMUNITY_MODERATOR", "MDO_LEADER", "PUBLIC"));
    return result;
  }
}
