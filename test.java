import com.unboundid.scim2.common.BaseScimResource;
import com.unboundid.scim2.common.messages.ErrorResponse;
import com.unboundid.scim2.common.messages.ListResponse;
import com.unboundid.scim2.common.messages.PatchRequest;
import com.unboundid.scim2.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import de.bund.bamf.debev.ng.idm.oig.client.scim.ScimErrorException;
import de.bund.bamf.debev.ng.idm.oig.client.scim.ScimResourceType;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Component
public class OigScimClient {

    private final RestClient rest;

    public OigScimClient(RestClient oigRestClient) {
        this.rest = oigRestClient;
    }

    public <R extends BaseScimResource> R create(ScimResourceType type, Class<R> resourceClass, R resource) {
        return call(() -> rest.post()
                .uri(type.remotePath())
                .body(resource)
                .retrieve()
                .body(resourceClass));
    }

    public <R extends BaseScimResource> R get(ScimResourceType type, Class<R> resourceClass, String id) {
        return call(() -> rest.get()
                .uri(type.remotePath() + "/{id}", id)
                .retrieve()
                .body(resourceClass));
    }

    public <R extends BaseScimResource> ListResponse<R> search(ScimResourceType type, Class<R> resourceClass,
                                                              @Nullable String filter, int startIndex, int count) {
        ParameterizedTypeReference<ListResponse<R>> listType = ParameterizedTypeReference.forType(
                ResolvableType.forClassWithGenerics(ListResponse.class, resourceClass).getType());

        return call(() -> rest.get()
                .uri(builder -> builder.path(type.remotePath())
                        .queryParamIfPresent("filter", Optional.ofNullable(filter))
                        .queryParam("startIndex", startIndex)
                        .queryParam("count", count)
                        .build())
                .retrieve()
                .body(listType));
    }

    public <R extends BaseScimResource> R patch(ScimResourceType type, Class<R> resourceClass,
                                                String id, PatchRequest patchRequest) {
        return call(() -> rest.patch()
                .uri(type.remotePath() + "/{id}", id)
                .body(patchRequest)
                .retrieve()
                .body(resourceClass));
    }

    public void delete(ScimResourceType type, String id) {
        call(() -> rest.delete()
                .uri(type.remotePath() + "/{id}", id)
                .retrieve()
                .toBodilessEntity());
    }

    private <T> T call(Supplier<@Nullable T> request) {
        try {
            return Objects.requireNonNull(request.get(), "OIG hat eine leere Antwort geliefert");
        } catch (RestClientResponseException e) {
            throw ScimErrorException.from(toErrorResponse(e));
        } catch (ResourceAccessException e) {
            log.error("OIG nicht erreichbar", e);
            throw ScimErrorException.badGateway("OIG ist nicht erreichbar.");
        }
    }

    private ErrorResponse toErrorResponse(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        try {
            return JsonUtils.getObjectReader()
                    .forType(ErrorResponse.class)
                    .readValue(e.getResponseBodyAsString());
        } catch (RuntimeException parseFailure) {
            log.warn("OIG-Fehlerantwort ({}) ist kein SCIM-JSON: {}", status, e.getResponseBodyAsString());
            ErrorResponse error = new ErrorResponse(status);
            error.setDetail("OIG hat mit Status %d geantwortet.".formatted(status));
            return error;
        }
    }
}
