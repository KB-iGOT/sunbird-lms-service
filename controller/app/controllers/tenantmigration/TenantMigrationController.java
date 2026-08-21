package controllers.tenantmigration;

import akka.actor.ActorRef;
import controllers.BaseController;
import java.util.concurrent.CompletionStage;
import javax.inject.Inject;
import javax.inject.Named;
import org.sunbird.operations.ActorOperations;
import play.mvc.Http;
import play.mvc.Result;

/** @author anmolgupta */
public class TenantMigrationController extends BaseController {

  @Inject
  @Named("tenant_migration_actor")
  private ActorRef tenantMigrationActor;

  /**
   * Method to migrate user from one tenant to another.
   *
   * @return Result
   */
  public CompletionStage<Result> userTenantMigrate(Http.Request httpRequest) {
    return handleRequest(
        tenantMigrationActor,
        ActorOperations.USER_TENANT_MIGRATE.getValue(),
        httpRequest.body().asJson(),
        null,
        null,
        null,
        true,
        httpRequest);
  }

  /**
   * Method to migrate user from one tenant to another (v2).
   *
   * @return Result
   */
  public CompletionStage<Result> userTenantMigrateV2(Http.Request httpRequest) {
    return handleRequest(
            tenantMigrationActor,
            ActorOperations.USER_TENANT_MIGRATE_V2.getValue(),
            httpRequest.body().asJson(),
            null,
            null,
            null,
            true,
            httpRequest);
  }
}
