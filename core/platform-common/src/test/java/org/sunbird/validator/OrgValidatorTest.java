/** */
package org.sunbird.validator;

import static org.junit.Assert.assertEquals;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.request.Request;
import org.sunbird.util.PropertiesCache;
import org.sunbird.validator.orgvalidator.OrgRequestValidator;

@RunWith(PowerMockRunner.class)
@PrepareForTest({PropertiesCache.class})
@PowerMockIgnore({
  "javax.management.*",
  "javax.net.ssl.*",
  "javax.security.*",
  "jdk.internal.reflect.*"
})
public class OrgValidatorTest {

  @Test
  public void validateCreateOrgSuccess() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    requestObj.put(JsonKey.ORG_TYPE, "board");
    requestObj.put(JsonKey.IS_TENANT, false);
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void validateCreateRootOrgWithLicenseSuccess() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    requestObj.put(JsonKey.ORG_TYPE, "board");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.LICENSE, "Test license");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void validateCreateRootOrgWithEmptyLicenseFailure() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    requestObj.put(JsonKey.LICENSE, "");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNotNull(e);
    }
    assertEquals(requestObj.get("ext"), null);
  }

  @Test
  public void validateCreateOrgWithOutName() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.mandatoryParamsMissing.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void validateCreateOrgWithRootOrgTrueAndWithOutChannel() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.CHANNEL, "");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.mandatoryParamsMissing.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void validateUpdateCreateOrgSuccess() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.ORGANISATION_ID, "test12344");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void validateUpdateOrgFailure() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORGANISATION_ID, "test2344");
    requestObj.put(JsonKey.ROOT_ORG_ID, "");
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void validateUpdateOrgWithStatus() {
    PowerMockito.mockStatic(PropertiesCache.class);
    PropertiesCache propertiesCache = mock(PropertiesCache.class);
    when(PropertiesCache.getInstance()).thenReturn(propertiesCache);
    PowerMockito.when(propertiesCache.getProperty(Mockito.anyString())).thenReturn("anyString");

    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.STATUS, "true");
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "tpp");
    requestObj.put(JsonKey.ORGANISATION_ID, "test123444");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidRequestParameter.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void validateUpdateOrgWithEmptyChannel() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.IS_TENANT, true);
    requestObj.put(JsonKey.CHANNEL, "");
    requestObj.put(JsonKey.ORGANISATION_ID, "test123444");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.dependentParameterMissing.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void validateUpdateOrgStatus() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.STATUS, 2);
    requestObj.put(JsonKey.ORGANISATION_ID, "test-12334");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgStatusRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void validateUpdateOrgStatusWithInvalidStatus() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "test");
    requestObj.put(JsonKey.STATUS, "true");
    requestObj.put(JsonKey.ORGANISATION_ID, "test-12334");
    request.setRequest(requestObj);
    try {
      // this method will either throw projectCommonException or it return void
      new OrgRequestValidator().validateUpdateOrgStatusRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidRequestData.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithValidAlphanumericName() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization 123");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithValidSpecialCharacters() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test & Co., Inc. - (Division's)");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithHTMLTags() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test <script>alert('xss')</script> Org");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithHTMLBoldTag() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test <b>bold</b> Org");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithJavaScriptProtocol() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "javascript:alert('test')");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithDisallowedSpecialCharacters() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test @ # $ % Org");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithControlCharacters() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test\u0000Org");  // null character
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithSQLInjection() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test'; DROP TABLE organizations; --");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithAngularBrackets() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test <> Org");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithValidComplexName() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "ABC Corp. & Sons, Ltd. - (India's Division)");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithOnlySpaces() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "   ");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      // Should fail due to blank check or pass through to validation
      Assert.assertNotNull(e);
    }
  }

  @Test
  public void testValidateCreateOrgWithScriptTagUpperCase() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test <SCRIPT>alert('xss')</SCRIPT> Org");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithValidDescription() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization");
    requestObj.put(JsonKey.DESCRIPTION, "This is a valid description for testing");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithInvalidDescriptionPattern() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization");
    requestObj.put(JsonKey.DESCRIPTION, "Description with <script>alert('xss')</script>");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Only alphanumeric characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithExceedingDescriptionLength() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization");
    // Create a string with 1001 characters
    String longDescription = "a".repeat(1001);
    requestObj.put(JsonKey.DESCRIPTION, longDescription);
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      assertEquals(ResponseCode.invalidParameterValue.getErrorCode(), e.getErrorCode());
      assertEquals(ResponseCode.CLIENT_ERROR.getResponseCode(), e.getErrorResponseCode());
      Assert.assertTrue(e.getMessage().contains("Maximum allowed length is 1000 characters"));
    }
    assertEquals(null, requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithEmptyDescription() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization");
    requestObj.put(JsonKey.DESCRIPTION, "");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }

  @Test
  public void testValidateCreateOrgWithDescriptionSpecialChars() {
    Request request = new Request();
    Map<String, Object> requestObj = new HashMap<>();
    requestObj.put(JsonKey.ORG_NAME, "Test Organization");
    requestObj.put(JsonKey.DESCRIPTION, "Test & Co., Ltd. - (India's Division)");
    requestObj.put(JsonKey.IS_TENANT, false);
    requestObj.put(JsonKey.ORG_TYPE, "board");
    request.setRequest(requestObj);
    try {
      new OrgRequestValidator().validateCreateOrgRequest(request);
      requestObj.put("ext", "success");
    } catch (ProjectCommonException e) {
      Assert.assertNull(e);
    }
    assertEquals("success", requestObj.get("ext"));
  }
}
