package org.sunbird.sso;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.powermock.api.mockito.PowerMockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.http.HttpClientUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

@PrepareForTest({ProjectUtil.class, HttpClientUtil.class})
@RunWith(PowerMockRunner.class)
@PowerMockIgnore({
  "javax.management.*",
  "javax.net.ssl.*",
  "javax.security.*",
  "jdk.internal.reflect.*"
})
public class KeycloakUtilTest {

  @Before
  public void setup() {
    PowerMockito.mockStatic(HttpClientUtil.class);
    PowerMockito.mockStatic(ProjectUtil.class);
    when(ProjectUtil.getConfigValue(Mockito.anyString())).thenReturn("anyString");
  }

  @Test
  public void testGetAdminAccessToken() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("{\"access_token\":\"accesstoken\"}");

    String token = KeycloakUtil.getAdminAccessToken(new RequestContext(), "url");
    assertTrue(token.equals("accesstoken"));
  }

  @Test
  public void testGetAdminAccessTokenWithEmptyResponse() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("");

    try {
      KeycloakUtil.getAdminAccessToken(new RequestContext(), "url");
      fail("Expected exception for empty response");
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains("Failed to get admin access token: Empty response from Keycloak"));
    }
  }

  @Test
  public void testGetAdminAccessTokenWithNullResponse() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn(null);

    try {
      KeycloakUtil.getAdminAccessToken(new RequestContext(), "url");
      fail("Expected exception for null response");
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains("Failed to get admin access token: Empty response from Keycloak"));
    }
  }

  @Test
  public void testGetAdminAccessTokenWithInvalidJson() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("invalid json");

    try {
      KeycloakUtil.getAdminAccessToken(new RequestContext(), "url");
      fail("Expected exception for invalid JSON");
    } catch (Exception e) {
      assertTrue(
          e.getMessage().contains("Failed to get admin access token: Error parsing response"));
    }
  }

  @Test
  public void testGetAdminAccessTokenWithMissingAccessToken() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("{\"error\":\"invalid_client\"}");

    try {
      KeycloakUtil.getAdminAccessToken(new RequestContext(), "url");
      fail("Expected exception for missing access_token");
    } catch (Exception e) {
      // The exception will be caught by the generic catch block and will have the message "Error
      // parsing response"
      assertTrue(
          "Expected exception message to contain 'Error parsing response', but got: "
              + e.getMessage(),
          e.getMessage().contains("Error parsing response"));
    }
  }

  @Test
  public void testGetAdminAccessTokenWithDomain() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("{\"access_token\":\"accesstoken\"}");

    String token = KeycloakUtil.getAdminAccessTokenWithDomain(new RequestContext());
    assertTrue(token.equals("accesstoken"));
  }

  @Test
  public void testGetAdminAccessTokenWithoutDomain() throws Exception {
    when(HttpClientUtil.postFormData(
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyMap(),
            Mockito.any(RequestContext.class)))
        .thenReturn("{\"access_token\":\"accesstoken\"}");

    String token = KeycloakUtil.getAdminAccessTokenWithoutDomain(new RequestContext());
    assertTrue(token.equals("accesstoken"));
  }
}
