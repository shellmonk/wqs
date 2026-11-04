package wqs.read;

import java.util.List;

/**
 * One level-1/2 page (ruling R4): the exact number of high-level rows in the range, and the
 * page's entries as the caller's assembler built them, in entry order. Both come from one
 * reader transaction.
 */
public record HighLevelPage<R>(long totalRowCount, List<R> entries) {

    public HighLevelPage {
        entries = List.copyOf(entries);
    }
}
