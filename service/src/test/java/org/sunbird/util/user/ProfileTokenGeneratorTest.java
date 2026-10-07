package org.sunbird.util.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
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
import org.sunbird.keys.JsonKey;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ProjectUtil.class})
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
public class ProfileTokenGeneratorTest {

  private static final String SECRET = "unit-test-profile-token-secret-value-01";
  private static final String OTHER_SECRET = "a-completely-different-secret-value-002";
  private static final String USER_ID = "b1e9f3c4-0d6a-4f5e-9c2b-7a8d5e1f0a3b";

  private final RequestContext context = new RequestContext();

  @Before
  public void setUp() {
    PowerMockito.mockStatic(ProjectUtil.class);
    configureSecret(SECRET);
    // The generator caches the derived key in a static field; clear it so each test derives from
    // the secret that test configured.
    clearCachedKey();
  }

  @Test
  public void generateReturnsTokenSignedWithConfiguredSecret() {
    String token = ProfileTokenGenerator.generate(fullProfile(), USER_ID, context);

    Assert.assertTrue(StringUtils.isNotBlank(token));
    Jws<Claims> jws = parse(token, SECRET);
    Assert.assertEquals(SignatureAlgorithm.HS256.getValue(), jws.getHeader().getAlgorithm());
    Assert.assertEquals(JsonKey.PROFILE_TOKEN_ISSUER, jws.getBody().getIssuer());
    Assert.assertNotNull(jws.getBody().getIssuedAt());
  }

  @Test
  public void generatePopulatesClaimsFromPersonalAndProfessionalDetails() {
    Claims claims = claimsOf(ProfileTokenGenerator.generate(fullProfile(), USER_ID, context));

    Assert.assertEquals("VERIFIED", claims.get(JsonKey.PROFILE_TOKEN_CLAIM_PROFILE_STATUS));
    Assert.assertEquals("IAS", claims.get(JsonKey.SERVICE));
    Assert.assertEquals("2011", claims.get(JsonKey.BATCH));
    Assert.assertEquals("Karnataka", claims.get(JsonKey.CADRE));
    Assert.assertEquals("Deputy Secretary", claims.get(JsonKey.DESIGNATION));
    Assert.assertEquals(USER_ID, claims.get(JsonKey.USER));
    Assert.assertEquals("root-org-001", claims.get(JsonKey.ROOTORG_ID));
    Assert.assertEquals("Group A", claims.get(JsonKey.GROUP));
    Assert.assertEquals("ministry-001", claims.get(JsonKey.MINISTRY_STATE_ID));
    Assert.assertEquals("ministry", claims.get(JsonKey.MINISTRY_STATE_TYPE));
  }

  @Test
  public void generateFallsBackToCadreDetailsWhenPersonalDetailsAreBlank() {
    Map<String, Object> profile = fullProfile();
    profileDetails(profile).put(JsonKey.PERSONAL_DETAILS, new HashMap<String, Object>());

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals("Indian Administrative Service", claims.get(JsonKey.SERVICE));
    Assert.assertEquals("2010", claims.get(JsonKey.BATCH));
    Assert.assertEquals("Kerala", claims.get(JsonKey.CADRE));
  }

  @Test
  public void generateFallsBackToRootOrgForMinistryState() {
    Map<String, Object> profile = fullProfile();
    profileDetails(profile).remove(JsonKey.MINISTRY_STATE_ID);
    profileDetails(profile).remove(JsonKey.MINISTRY_STATE_TYPE);

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals("root-org-ministry-009", claims.get(JsonKey.MINISTRY_STATE_ID));
    Assert.assertEquals("state", claims.get(JsonKey.MINISTRY_STATE_TYPE));
  }

