package org.sunbird.notification.sms.providerimpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.sunbird.logging.LoggerUtil;
import org.sunbird.notification.sms.provider.ISmsProvider;
import org.sunbird.notification.utils.JsonUtil;
import org.sunbird.notification.utils.PropertiesCache;
import org.sunbird.request.RequestContext;

import com.fasterxml.jackson.databind.ObjectMapper;

public class GcpGatewaySmsProvider implements ISmsProvider {
    private static final LoggerUtil logger = new LoggerUtil(GcpGatewaySmsProvider.class);

    private static String baseUrl = null;
    private static String senderId = null;
    private static String authKey = null;
    private static String campaignName = null;

    static {
        boolean response = init();
        logger.info("SMS configuration values are set using GCP : " + response);
    }

    @Override
    public boolean send(String phoneNumber, String smsText, RequestContext context) {
        return sendSms(phoneNumber, smsText, context);
    }

    @Override
    public boolean send(
            String phoneNumber, String countryCode, String smsText, RequestContext context) {
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
            String dltTemplateId = getTemplateId(smsText, GCP_PROVIDER);
            if (StringUtils.isBlank(dltTemplateId)) {
                logger.error(context, "dlt template id is empty for sms : " + smsText, new Exception("TemplateId is not configured properly."));
                return retVal;
            }
            Map<String, Object> messageMap = new HashMap<String, Object>();
            messageMap.put("msgdata", smsText);
            messageMap.put("Template_ID", dltTemplateId);
            messageMap.put("coding", "1");
            messageMap.put("flash_message", 1);
            messageMap.put("scheduleTime", "");
            Map<String, Object> request = new HashMap<String, Object>();
            request.put("message", messageMap);
            request.put("campaign_name", campaignName);
            request.put("auth_key", authKey);
            request.put("receivers", mobileNumber);
            request.put("sender", senderId);
            request.put("route", "TR");
            long startTime = System.currentTimeMillis();
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(baseUrl);
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity((new ObjectMapper()).writeValueAsString(request)));
                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    int responseCode = response.getStatusLine().getStatusCode();
                    logger.info(String.format("Sent SMS. ResponseCode: %s, Response body: %s, TimeTaken %s seconds",
                            responseCode, response.getEntity(), (System.currentTimeMillis() - startTime) / 1000));
                    if (responseCode == 200) {
                        retVal = true;
                    }
                } catch (Exception e) {
                    logger.error("Failed to send SMS to mobile: " + mobileNumber + ", Exception: ", e);
                }
            } catch (Exception e) {
                logger.error("Failed to create httpClient. Exception: ", e);
            }
        } catch (Exception ex) {
            logger.error(context, "Exception occurred while sending sms.", ex);
        }
        return retVal;
    }

    /** this method will do the SMS properties initialization. */
    public static boolean init() {
        baseUrl = System.getenv("gcp_sms_gateway_provider_base_url");
        if (JsonUtil.isStringNullOREmpty(baseUrl)) {
            baseUrl = PropertiesCache.getInstance().getProperty("gcp_sms_gateway_provider_base_url");
        }
        authKey = System.getenv("gcp_sms_gateway_provider_authkey");
        if (JsonUtil.isStringNullOREmpty(authKey)) {
            authKey = PropertiesCache.getInstance().getProperty("gcp_sms_gateway_provider_authkey");
        }
        senderId = System.getenv("gcp_sms_gateway_provider_senderid");
        if (JsonUtil.isStringNullOREmpty(senderId)) {
            senderId = PropertiesCache.getInstance().getProperty("gcp_sms_gateway_provider_senderid");
        }
        campaignName = System.getenv("gcp_sms_gateway_provider_campaign_name");
        if (JsonUtil.isStringNullOREmpty(campaignName)) {
            campaignName = PropertiesCache.getInstance().getProperty("gcp_sms_gateway_provider_campaign_name");
        }
        return validateSettings();
    }

    private static boolean validateSettings() {
        if (!JsonUtil.isStringNullOREmpty(baseUrl)
                && !JsonUtil.isStringNullOREmpty(authKey)
                && !JsonUtil.isStringNullOREmpty(senderId)
                && !JsonUtil.isStringNullOREmpty(campaignName)) {
            return true;
        }
        return false;
    }
}
