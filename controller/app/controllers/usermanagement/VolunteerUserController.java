package controllers.usermanagement;

import akka.actor.ActorRef;
import akka.pattern.PatternsCS;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import controllers.BaseController;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.user.validator.UserRequestValidator;
import org.sunbird.auth.verifier.AccessTokenValidator;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.response.Response;
import org.sunbird.util.ProjectUtil;
import play.mvc.Http;
import play.mvc.Result;
import util.Attrs;
import util.Common;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static org.sunbird.common.Constants.USER_ROLE_FETCH_TIMEOUT_SEC;

public class VolunteerUserController extends BaseController {

    @Inject
    @Named("sso_user_create_actor")
    private ActorRef ssoUserCreateActor;

    @Inject
    @Named("fetch_user_role_actor")
    private ActorRef fetchUserRoleActor;

    @Inject
    @Named("user_profile_read_actor")
    private ActorRef userProfileReadActor;

    @Inject
    @Named("user_update_actor")
    private ActorRef userUpdateActor;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SYNC = "sync";

    public CompletionStage<Result> createVolunteerUserV5(Http.Request httpRequest)
            throws JsonProcessingException {
        Optional<String> authUserToken =
                httpRequest.getHeaders().get(JsonKey.X_AUTHENTICATED_USER_TOKEN);
        Optional<String> rootOrgId =
                httpRequest.getHeaders().get(JsonKey.X_AUTH_USER_ORG_ID);
        Map<String, Object> requestMap = mapper.readValue(
                httpRequest.body().asJson().toString(), Map.class);
        Map<String, Object> userMap = (Map<String, Object>) requestMap.get(JsonKey.REQUEST);

        // Mark as volunteer creation source
        userMap.put(JsonKey.SOURCE_CREATION_TYPE, JsonKey.NGO_USER_CREATE);

        JsonNode requestMapJsonNode = mapper.valueToTree(requestMap);

        return handleRequest(
                ssoUserCreateActor,
                ActorOperations.CREATE_USER_V5.getValue(),
                requestMapJsonNode,
                req -> {
                    Request request = (Request) req;
                    request.getRequest().put(SYNC, true);
                    // Use dedicated volunteer validator instead of regular V5 validator
                    new UserRequestValidator().validateVolunteerUserCreate(request, authUserToken.get());
                    request.getContext().put(JsonKey.ROOT_ORG_ID, rootOrgId.get());
                    return null;
                },
                null,
                null,
                true,
                httpRequest);
    }


    public CompletionStage<Result> getVolunteerUserDetails(Http.Request httpRequest) throws JsonProcessingException {

        Optional<String> authUserToken =
                httpRequest.getHeaders().get(JsonKey.X_AUTHENTICATED_USER_TOKEN);

        return handleGetVolunteerUserProfileV1(
                ActorOperations.GET_USER_PROFILE_V5.getValue(),
                authUserToken.get(),
                httpRequest);
    }


    private CompletionStage<Result> handleGetVolunteerUserProfileV1(
            String operation, String authUserToken, Http.Request httpRequest) throws JsonProcessingException {

        if (StringUtils.isBlank(authUserToken)) {
            throw new ProjectCommonException(
                    ResponseCode.SERVER_ERROR,
                    ResponseCode.SERVER_ERROR.getErrorMessage(),
                    ResponseCode.SERVER_ERROR.getResponseCode());
        }

        final String requestingUserId = AccessTokenValidator.verifyUserToken(
                authUserToken,
                new HashMap<>());
        Map<String, Object> requestMap = mapper.readValue(
                httpRequest.body().asJson().toString(), Map.class);

        Request reqJson = new Request();
        reqJson.setRequest(requestMap);

        // Validate request body first
        new UserRequestValidator().validateVolunteerUserReadRequest(reqJson);

        // Extract userId after validation
        String requestedUserId = reqJson.get(JsonKey.USER_ID).toString();
        requestedUserId = ProjectUtil.getLmsUserId(requestedUserId);


        return setFetchUserRoleActor(httpRequest, requestingUserId, requestedUserId)
                .thenCompose(hasAccess -> {

                    if (!hasAccess) {
                        throw new ProjectCommonException(
                                ResponseCode.UNAUTHORIZED,
                                ResponseCode.UNAUTHORIZED.getErrorMessage(),
                                ResponseCode.UNAUTHORIZED.getResponseCode());
                    }
                    final String requestedFields = httpRequest.getQueryString(JsonKey.FIELDS);
                    final String provider = httpRequest.getQueryString(JsonKey.PROVIDER);
                    final String idType = httpRequest.getQueryString(JsonKey.ID_TYPE);
                    final String withTokens = httpRequest.getQueryString(JsonKey.WITH_TOKENS);
                    final boolean isPrivate = httpRequest.path().contains(JsonKey.PRIVATE);

                    return handleRequest(
                            userProfileReadActor,
                            operation,
                            httpRequest.body().asJson(),
                            req -> {
                                Request request = (Request) req;
                                request.getContext().put(JsonKey.VERSION, JsonKey.VERSION_3);
                                request.getContext().put(JsonKey.FIELDS, requestedFields);
                                request.getContext().put(JsonKey.PRIVATE, isPrivate);
                                request.getContext().put(JsonKey.WITH_TOKENS, withTokens);
                                request.getContext().put(JsonKey.PROVIDER, provider);
                                request.getContext().put(JsonKey.ID_TYPE, idType);
                                return null;
                            },
                            null,
                            null,
                            true,
                            httpRequest);
                });
    }


