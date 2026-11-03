package wqs.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import wqs.domain.ProjectionTransaction;
import wqs.projection.source.StreamPayload;

/**
 * Lives in the projection package, never api -- a mapper touches both wire and persistence
 * shapes by definition and belongs on the inside of the boundary (spec 2.3 rule 1).
 *
 * unmappedTargetPolicy = ERROR means adding a field to ProjectionTransaction without mapping it
 * fails the build. The type code is mapped as-is (plan D1): no lookup, so mapping never touches
 * the database.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StreamPayloadMapper {

    /** Types whose {@code transactionReference} names the transaction they cancel (spec 4.1). */
    Set<String> CANCEL_TYPES = Set.of("TT_CANCEL", "CANCEL", "TT_TOMBSTONE");

    @Mapping(target = "sourceId", source = "id")
    @Mapping(target = "occurredAt", source = "created")
    @Mapping(target = "balanceAfter", source = "totalPostBalance")
    @Mapping(target = "cancels", expression = "java(cancelsOf(payload))")
    @Mapping(target = "source", expression = "java(wqs.domain.SourceKind.TRANSACTION)")
    @Mapping(target = "detailJson", source = "detail", qualifiedByName = "detailToJson")
    ProjectionTransaction toTransaction(StreamPayload payload);

    /**
     * The cancelled transaction's id, only for a cancel type whose reference differs from the
     * projected transaction's own id: the producer self-references when there is no link. {@code *_UNDO} and
     * {@code *_ROLLBACK} are ordinary projected transactions.
     */
    default String cancelsOf(StreamPayload payload) {
        String reference = payload.transactionReference();
        if (reference == null || reference.isBlank() || !CANCEL_TYPES.contains(payload.type())
                || reference.equals(payload.id())) {
            return null;
        }
        return reference;
    }

    /** MapStruct cannot infer a mapping for an open-ended blob, so it is explicit (spec 2.3 rule 3). */
    @Named("detailToJson")
    default String detailToJson(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return DetailJson.MAPPER.writeValueAsString(detail);
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
