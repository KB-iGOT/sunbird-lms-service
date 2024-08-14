package org.sunbird.actor.systemsettings;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.MessageFormat;
import java.util.Map;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.exception.ResponseMessage;
import org.sunbird.exception.ResponseMessage.Message;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.systemsettings.SystemSetting;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.service.systemsettings.SystemSettingsService;

public class SystemSettingsActor extends BaseActor {

  private static final Logger log = LoggerFactory.getLogger(SystemSettingsActor.class);
  private final SystemSettingsService service = new SystemSettingsService();

  @Override
  public void onReceive(Request request) throws Throwable {
    switch (request.getOperation()) {
      case "getSystemSetting":
        getSystemSetting(request);
        break;
      case "getAllSystemSettings":
        getAllSystemSettings(request.getRequestContext());
        break;
      case "setSystemSetting":
        setSystemSetting(request);
        break;
      case "getSystemSettingV2":
        getSystemSettingV2(request);
        break;
      default:
        onReceiveUnsupportedOperation();
        break;
    }
  }

  private void getSystemSetting(Request actorMessage) {
    SystemSetting setting =
        service.getSystemSettingByKey(
            (String) actorMessage.getContext().get(JsonKey.FIELD),
            actorMessage.getRequestContext());
    Response response = new Response();
    response.put(JsonKey.RESPONSE, setting);
    sender().tell(response, self());
  }

  private void getAllSystemSettings(RequestContext context) {
    Response response = new Response();
    response.put(JsonKey.RESPONSE, service.getAllSystemSettings(context));
    sender().tell(response, self());
  }

  private void setSystemSetting(Request actorMessage) {
    Map<String, Object> request = actorMessage.getRequest();
    Response response = service.setSystemSettings(request, actorMessage.getRequestContext());
    sender().tell(response, self());
  }

  private void getSystemSettingV2(Request actorMessage) {
    String key = (String) actorMessage.getContext().get(JsonKey.FIELD);
    JsonNode setting =
        service.getSystemSettingV2ByKey(
            key,
            actorMessage.getRequestContext());
    Response response = new Response();
    if (setting != null && !setting.isNull()) {
      response.put(JsonKey.RESPONSE, setting);
      sender().tell(response, self());
    } else {
      String formattedErrorMessage = MessageFormat.format(
          ResponseCode.resourceNotFound.getErrorMessage(), key);
      ProjectCommonException exception = new ProjectCommonException(
          ResponseCode.resourceNotFound,
          formattedErrorMessage,
          ResponseCode.RESOURCE_NOT_FOUND.getResponseCode()
      );
      sender().tell(exception, self());
    }

  }
}
