package controllers.organisationmanagement;

import akka.actor.ActorRef;
import controllers.BaseController;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import javax.inject.Inject;
import javax.inject.Named;

import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import org.sunbird.util.ProjectUtil;
import org.sunbird.validator.BaseRequestValidator;
import org.sunbird.validator.orgvalidator.OrgRequestValidator;
import play.mvc.Http;
import play.mvc.Result;

public class OrgController extends BaseController {

  @Inject
  @Named("org_management_actor")
  private ActorRef organisationManagementActor;

  @Inject
  @Named("search_handler_actor")
  private ActorRef searchHandlerActor;

  private final Map<String, Object> ministryFilters;
  private final Map<String, Object> stateFilters;

  public OrgController() {
    this.ministryFilters = ProjectUtil.loadFilters(
            JsonKey.ORG_HIERARCHY_SEARCH_MINISTRY_FILTERS);

    this.stateFilters = ProjectUtil.loadFilters(
            JsonKey.ORG_HIERARCHY_SEARCH_STATE_FILTERS);
  }

  public CompletionStage<Result> createOrg(Http.Request httpRequest) {
    return handleRequest(
        organisationManagementActor,
        ActorOperations.CREATE_ORG.getValue(),
        httpRequest.body().asJson(),
        orgRequest -> {
          new OrgRequestValidator().validateCreateOrgRequest((Request) orgRequest);
          return null;
        },
        getAllRequestHeaders(httpRequest),
        httpRequest);
  }

  public CompletionStage<Result> updateOrg(Http.Request httpRequest) {
    return handleRequest(
        organisationManagementActor,
        ActorOperations.UPDATE_ORG.getValue(),
        httpRequest.body().asJson(),
        orgRequest -> {
          new OrgRequestValidator().validateUpdateOrgRequest((Request) orgRequest);
          return null;
        },
        getAllRequestHeaders(httpRequest),
        httpRequest);
  }

  public CompletionStage<Result> updateOrgStatus(Http.Request httpRequest) {
    return handleRequest(
        organisationManagementActor,
        ActorOperations.UPDATE_ORG_STATUS.getValue(),
        httpRequest.body().asJson(),
        orgRequest -> {
          new OrgRequestValidator().validateUpdateOrgStatusRequest((Request) orgRequest);
          return null;
        },
        getAllRequestHeaders(httpRequest),
        httpRequest);
  }

  public CompletionStage<Result> getOrgDetails(Http.Request httpRequest) {
    // Org details are identical for every caller (no auth-gated or requester-specific fields —
    // see OrganisationManagementActor.getOrgDetails) and change rarely, so this response is safe
    // to cache at an edge/CDN layer keyed purely on request body (orgId/externalId+provider).
    // This header is what a shared Nginx/CDN cache in front of this service would key off of;
    // the actual proxy_cache config still needs to be added at the infra layer.
    return handleRequest(
            organisationManagementActor,
            ActorOperations.GET_ORG_DETAILS.getValue(),
            httpRequest.body().asJson(),
            orgRequest -> {
              new OrgRequestValidator().validateOrgReference((Request) orgRequest);
              return null;
            },
            getAllRequestHeaders(httpRequest),
            httpRequest)
        .thenApply(result -> result.withHeader("Cache-Control", "public, max-age=3600"));
  }

  public CompletionStage<Result> search(Http.Request httpRequest) {
    return handleSearchRequest(
        searchHandlerActor,
        ActorOperations.ORG_SEARCH.getValue(),
        httpRequest.body().asJson(),
        orgRequest -> {
          new BaseRequestValidator().validateSearchRequest((Request) orgRequest);
          return null;
        },
        null,
        null,
        getAllRequestHeaders(httpRequest),
        ProjectUtil.EsType.organisation.getTypeName(),
        httpRequest);
  }

  public CompletionStage<Result> searchV2(Http.Request httpRequest) {
    return handleSearchRequest(
        searchHandlerActor,
        ActorOperations.ORG_SEARCH_V2.getValue(),
        httpRequest.body().asJson(),
        orgRequest -> {
          new BaseRequestValidator().validateSearchRequest((Request) orgRequest);
          return null;
        },
        null,
        null,
        getAllRequestHeaders(httpRequest),
        ProjectUtil.EsType.organisation.getTypeName(),
        httpRequest);
  }

  public CompletionStage<Result> searchOrgHierarchy(Http.Request httpRequest) {
      return handleSearchRequest(
              searchHandlerActor,
              ActorOperations.ORG_HIERARCHY_SEARCH.getValue(),
              httpRequest.body().asJson(),
              orgRequest -> {
                  new OrgRequestValidator().validateHierarchySearchRequest((Request) orgRequest);
                  return null;
              },
              null,
              null,
              getAllRequestHeaders(httpRequest),
              ProjectUtil.EsType.organisation.getTypeName(),
              httpRequest);
  }

  public CompletionStage<Result> searchOrgHierarchyMinistry(Http.Request httpRequest) {
    return handleSearchRequest(
            searchHandlerActor,
            ActorOperations.ORG_HIERARCHY_MINISTRY_SEARCH.getValue(),
            httpRequest.body().asJson(),
            orgRequest -> {
              Request request = (Request) orgRequest;
              Object filtersObj = request.get(JsonKey.FILTERS);
              if (filtersObj == null) {
                request.getRequest().put(JsonKey.FILTERS, ministryFilters);
              }
              new OrgRequestValidator().validateHierarchySearchRequest((Request) orgRequest);
              return null;
            },
            null,
            null,
            getAllRequestHeaders(httpRequest),
            ProjectUtil.EsType.organisation.getTypeName(),
            httpRequest);
  }

  public CompletionStage<Result> searchOrgHierarchyState(Http.Request httpRequest) {
    return handleSearchRequest(
            searchHandlerActor,
            ActorOperations.ORG_HIERARCHY_STATE_SEARCH.getValue(),
            httpRequest.body().asJson(),
            orgRequest -> {
              Request request = (Request) orgRequest;
              Object filtersObj = request.get(JsonKey.FILTERS);
              if (filtersObj == null) {
                request.getRequest().put(JsonKey.FILTERS, stateFilters);
              }
              new OrgRequestValidator().validateHierarchySearchRequest((Request) orgRequest);
              return null;
            },
            null,
            null,
            getAllRequestHeaders(httpRequest),
            ProjectUtil.EsType.organisation.getTypeName(),
            httpRequest);
  }
}
