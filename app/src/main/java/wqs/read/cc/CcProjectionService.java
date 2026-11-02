package wqs.read.cc;

import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import wqs.domain.AggregationLevel;
import wqs.domain.ViewKind;
import wqs.read.HighLevelAssembler;
import wqs.read.HighLevelEntry;
import wqs.read.HighLevelFilter;
import wqs.read.HighLevelPage;
import wqs.read.HighLevelRequest;
import wqs.read.Level0Filter;
import wqs.read.Level0Request;
import wqs.read.ProjectionResponse;
import wqs.read.ProjectionReader;
import wqs.read.TxView;

/**
 * The /cc read entry point. Composes the reader with {@link CcLevel0Query} only. {@code userId}
 * is the operator-supplied request parameter (spec 7.1). /cc is deny-by-default via
 * {@code wqs.cc.access} (spec 9); real authentication/authorization is open question O-14.
 */
@Service
public class CcProjectionService {

    private final ProjectionReader reader;
    private final CcLevel0Query query;
    private final HighLevelAssembler assembler;
    private final CcHighLevelAssembly assembly;

    public CcProjectionService(ProjectionReader reader, CcLevel0Query query, HighLevelAssembler assembler,
            CcHighLevelAssembly assembly) {
        this.reader = reader;
        this.query = query;
        this.assembler = assembler;
        this.assembly = assembly;
    }

    /** Arguments are validated by the caller: page and pageSize >= 1, from <= to. */
    public ProjectionResponse<TxView> level0(long userId, Instant from, Instant to,
            boolean excludeCancelled, Set<String> productTypes, Set<String> transactionTypes,
            long page, long pageSize) {
        return ProjectionResponse.ofLevel0(reader.readLevel0(query, new Level0Request(
                new Level0Filter(userId, productTypes, Set.of(), excludeCancelled), transactionTypes,
                from, to, page, pageSize)));
    }

    /**
     * A level-1 or level-2 page (spec 7.3): the page index walked and assembled in one reader
     * transaction (ruling R4). {@code transactionTypes} filters only the nested transactions (D11).
     * Arguments are validated by the caller: page and pageSize >= 1, from <= to.
     */
    public ProjectionResponse<HighLevelEntry> highLevel(long userId, AggregationLevel level, Instant from,
            Instant to, boolean excludeCancelled, Set<String> productTypes, Set<String> transactionTypes,
            long page, long pageSize) {
        HighLevelRequest req = new HighLevelRequest(ViewKind.CC, level,
                new HighLevelFilter(userId, productTypes, excludeCancelled), from, to, page, pageSize);
        HighLevelAssembler.Nesting nesting = new HighLevelAssembler.Nesting(level, transactionTypes, excludeCancelled);
        HighLevelPage<HighLevelEntry> result = reader.readHighLevel(req,
                (tx, keys) -> assembler.assemble(tx, assembly, userId, nesting, keys));
        return ProjectionResponse.ofHighLevel(result, page, reader.effectivePageSize(pageSize));
    }
}
