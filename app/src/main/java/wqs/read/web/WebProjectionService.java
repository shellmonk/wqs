package wqs.read.web;

import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import wqs.read.Level0Filter;
import wqs.read.Level0Request;
import wqs.read.ProjectionResponse;
import wqs.read.ProjectionReader;
import wqs.read.TxView;

/**
 * The /web read entry point. Composes the reader with {@link WebLevel0Query} only, so /web's
 * dependency chain never includes a cc class (spec 9 control 3). {@code userId} is the already-
 * resolved principal (spec 9 control 1), never a request value.
 */
@Service
public class WebProjectionService {

    private final ProjectionReader reader;
    private final WebLevel0Query query;

    public WebProjectionService(ProjectionReader reader, WebLevel0Query query) {
        this.reader = reader;
        this.query = query;
    }

    /** Arguments are validated by the caller: page and pageSize >= 1, from <= to. */
    public ProjectionResponse<TxView> level0(long userId, Instant from, Instant to,
            boolean excludeCancelled, Set<String> productTypes, Set<String> transactionTypes,
            long page, long pageSize) {
        return ProjectionResponse.ofLevel0(reader.readLevel0(query, new Level0Request(
                new Level0Filter(userId, productTypes, Set.of(), excludeCancelled), transactionTypes,
                from, to, page, pageSize)));
    }
}
