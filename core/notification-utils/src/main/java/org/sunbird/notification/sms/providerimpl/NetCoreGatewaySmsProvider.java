package org.sunbird.notification.sms.providerimpl;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.notification.sms.provider.ISmsProvider;
import org.sunbird.notification.utils.JsonUtil;
import org.sunbird.notification.utils.PropertiesCache;
import org.sunbird.request.RequestContext;

public class NetCoreGatewaySmsProvider implements ISmsProvider {

    private static final LoggerUtil logger = new LoggerUtil(NetCoreGatewaySmsProvider.class);

    private static String baseUrl = null;
    private static String feedId = null;
    private static String userName = null;
    private static String password = null;

    static {
        boolean response = init();
        logger.info("NetCoreGatewaySmsProvider:: SMS configuration values are set using NetCore : " + response);
    }

    @Override
    public boolean send(String phoneNumber, String smsText, RequestContext context) {
        return sendSms(phoneNumber, smsText, context);
    }

    @Override
    public boolean send(String phoneNumber, String countryCode, String smsText, RequestContext context) {
        return sendSms(phoneNumber, smsText, context);
    }

    @Override
    public boolean send(List<String> phoneNumbers, String smsText, RequestContext context) {
        phoneNumbers
                .stream()
                .forEach(
                        phone -> {
                            sendSms(phone, smsText, context);
                        });
        return true;
    }

    public boolean sendSms(String mobileNumber, String smsText, RequestContext context) {
        boolean retVal = false;
        try {
            // add dlt template
            String dltTemplateId = getTemplateId(smsText, NETCORE_PROVIDER);
            if (StringUtils.isBlank(dltTemplateId)) {
                logger.error(context, "NetCoreGatewaySmsProvider:: dlt template id is empty for sms : " + smsText,
                        new Exception("TemplateId is not configured properly."));
                return retVal;
            }

            long startTime = System.currentTimeMillis();

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(baseUrl);
                post.setHeader("Content-Type", "application/x-www-form-urlencoded");

                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("feedid", feedId));
                params.add(new BasicNameValuePair("username", userName));
                params.add(new BasicNameValuePair("password", password));
                params.add(new BasicNameValuePair("to", mobileNumber));
                params.add(new BasicNameValuePair("text", smsText));
                params.add(new BasicNameValuePair("templateid", dltTemplateId));
                params.add(new BasicNameValuePair("short", "0"));
                params.add(new BasicNameValuePair("async", "0"));

                post.setEntity(new UrlEncodedFormEntity(params));

                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    int responseCode = response.getStatusLine().getStatusCode();
                    String responseStr = EntityUtils.toString(response.getEntity());
                    logger.info(String.format("SMS Sent. ResponseCode: %s, Response Body: %s, TimeTaken: %s",
                            responseCode, responseStr, (System.currentTimeMillis() - startTime)));
                    if (responseCode == 200) {
                        retVal = true;
                    }
                } catch (Exception e) {
                    logger.error(String.format("Failed to send SMS to mobile: %s, TimeTaken: %s, Exception: %s",
                            mobileNumber, (System.currentTimeMillis() - startTime), e.getMessage()), e);
                }
            } catch (Exception e) {
                logger.error(String.format("Failed to create httpClient. TimeTaken: %s, Exception: %s",
                        (System.currentTimeMillis() - startTime), e.getMessage()), e);
            }
        } catch (Exception ex) {
            logger.error(context, "NetCoreGatewaySmsProvider:: Exception occurred while sending sms.", ex);
        }
        return retVal;
    }

    /** this method will do the SMS properties initialization. */
    public static boolean init() {
        baseUrl = System.getenv("netcore_sms_gateway_provider_base_url");
        if (JsonUtil.isStringNullOREmpty(baseUrl)) {
            baseUrl = PropertiesCache.getInstance().getProperty("netcore_sms_gateway_provider_base_url");
        }
        feedId = System.getenv("netcore_sms_gateway_provider_feedid");
        if (JsonUtil.isStringNullOREmpty(feedId)) {
            feedId = PropertiesCache.getInstance().getProperty("netcore_sms_gateway_provider_feedid");
        }
        userName = System.getenv("netcore_sms_gateway_provider_username");
        if (JsonUtil.isStringNullOREmpty(userName)) {
            userName = PropertiesCache.getInstance().getProperty("netcore_sms_gateway_provider_username");
        }
        password = System.getenv("netcore_sms_gateway_provider_password");
        if (JsonUtil.isStringNullOREmpty(password)) {
            password = PropertiesCache.getInstance().getProperty("netcore_sms_gateway_provider_password");
        }
        return validateSettings();
    }

    private static boolean validateSettings() {
        if (!JsonUtil.isStringNullOREmpty(baseUrl)
                && !JsonUtil.isStringNullOREmpty(feedId)
                && !JsonUtil.isStringNullOREmpty(userName)
                && !JsonUtil.isStringNullOREmpty(password)) {
            return true;
        }
        return false;
    }

}