  @Test
  public void generateTakesDesignationFromTheFirstProfessionalDetailsEntry() {
    Map<String, Object> profile = fullProfile();
    Map<String, Object> second = new HashMap<>();
    second.put(JsonKey.DESIGNATION, "Under Secretary");
    second.put(JsonKey.GROUP, "Group B");
    ((List<Map<String, Object>>) profileDetails(profile).get(JsonKey.PROFESSIONAL_DETAILS))
        .add(second);

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals("Deputy Secretary", claims.get(JsonKey.DESIGNATION));
    Assert.assertEquals("Group A", claims.get(JsonKey.GROUP));
  }

  @Test
  public void generatePrefersTheUserIdArgumentOverTheProfileUserId() {
    Map<String, Object> profile = fullProfile();
    profile.put(JsonKey.USER_ID, "user-id-from-profile");

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals(USER_ID, claims.get(JsonKey.USER));
  }

  @Test
  public void generateFallsBackToProfileUserIdWhenUserIdArgumentIsBlank() {
    Map<String, Object> profile = fullProfile();
    profile.put(JsonKey.USER_ID, "user-id-from-profile");

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, StringUtils.EMPTY, context));

    Assert.assertEquals("user-id-from-profile", claims.get(JsonKey.USER));
  }

  @Test
  public void generateReadsRoleNamesFromRoleObjects() {
    Map<String, Object> profile = fullProfile();
    profile.put(JsonKey.ROLES, Arrays.asList(role("PUBLIC"), role("CONTENT_CREATOR")));

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals(Arrays.asList("PUBLIC", "CONTENT_CREATOR"), claims.get(JsonKey.ROLES));
  }

  @Test
  public void generateReadsPlainRoleNamesAndSkipsBlankEntries() {
    Map<String, Object> profile = fullProfile();
    profile.put(JsonKey.ROLES, Arrays.asList("PUBLIC", StringUtils.EMPTY, "MDO_ADMIN"));

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals(Arrays.asList("PUBLIC", "MDO_ADMIN"), claims.get(JsonKey.ROLES));
  }

  @Test
  public void generateEmitsEmptyRolesWhenRolesAreAbsent() {
    Map<String, Object> profile = fullProfile();
    profile.remove(JsonKey.ROLES);

    Claims claims = claimsOf(ProfileTokenGenerator.generate(profile, USER_ID, context));

    Assert.assertEquals(new ArrayList<String>(), claims.get(JsonKey.ROLES));
  }

  /** A user read can legitimately return a profile with none of the optional sections filled in. */
  @Test
  public void generateSucceedsForProfileWithoutAnyDetails() {
    Map<String, Object> profile = new HashMap<>();
    profile.put(JsonKey.USER_ID, USER_ID);

    String token = ProfileTokenGenerator.generate(profile, USER_ID, context);

    Assert.assertTrue(StringUtils.isNotBlank(token));
    Claims claims = claimsOf(token);
    Assert.assertEquals(USER_ID, claims.get(JsonKey.USER));
    Assert.assertEquals(StringUtils.EMPTY, claims.get(JsonKey.DESIGNATION));
    Assert.assertEquals(StringUtils.EMPTY, claims.get(JsonKey.ROOTORG_ID));
    Assert.assertEquals(new ArrayList<String>(), claims.get(JsonKey.ROLES));
  }

  @Test
  public void generateReturnsNullWhenSecretIsNotConfigured() {
    configureSecret(null);
    clearCachedKey();

    Assert.assertNull(ProfileTokenGenerator.generate(fullProfile(), USER_ID, context));
  }

  @Test
  public void generateReturnsNullWhenSecretIsShorterThanTheMinimumLength() {
    configureSecret(StringUtils.repeat("x", JsonKey.PROFILE_TOKEN_MIN_SECRET_LENGTH - 1));
    clearCachedKey();

    Assert.assertNull(ProfileTokenGenerator.generate(fullProfile(), USER_ID, context));
  }

  @Test(expected = SignatureException.class)
  public void tokenDoesNotVerifyUnderADifferentSecret() {
    parse(ProfileTokenGenerator.generate(fullProfile(), USER_ID, context), OTHER_SECRET);
  }

  @Test(expected = SignatureException.class)
  public void tamperedClaimsBreakTheSignature() {
    String token = ProfileTokenGenerator.generate(fullProfile(), USER_ID, context);
    String[] segments = token.split("\\.");
    String claims =
        new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8)
            .replace(USER_ID, "11111111-2222-3333-4444-555555555555");
    String forged =
        segments[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8))
            + "."
            + segments[2];

    parse(forged, SECRET);
  }

  /** An `alg: none` token carries no signature at all and must never be accepted as a JWS. */
  @Test(expected = JwtException.class)
  public void unsignedTokenIsNotAcceptedAsAJws() {
    String unsigned =
        Jwts.builder()
            .setIssuer(JsonKey.PROFILE_TOKEN_ISSUER)
            .claim(JsonKey.USER, USER_ID)
            .compact();

    parse(unsigned, SECRET);
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

  private static Claims claimsOf(String token) {
    return parse(token, SECRET).getBody();
  }

  /** Verifies the token the way a consumer would: with the key derived from the same secret. */
  private static Jws<Claims> parse(String token, String secret) {
    try {
      byte[] keyBytes =
          MessageDigest.getInstance(JsonKey.PROFILE_TOKEN_KEY_DIGEST)
              .digest(secret.getBytes(StandardCharsets.UTF_8));
      SecretKey key = new SecretKeySpec(keyBytes, JsonKey.PROFILE_TOKEN_HMAC_ALGORITHM);
      return Jwts.parser().setSigningKey(key).parseClaimsJws(token);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, Object> role(String name) {
    Map<String, Object> role = new HashMap<>();
    role.put(JsonKey.ROLE, name);
    return role;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> profileDetails(Map<String, Object> profile) {
    return (Map<String, Object>) profile.get(JsonKey.PROFILE_DETAILS);
  }

  private static Map<String, Object> fullProfile() {
    Map<String, Object> personalDetails = new HashMap<>();
    personalDetails.put(JsonKey.SERVICE_TYPE, "IAS");
    personalDetails.put(JsonKey.BATCH, "2011");
    personalDetails.put(JsonKey.CADRE, "Karnataka");

    Map<String, Object> cadreDetails = new HashMap<>();
    cadreDetails.put(JsonKey.CIVIL_SERVICE_NAME, "Indian Administrative Service");
    cadreDetails.put(JsonKey.CADRE_BATCH, "2010");
    cadreDetails.put(JsonKey.CADRE_NAME, "Kerala");

    Map<String, Object> professionalDetails = new HashMap<>();
    professionalDetails.put(JsonKey.DESIGNATION, "Deputy Secretary");
    professionalDetails.put(JsonKey.GROUP, "Group A");

    Map<String, Object> profileDetails = new HashMap<>();
    profileDetails.put(JsonKey.PROFILE_STATUS, "VERIFIED");
    profileDetails.put(JsonKey.PERSONAL_DETAILS, personalDetails);
    profileDetails.put(JsonKey.CADRE_DETAILS, cadreDetails);
    profileDetails.put(
        JsonKey.PROFESSIONAL_DETAILS, new ArrayList<>(Arrays.asList(professionalDetails)));
    profileDetails.put(JsonKey.MINISTRY_STATE_ID, "ministry-001");
    profileDetails.put(JsonKey.MINISTRY_STATE_TYPE, "ministry");

    Map<String, Object> rootOrg = new HashMap<>();
    rootOrg.put(JsonKey.MINISTRY_STATE_ID, "root-org-ministry-009");
    rootOrg.put(JsonKey.MINISTRY_STATE_TYPE, "state");

    Map<String, Object> profile = new HashMap<>();
    profile.put(JsonKey.USER_ID, USER_ID);
    profile.put(JsonKey.ROOT_ORG_ID, "root-org-001");
    profile.put(JsonKey.PROFILE_DETAILS, profileDetails);
    profile.put(JsonKey.ROOT_ORG, rootOrg);
    profile.put(JsonKey.ROLES, Arrays.asList(role("PUBLIC")));
    return profile;
  }
}
