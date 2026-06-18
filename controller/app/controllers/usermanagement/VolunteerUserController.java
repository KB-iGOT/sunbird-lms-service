package controllers.usermanagement;

import akka.actor.ActorRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import controllers.BaseController;
import org.sunbird.actor.user.validator.UserRequestValidator;
import org.sunbird.auth.verifier.AccessTokenValidator;
import org.sunbird.keys.JsonKey;
import org.sunbird.operations.ActorOperations;
import org.sunbird.request.Request;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class VolunteerUserController extends BaseController {

    @Inject
    @Named("user_profile_read_actor")
    private ActorRef userProfileReadActor;

    @Inject
    AccessTokenValidator accessTokenValidator;

    private final ObjectMapper mapper = new ObjectMapper();

    public CompletionStage<Result> volunteerUserRead(Http.Request httpRequest) throws JsonProcessingException {

        Optional<String> authUserToken =
                httpRequest.getHeaders().get(JsonKey.X_AUTHENTICATED_USER_TOKEN);

        final boolean isPrivate = httpRequest.path().contains(JsonKey.PRIVATE);
        return handleRequest(
                userProfileReadActor,
                ActorOperations.GET_VOLUNTEER_USER_PROFILE_V1.getValue(),
                null,
                req -> {
                    Request request = (Request) req;
                    String userId = accessTokenValidator.verifyUserToken(authUserToken.get(),request.getContext());
                    request.getContext().put(JsonKey.PRIVATE, isPrivate);
                    request.getRequest().put("sync", true);
                    request.getRequest().put(JsonKey.USER_ID, userId);
                    request.getRequest().put(JsonKey.IS_NGO, true);
                    new UserRequestValidator().validateVolunteerUserReadRequest(request, authUserToken.get());
                    return null;
                },
                null,
                null,
                false,
                httpRequest);
    }
}
