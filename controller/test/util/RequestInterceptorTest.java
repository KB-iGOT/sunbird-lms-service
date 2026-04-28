package util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.auth.verifier.AccessTokenValidator;
import org.sunbird.keys.JsonKey;
import play.mvc.Http;
import play.test.Helpers;

@RunWith(PowerMockRunner.class)
@PrepareForTest({AccessTokenValidator.class})
@PowerMockIgnore({
  "javax.management.*",
  "javax.net.ssl.*",
  "javax.security.*",
  "jdk.internal.reflect.*",
  "javax.crypto.*",
  "javax.script.*",
  "javax.xml.*",
  "com.sun.org.apache.xerces.*",
  "org.xml.*"
})
public class RequestInterceptorTest {

  private static AccessTokenValidator tokenValidator;

  @Test
  public void testIsRequestInExcludeListWithResAnonymous() {
    tokenValidator = mock(AccessTokenValidator.class);
    PowerMockito.mockStatic(AccessTokenValidator.class);
    Http.RequestBuilder requestBuilder =
        Helpers.fakeRequest(Helpers.GET, "http://localhost:9000/service/health")
            .header(
                "x-authenticated-user-token",
                "");
    ;
    assertEquals(
        (String)
            RequestInterceptor.verifyRequestData(requestBuilder.build(), new HashMap<>())
                .get(JsonKey.USER_ID),
        "Anonymous");
  }

  @Test
  public void testIsRequestInExcludeLisWithReqUrlNotExists() {
    boolean isurlexits = RequestInterceptor.isRequestInExcludeList("/v1/group/create");
    assertFalse(isurlexits);
  }

  @Test
  public void testVerifyRequestDataReturnsAuthorizedUser() {
    tokenValidator = mock(AccessTokenValidator.class);
    PowerMockito.mockStatic(AccessTokenValidator.class);
    ObjectNode requestNode = JsonNodeFactory.instance.objectNode();
    ObjectNode userNode = JsonNodeFactory.instance.objectNode();
    userNode.put(JsonKey.USER_ID, "56c2d9a3-fae9-4341-9862-4eeeead2e9a1");
    requestNode.put(JsonKey.REQUEST, userNode);
    Http.RequestBuilder requestBuilder =
        Helpers.fakeRequest(Helpers.POST, "http://localhost:9000/v1/group/create")
            .header(
                "x-authenticated-user-token",
                "")
            .header(
                "x-authenticated-for",
                "")
            .bodyJson(requestNode);
    Http.Request req = requestBuilder.build();
    when(tokenValidator.verifyUserToken(Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    when(tokenValidator.verifyManagedUserToken(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    assertEquals(
        (String)
            RequestInterceptor.verifyRequestData(requestBuilder.build(), new HashMap<>())
                .get(JsonKey.USER_ID),
        "authorized-user");
  }

  @Test
  public void testVerifyRequestDataReturnsAuthorizedUser2() {
    tokenValidator = mock(AccessTokenValidator.class);
    PowerMockito.mockStatic(AccessTokenValidator.class);
    ObjectNode requestNode = JsonNodeFactory.instance.objectNode();
    ObjectNode userNode = JsonNodeFactory.instance.objectNode();
    userNode.put(JsonKey.USER_ID, "56c2d9a3-fae9-4341-9862-4eeeead2e9a1");
    requestNode.put(JsonKey.REQUEST, userNode);
    Http.RequestBuilder requestBuilder =
        Helpers.fakeRequest(Helpers.POST, "http://localhost:9000/v1/group/create")
            .bodyJson(requestNode);
    Http.Request req = requestBuilder.build();
    when(tokenValidator.verifyUserToken(Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    when(tokenValidator.verifyManagedUserToken(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    assertEquals(
        RequestInterceptor.verifyRequestData(requestBuilder.build(), new HashMap<>())
            .get(JsonKey.USER_ID),
        JsonKey.UNAUTHORIZED);
  }

  @Test
  public void testVerifyRequestDataReturnsAuthorizedUserForGET() {
    tokenValidator = mock(AccessTokenValidator.class);
    PowerMockito.mockStatic(AccessTokenValidator.class);
    ObjectNode requestNode = JsonNodeFactory.instance.objectNode();
    ObjectNode userNode = JsonNodeFactory.instance.objectNode();
    userNode.put(JsonKey.USER_ID, "56c2d9a3-fae9-4341-9862-4eeeead2e9a1");
    requestNode.put(JsonKey.REQUEST, userNode);
    Http.RequestBuilder requestBuilder =
        Helpers.fakeRequest(
                Helpers.GET,
                "http://localhost:9000/v1/user/read/56c2d9a3-fae9-4341-9862-4eeeead2e9a1")
            .header(
                "x-authenticated-user-token",
                "")
            .header(
                "x-authenticated-for",
                "");
    Http.Request req = requestBuilder.build();
    when(tokenValidator.verifyUserToken(Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    when(tokenValidator.verifyManagedUserToken(
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
        .thenReturn("authorized-user");
    assertEquals(
        (String)
            RequestInterceptor.verifyRequestData(requestBuilder.build(), new HashMap<>())
                .get(JsonKey.USER_ID),
        "authorized-user");
  }

  @Test
  public void testVerifyRequestDataReturingUnAuthorizedUser() {
    tokenValidator = mock(AccessTokenValidator.class);
    Http.RequestBuilder requestBuilder =
        Helpers.fakeRequest(Helpers.GET, "http://localhost:9000/v1/group/read")
            .header(
                "x-authenticated-user-token",
                "");
    Http.Request req = requestBuilder.build();
    assertEquals(
        (String)
            RequestInterceptor.verifyRequestData(requestBuilder.build(), new HashMap<>())
                .get(JsonKey.USER_ID),
        JsonKey.UNAUTHORIZED);
  }
}
