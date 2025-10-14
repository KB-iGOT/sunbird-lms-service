package org.sunbird.notification.sms.providerimpl;

import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.keys.JsonKey;
import org.sunbird.notification.sms.provider.ISmsProvider;
import org.sunbird.notification.utils.PropertiesCache;
import org.sunbird.notification.utils.SMSFactory;
import org.sunbird.notification.utils.SmsTemplateUtil;
import org.sunbird.request.RequestContext;
import org.sunbird.util.ProjectUtil;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"javax.management.*", "javax.net.ssl.*", "javax.security.*"})
@PrepareForTest({
  HttpClients.class,
  CloseableHttpClient.class,
  PropertiesCache.class,
  SmsTemplateUtil.class,
  ProjectUtil.class,
  SMSFactory.class
})
public class Msg91PostMethodTest {

  private void initMockRulesFor200() {
    CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    CloseableHttpResponse httpResp = mock(CloseableHttpResponse.class);
    PropertiesCache propertiesCache = mock(PropertiesCache.class);
    StatusLine statusLine = mock(StatusLine.class);
    
    PowerMockito.mockStatic(HttpClients.class);
    PowerMockito.mockStatic(ProjectUtil.class);
  PowerMockito.mockStatic(PropertiesCache.class);
    
    try {
      // Mock ProjectUtil first - this is critical for SMSFactory.getInstance()
      when(ProjectUtil.getConfigValue("sms_gateway_provider")).thenReturn(JsonKey.MSG_91);
      when(ProjectUtil.getConfigValue(Mockito.anyString())).thenReturn("test_value");
      
      // Mock PropertiesCache for Msg91SmsProvider initialization
      when(PropertiesCache.getInstance()).thenReturn(propertiesCache);
      when(propertiesCache.getProperty(Mockito.anyString())).thenReturn("test_value");
      when(propertiesCache.getProperty("sunbird.msg.91.baseurl")).thenReturn("http://api.msg91.com/");
      when(propertiesCache.getProperty("sunbird.msg.91.post.url")).thenReturn("api/v2/sendsms");
      when(propertiesCache.getProperty("sunbird.msg.91.method")).thenReturn("POST");
      when(propertiesCache.getProperty("sunbird.msg.91.sender")).thenReturn("TestSun");
      when(propertiesCache.getProperty("sunbird.msg.91.route")).thenReturn("4");
      when(propertiesCache.getProperty("sunbird.msg.91.country")).thenReturn("91");
      when(propertiesCache.getProperty("sunbird.msg.91.auth")).thenReturn("test_auth_key");
      
      // Mock HTTP client behavior
      doReturn(httpClient).when(HttpClients.class, "createDefault");
      when(httpClient.execute(Mockito.any(HttpPost.class))).thenReturn(httpResp);
      doReturn(statusLine).when(httpResp).getStatusLine();
      doReturn(200).when(statusLine).getStatusCode();

  // Factory selection will use ProjectUtil mock; no need to stub SMSFactory directly
      
    } catch (Exception e) {
      Assert.fail("Exception while mocking static " + e.getLocalizedMessage());
    }
  }