    private CompletionStage<Boolean> setFetchUserRoleActor(
            Http.Request httpRequest,
            String loggedInUserId, String requestedUserId) {

        Request roleReq = createAndInitRequest(
                ActorOperations.GET_USER_ROLES_BY_ID.getValue(),
                httpRequest);

        roleReq.getRequest().put(JsonKey.USER_ID, loggedInUserId);
        roleReq.getContext().put(JsonKey.USER_ID, loggedInUserId);
        roleReq.setTimeout(USER_ROLE_FETCH_TIMEOUT_SEC);

        return PatternsCS.ask(fetchUserRoleActor, roleReq, timeout)
                .thenApply(actorRespObj -> {

                    if (!(actorRespObj instanceof Response)) {
                        throw new ProjectCommonException(
                                ResponseCode.UNAUTHORIZED,
                                ResponseCode.UNAUTHORIZED.getErrorMessage(),
                                ResponseCode.UNAUTHORIZED.getResponseCode());
                    }

                    Response actorResp = (Response) actorRespObj;

                    Object respObj =
                            actorResp.getResult().get(JsonKey.RESPONSE);

                    List<Map<String, Object>> roleList =
                            respObj instanceof List
                                    ? (List<Map<String, Object>>) respObj
                                    : Collections.emptyList();

                    Set<String> userRoles = roleList.stream()
                            .map(m -> (String) m.get(JsonKey.ROLE))
                            .filter(StringUtils::isNotBlank)
                            .map(String::toUpperCase)
                            .collect(Collectors.toSet());

                    boolean isNgoAdmin =
                            userRoles.contains(JsonKey.NGO_ADMIN);

                    boolean isVolunteer =
                            userRoles.contains(JsonKey.VOLUNTEER);

                    return isNgoAdmin
                            || (isVolunteer
                            && StringUtils.isNotBlank(requestedUserId)
                            && requestedUserId.equalsIgnoreCase(loggedInUserId));
                })
                .exceptionally(ex -> {
                    Throwable cause =
                            ex instanceof CompletionException
                                    && ex.getCause() != null
                                    ? ex.getCause()
                                    : ex;

                    if (cause instanceof ProjectCommonException) {
                        throw (ProjectCommonException) cause;
                    }

                    throw new ProjectCommonException(
                            ResponseCode.SERVER_ERROR,
                            ResponseCode.SERVER_ERROR.getErrorMessage(),
                            ResponseCode.SERVER_ERROR.getResponseCode());
                });
    }

    public CompletionStage<Result> updateVolunteerUser(Http.Request httpRequest) {

        return handleRequest(
                userUpdateActor,
                ActorOperations.UPDATE_USER.getValue(),
                httpRequest.body().asJson(),
                req -> {
                    Request request = (Request) req;
                    request
                            .getContext()
                            .put(JsonKey.USER_ID, Common.getFromRequest(httpRequest, Attrs.USER_ID));
                    new UserRequestValidator().validateVolunteerUserUpdateRequest(request);
                    request
                            .getContext()
                            .put(JsonKey.IS_AUTH_REQ, Common.getFromRequest(httpRequest, Attrs.IS_AUTH_REQ));

                    return null;
                },
                null,
                null,
                true,
                httpRequest);
    }

    public CompletionStage<Result> bulkCreateUserV5(Http.Request httpRequest) throws JsonProcessingException {
        Optional<String> authUserToken =
                httpRequest.getHeaders().get(JsonKey.X_AUTHENTICATED_USER_TOKEN);
        Map<String, Object> requestMap = mapper.readValue(
                httpRequest.body().asJson().toString(), Map.class);
        Map<String, Object> userMap = (Map<String, Object>) requestMap.get(JsonKey.REQUEST);
        userMap.put(JsonKey.SOURCE_CREATION_TYPE, JsonKey.BULK_USER_CREATE);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode requestMapJsonNode = objectMapper.valueToTree(requestMap);
        return handleRequest(
                ssoUserCreateActor,
                ActorOperations.BULK_CREATE_USER_V5.getValue(),
                requestMapJsonNode,
                req -> {
                    Request request = (Request) req;
                    request.getRequest().put(SYNC, true);
                    new UserRequestValidator().validateVolunteerUserCreate(request, authUserToken.get());
                    request.getContext().put(JsonKey.VERSION, JsonKey.VERSION_4);
                    return null;
                },
                null,
                null,
                true,
                httpRequest);
    }

}






