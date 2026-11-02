package wqs.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import wqs.domain.ProjectionItem;
import wqs.projection.source.StreamPayload;

/**
 * Lives in the projection package, never api -- a mapper touches both wire and persistence
 * shapes by definition and belongs on the inside of the boundary (spec 2.3 rule 1).
 *
 * unmappedTargetPolicy = ERROR means adding a field to ProjectionItem without mapping it
 * fails the build.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StreamPayloadMapper {

    @Mapping(target = "sourceId", source = "transactionId")
    @Mapping(target = "cancels", source = "cancelsTransactionId")
    @Mapping(target = "source", expression = "java(wqs.domain.SourceKind.TRANSACTION)")
    @Mapping(target = "detailJson", source = "extra", qualifiedByName = "extraToJson")
    ProjectionItem toItem(StreamPayload payload);

    /** MapStruct cannot infer a mapping for an open-ended blob, so it is explicit (spec 2.3 rule 3). */
    @Named("extraToJson")
    default String extraToJson(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        try {
            return DetailJson.MAPPER.writeValueAsString(extra);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialise detail payload", e);
        }
    }

    /** Holder so the interface can keep a single shared ObjectMapper. */
    final class DetailJson {
        static final ObjectMapper MAPPER = new ObjectMapper();

        private DetailJson() {}
    }
}
