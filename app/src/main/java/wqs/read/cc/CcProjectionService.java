package wqs.read.cc;

import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import wqs.read.Level0Filter;
import wqs.read.Level0Request;
import wqs.read.ProjectionPage;
import wqs.read.ProjectionReader;

/**
 * The /cc read entry point. Composes the reader with {@link CcLevel0Query} only. {@code userId}
 * is the operator-supplied request parameter (spec 7.1). /cc is deny-by-default via
 * {@code wqs.cc.access} (spec 9); real authentication/authorization is open question O-14.
 */
@Service
public class CcProjectionService {

    private final ProjectionReader reader;
    private final CcLevel0Query query;

    public CcProjectionService(ProjectionReader reader, CcLevel0Query query) {
        this.reader = reader;
        this.query = query;
    }

    /** Arguments are validated by the caller: page and pageSize >= 1, from <= to. */
    public ProjectionPage level0(long userId, Instant from, Instant to,
            boolean excludeCancelled, Set<String> productTypes, Set<String> transactionTypes,
            long page, long pageSize) {
        return reader.readLevel0(query, new Level0Request(
                new Level0Filter(userId, productTypes, Set.of(), excludeCancelled), transactionTypes,
                from, to, page, pageSize));
    }
}
