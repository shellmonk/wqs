package wqs.read.web;

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
 * The /web read entry point. Composes the reader with {@link WebLevel0Query} only, so /web's
 * dependency chain never includes a cc class (spec 9 control 3). {@code userId} is the already-
 * resolved principal (spec 9 control 1), never a request value.
 */
@Service
public class WebProjectionService {

    private final ProjectionReader reader;
    private final WebLevel0Query query;
    private final HighLevelAssembler assembler;
    private final WebHighLevelAssembly assembly;

    public WebProjectionService(ProjectionReader reader, WebLevel0Query query, HighLevelAssembler assembler,
            WebHighLevelAssembly assembly) {
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
        HighLevelRequest req = new HighLevelRequest(ViewKind.WEB, level,
                new HighLevelFilter(userId, productTypes, excludeCancelled), from, to, page, pageSize);
        HighLevelAssembler.Nesting nesting = new HighLevelAssembler.Nesting(level, transactionTypes, excludeCancelled);
        HighLevelPage<HighLevelEntry> result = reader.readHighLevel(req,
                (tx, keys) -> assembler.assemble(tx, assembly, userId, nesting, keys));
        return ProjectionResponse.ofHighLevel(result, page, reader.effectivePageSize(pageSize));
    }
}
