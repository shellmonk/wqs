package wqs.controller;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import wqs.dto.ProjectionRequest;

/**
 * The contract's query parameters, as bound by Spring MVC; {@link #toRequest()} validates them.
 * {@code from}/{@code to} are ISO-8601 date-times with an offset (a {@code +} must be sent as {@code %2B}).
 */
public record ProjectionParameters(
        @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
        List<String> productTypes,
        List<String> transactionTypes,
        Boolean excludeCancelledTransactions,
        Integer aggregationLevel,
        Integer pageNumber,
        Integer pageSize) {

    public ProjectionRequest toRequest() {
        return ProjectionRequest.of(from == null ? null : from.toInstant(), to == null ? null : to.toInstant(),
                aggregationLevel, excludeCancelledTransactions, productTypes, transactionTypes, pageNumber, pageSize);
    }
}
