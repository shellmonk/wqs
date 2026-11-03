package wqs.ingest.delta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fetches one page of {@code /wallet/delta} (plan D1): {@code GET {base-url}/wallet/delta?{cursor-param}={since}},
 * the cursor an ISO-8601 UTC instant. Open endpoint, no auth. Every way of not getting a page --
 * connect/read timeout, non-2xx, a body that is not a JSON array -- is a {@link DeltaFetchException}.
 */
@Component
public class DeltaClient {

    private static final String PATH = "/wallet/delta";

    private final RestClient restClient;
    private final DeltaProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public DeltaClient(RestClient.Builder builder, DeltaProperties properties, ObjectMapper objectMapper) {
        this(configure(builder, properties), properties, objectMapper);
    }

    /** For tests: a client bound to a fake server (MockRestServiceServer). */
    DeltaClient(RestClient restClient, DeltaProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private static RestClient configure(RestClient.Builder builder, DeltaProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        builder.requestFactory(factory);
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        return builder.build();
    }

    /** The rows changed at or after {@code since}, in the endpoint's order. */
    public ArrayNode fetch(Instant since) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            throw new DeltaFetchException("wqs.delta.base-url is not set");
        }
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path(PATH).queryParam(properties.cursorParam(), since.toString()).build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Never the response body, nor the exception (its message quotes the body): only the status.
            throw new DeltaFetchException("GET " + PATH + " since " + since + " failed: HTTP "
                    + e.getStatusCode().value() + " (" + e.getClass().getSimpleName() + ")");
        } catch (RestClientException e) {
            throw new DeltaFetchException("GET " + PATH + " since " + since + " failed: " + describe(e));
        }
        JsonNode page;
        try {
            page = body == null ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            // Not the parser's exception: its message quotes the offending body text.
            throw new DeltaFetchException("GET " + PATH + " returned a body that is not JSON (" + describe(e) + ")");
        }
        if (!(page instanceof ArrayNode rows)) {
            throw new DeltaFetchException("GET " + PATH + " returned a body that is not a JSON array");
        }
        return rows;
    }

    /**
     * Class names only, the cause chain included (a timeout reads "ResourceAccessException <-
     * SocketTimeoutException"): exception messages may quote the response, so none is kept.
     */
    private static String describe(Throwable e) {
        StringBuilder text = new StringBuilder(e.getClass().getSimpleName());
        for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            text.append(" <- ").append(cause.getClass().getSimpleName());
        }
        return text.toString();
    }
}
