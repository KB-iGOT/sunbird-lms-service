package controllers.organisationmanagement;

import akka.actor.ActorRef;
import controllers.BaseController;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.validator.orgvalidator.OrgRequestValidator;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import javax.inject.Named;
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
    Optional<String> rootOrgId =
            httpRequest.getHeaders().get(JsonKey.X_AUTH_USER_ORG_ID);
    return handleRequest(
            organisationManagementActor,
            ActorOperations.GET_ORG_DETAILS.getValue(),
            null,
            orgRequest -> {
                ((Request) orgRequest).put(JsonKey.ORGANISATION_ID, StringUtils.isNotBlank(rootOrgId.get()) ?rootOrgId.get() : null);
                new OrgRequestValidator().validateOrgReference((Request) orgRequest);
                return null;
            },
            null,
            null,
            false,
            httpRequest);
  }
}