  private void initMockRulesFor400() {
    CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    CloseableHttpResponse httpResp = mock(CloseableHttpResponse.class);
    PropertiesCache propertiesCache = mock(PropertiesCache.class);
    StatusLine statusLine = mock(StatusLine.class);
    
    PowerMockito.mockStatic(HttpClients.class);
    PowerMockito.mockStatic(ProjectUtil.class);
  PowerMockito.mockStatic(PropertiesCache.class);
    
    try {
      // Mock ProjectUtil first - this is critical for SMSFactory.getInstance()
      when(ProjectUtil.getConfigValue("sms_gateway_provider")).thenReturn(JsonKey.MSG_91);
      when(ProjectUtil.getConfigValue(Mockito.anyString())).thenReturn("test_value");
      
      // Mock PropertiesCache for Msg91SmsProvider initialization
      when(PropertiesCache.getInstance()).thenReturn(propertiesCache);
      when(propertiesCache.getProperty(Mockito.anyString())).thenReturn("test_value");
      when(propertiesCache.getProperty("sunbird.msg.91.baseurl")).thenReturn("http://api.msg91.com/");
      when(propertiesCache.getProperty("sunbird.msg.91.post.url")).thenReturn("api/v2/sendsms");
      when(propertiesCache.getProperty("sunbird.msg.91.method")).thenReturn("POST");
      when(propertiesCache.getProperty("sunbird.msg.91.sender")).thenReturn("TestSun");
      when(propertiesCache.getProperty("sunbird.msg.91.route")).thenReturn("4");
      when(propertiesCache.getProperty("sunbird.msg.91.country")).thenReturn("91");
      when(propertiesCache.getProperty("sunbird.msg.91.auth")).thenReturn("test_auth_key");
      
      // Mock HTTP client behavior
      doReturn(httpClient).when(HttpClients.class, "createDefault");
      when(httpClient.execute(Mockito.any(HttpPost.class))).thenReturn(httpResp);
      doReturn(statusLine).when(httpResp).getStatusLine();
      doReturn(400).when(statusLine).getStatusCode();

  // Factory selection will use ProjectUtil mock; no need to stub SMSFactory directly
      
    } catch (Exception e) {
      Assert.fail("Exception while mocking static " + e.getLocalizedMessage());
    }
  }

  @Test
  public void testSendSms() {
    initMockRulesFor200();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to reset your password on instance is 456123. This is valid for 30 minutes only.";
    boolean response = megObj.send("4321111111", sms, new RequestContext());
    Assert.assertTrue(response);
  }

  @Test
  public void testSendSmsFailure() {
    initMockRulesFor400();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to verify your phone number on instance is 456123. This is valid for 30 minutes only.";
    boolean response = megObj.send("4321111111", sms, new RequestContext());
    Assert.assertFalse(response);
  }

  @Test
  public void testSendSmsToMultiplePhone() {
    initMockRulesFor200();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to verify your phone number on instance is 456123. This is valid for 30 minutes only.";
    List<String> phoneList = new ArrayList<>();
    phoneList.add("5464654653");
    phoneList.add("7897951543");
    boolean response = megObj.send(phoneList, sms, new RequestContext());
    Assert.assertTrue(response);
  }

  @Test
  public void testSendSmsFailureToMultiplePhone() {
    initMockRulesFor400();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to verify your phone number on instance is 456123. This is valid for 30 minutes only.";
    List<String> phoneList = new ArrayList<>();
    phoneList.add("5464654653");
    phoneList.add("7897951543");
    boolean response = megObj.send(phoneList, sms, new RequestContext());
    Assert.assertFalse(response);
  }

  @Test
  public void testSendSmsWithCountryCode() {
    initMockRulesFor200();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to verify your phone number on instance is 456123. This is valid for 30 minutes only.";
    boolean response = megObj.send("4321111111", "+91", sms, new RequestContext());
    Assert.assertTrue(response);
  }

  @Test
  public void testSendSmsFailureWithCountryCode() {
    initMockRulesFor400();
    PowerMockito.mockStatic(SmsTemplateUtil.class);
    Map<String, Map<String, String>> template = new HashMap<>();
    Map<String, String> template1 = new HashMap<>();
    template1.put(
        "OTP to verify your phone number on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "1");
    template1.put(
        "OTP to reset your password on $installationName is $otp. This is valid for $otpExpiryInMinutes minutes only.",
        "2");
    template1.put(
        "Your ward has requested for registration on $installationName using this phone number. Use OTP $otp to agree and create the account. This is valid for $otpExpiryInMinutes minutes only.",
        "3");
    template.put(JsonKey.MSG_91, template1);
    when(SmsTemplateUtil.getSmsTemplateConfigMap()).thenReturn(template);
    ISmsProvider megObj = SMSFactory.getInstance();
    String sms =
        "OTP to verify your phone number on instance is 456123. This is valid for 30 minutes only.";
    boolean response = megObj.send("4321111111", "+91", sms, new RequestContext());
    Assert.assertFalse(response);
  }
}
