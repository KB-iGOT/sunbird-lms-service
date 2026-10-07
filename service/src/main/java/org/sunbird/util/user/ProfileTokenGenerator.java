package org.sunbird.util.user;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.keys.JsonKey;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;


public class ProfileTokenGenerator {

    private static final LoggerUtil logger = new LoggerUtil(ProfileTokenGenerator.class);

    private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;

    private static final AtomicReference<SecretKey> TOKEN_KEY = new AtomicReference<>();

    private ProfileTokenGenerator() {}

    public static String generate(
            Map<String, Object> userProfile, String userId, RequestContext context) {
        try {
            Map<String, Object> profileDetails = asMap(userProfile.get(JsonKey.PROFILE_DETAILS));
            Map<String, Object> personalDetails = asMap(profileDetails.get(JsonKey.PERSONAL_DETAILS));
            Map<String, Object> cadreDetails = asMap(profileDetails.get(JsonKey.CADRE_DETAILS));
            Map<String, Object> professionalDetails =
                    firstEntry(profileDetails.get(JsonKey.PROFESSIONAL_DETAILS));
            Map<String, Object> rootOrg = asMap(userProfile.get(JsonKey.ROOT_ORG));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(JsonKey.PROFILE_TOKEN_CLAIM_PROFILE_STATUS, asString(profileDetails.get(JsonKey.PROFILE_STATUS)));
            payload.put(
                    JsonKey.SERVICE,
                    firstNotBlank(
                            personalDetails.get(JsonKey.SERVICE_TYPE), cadreDetails.get(JsonKey.CIVIL_SERVICE_NAME)));
            payload.put(
                    JsonKey.BATCH,
                    firstNotBlank(personalDetails.get(JsonKey.BATCH), cadreDetails.get(JsonKey.CADRE_BATCH)));
            payload.put(
                    JsonKey.CADRE,
                    firstNotBlank(personalDetails.get(JsonKey.CADRE), cadreDetails.get(JsonKey.CADRE_NAME)));
            payload.put(JsonKey.DESIGNATION, asString(professionalDetails.get(JsonKey.DESIGNATION)));
            payload.put(
                    JsonKey.USER,
                    StringUtils.isNotBlank(userId) ? userId : asString(userProfile.get(JsonKey.USER_ID)));
            payload.put(JsonKey.ROOTORG_ID, asString(userProfile.get(JsonKey.ROOT_ORG_ID)));
            payload.put(JsonKey.GROUP, asString(professionalDetails.get(JsonKey.GROUP)));
            payload.put(
                    JsonKey.MINISTRY_STATE_ID,
                    firstNotBlank(
                            profileDetails.get(JsonKey.MINISTRY_STATE_ID), rootOrg.get(JsonKey.MINISTRY_STATE_ID)));
            payload.put(
                    JsonKey.MINISTRY_STATE_TYPE,
                    firstNotBlank(
                            profileDetails.get(JsonKey.MINISTRY_STATE_TYPE),
                            rootOrg.get(JsonKey.MINISTRY_STATE_TYPE)));
            payload.put(JsonKey.ROLES, roleNames(userProfile.get(JsonKey.ROLES)));

            return sign(payload);
        } catch (GeneralSecurityException | RuntimeException e) {
            logger.error(
                    "ProfileTokenGenerator:generate: unable to build profileToken for user " + userId,
                    e);
            return null;
        }
    }

    /** Roles are held either as plain names or as role objects, depending on the read version. */
    private static List<String> roleNames(Object roles) {
        if (!(roles instanceof List) || CollectionUtils.isEmpty((List) roles)) {
            return Collections.emptyList();
        }
        return ((List<Object>) roles)
                .stream()
                .map(
                        role ->
                                role instanceof Map
                                        ? asString(((Map<String, Object>) role).get(JsonKey.ROLE))
                                        : asString(role))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private static Map<String, Object> firstEntry(Object value) {
        if (value instanceof List && CollectionUtils.isNotEmpty((List) value)) {
            return asMap(((List<Object>) value).get(0));
        }
        return Collections.emptyMap();
    }

    private static String asString(Object value) {
        return null == value ? "" : String.valueOf(value);
    }

    private static String firstNotBlank(Object preferred, Object fallback) {
        String value = asString(preferred);
        return StringUtils.isNotBlank(value) ? value : asString(fallback);
    }

    /** Signs the claims as a compact JWS. Never SignatureAlgorithm.NONE. */
    private static String sign(Map<String, Object> claims) throws GeneralSecurityException {
        try {
            return Jwts.builder()
                    .setClaims(claims)
                    .setIssuer(JsonKey.PROFILE_TOKEN_ISSUER)
                    .setIssuedAt(new Date())
                    .signWith(SIGNATURE_ALGORITHM, key())
                    .compact();
        } catch (Exception e) {
            System.out.println("ProfileTokenGenerator:sign: unable to sign profileToken claims " + e.getMessage());
            throw e;
        }
    }


    private static SecretKey key() throws GeneralSecurityException {
        SecretKey derived = TOKEN_KEY.get();
        if (null == derived) {
            String secret = configuredSecret();
            if (StringUtils.isBlank(secret)) {
                throw new InvalidKeyException(
                        JsonKey.PROFILE_TOKEN_KEY + " is not configured; profileToken cannot be generated");
            }
            if (secret.trim().length() < JsonKey.PROFILE_TOKEN_MIN_SECRET_LENGTH) {
                throw new InvalidKeyException(
                        JsonKey.PROFILE_TOKEN_KEY
                                + " is shorter than "
                                + JsonKey.PROFILE_TOKEN_MIN_SECRET_LENGTH
                                + " characters; use `openssl rand -base64 32`");
            }
            byte[] keyBytes =
                    MessageDigest.getInstance(JsonKey.PROFILE_TOKEN_KEY_DIGEST).digest(secret.trim().getBytes(StandardCharsets.UTF_8));
            try {
                derived = new SecretKeySpec(keyBytes, JsonKey.PROFILE_TOKEN_HMAC_ALGORITHM);
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
            TOKEN_KEY.compareAndSet(null, derived);
            derived = TOKEN_KEY.get();
        }
        return derived;
    }

    /**
     * Reads the secret from externalresource.properties, with the same-named environment variable
     * taking precedence.
     */
    private static String configuredSecret() {
        return ProjectUtil.getConfigValue(JsonKey.PROFILE_TOKEN_KEY);
    }
}
