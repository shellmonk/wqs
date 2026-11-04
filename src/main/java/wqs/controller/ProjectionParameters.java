package wqs.controller;

import java.time.OffsetDateTime;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import wqs.dto.ProjectionRequest;

/**
 * The contract's query parameters, as bound by Spring MVC; {@link #toRequest()} validates them.
 * {@code from}/{@code to} are ISO-8601 date-times with an offset (a {@code +} must be sent as {@code %2B}).
 */
public record ProjectionParameters(
        @Parameter(description = "Start of the range, inclusive. ISO-8601 with an offset; send + as %2B.",
                required = true, example = "2026-10-01T00:00:00Z")
        @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
        @Parameter(description = "End of the range, exclusive. ISO-8601 with an offset; send + as %2B.",
                required = true, example = "2026-11-01T00:00:00Z")
        @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
        @Parameter(description = "Only these product types (repeat the parameter or separate with commas). At levels 1-2, sessions are listed by their product type.",
                example = "instants")
        List<String> productTypes,
        @Parameter(description = "Only these transaction types (repeat or comma-separate). At levels 1-2, a session is listed when it has a visible transaction of one of them, and its txs show only those.",
                example = "TT_BUY")
        List<String> transactionTypes,
        @Parameter(description = "Leave cancelled transactions out; session and play totals then exclude them too. Default false.")
        Boolean excludeCancelledTransactions,
        @Parameter(description = "0: flat transactions. 1: sessions with their transactions. 2: sessions with plays and their transactions. Default 0.",
                example = "0")
        Integer aggregationLevel,
        @Parameter(description = "1-based page number. Default 1.", example = "1")
        Integer pageNumber,
        @Parameter(description = "Top-level items per page: 1-1000 at level 0, 1-100 at levels 1-2. Default 50.", example = "50")
        Integer pageSize) {

    public ProjectionRequest toRequest() {
        return ProjectionRequest.of(from == null ? null : from.toInstant(), to == null ? null : to.toInstant(),
                aggregationLevel, excludeCancelledTransactions, productTypes, transactionTypes, pageNumber, pageSize);
    }
}
