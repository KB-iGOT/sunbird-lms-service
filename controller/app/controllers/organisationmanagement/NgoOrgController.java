package controllers.organisationmanagement;

import akka.actor.ActorRef;
import controllers.BaseController;
import org.sunbird.auth.verifier.AccessTokenValidator;
import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.util.ProjectUtil;
import org.sunbird.validator.BaseRequestValidator;
import org.sunbird.validator.orgvalidator.OrgRequestValidator;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class NgoOrgController extends BaseController {

  @Inject
  @Named("org_management_actor")
  private ActorRef organisationManagementActor;

  @Inject
  @Named("search_handler_actor")
  private ActorRef searchHandlerActor;

  public CompletionStage<Result> getNgoOrgDetails(Http.Request httpRequest) {
    Optional<String> authUserToken =
            httpRequest.getHeaders().get(JsonKey.X_AUTHENTICATED_USER_TOKEN);
    Optional<String> rootOrgId =
            httpRequest.getHeaders().get(JsonKey.X_AUTH_USER_ORG_ID);
    final String requestingUserId = AccessTokenValidator.verifyUserToken(
            authUserToken.get(),
            new HashMap<>());
    return handleRequest(
            organisationManagementActor,
            ActorOperations.GET_ORG_DETAILS.getValue(),
            httpRequest.body().asJson(),
            orgRequest -> {
                new OrgRequestValidator().validateOrgReference((Request) orgRequest);
                ((Request) orgRequest).put(JsonKey.USERID, requestingUserId);
                ((Request) orgRequest).put(JsonKey.ROOT_ORG_ID, rootOrgId.get());
                return null;
            },
            getAllRequestHeaders(httpRequest),
            httpRequest);
  }
}
