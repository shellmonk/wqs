package wqs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A session at levels 1 and 2, with its stored totals: over all rows, or over rows not cancelled
 * when {@code excludeCancelledTransactions} is set. {@code amount} is the net ({@code totalWin -
 * totalPlay}); {@code created} is the session's start.
 *
 * <p>Level 1 nests the session's transactions under {@code txs}. Level 2 nests plays under
 * {@code subitems}, and {@code txs} then holds only the transactions outside any play.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionView(
        String kind,
        String batchId,
        Instant created,
        Instant endedAt,
        String productId,
        String productType,
        String currency,
        BigDecimal totalPlay,
        BigDecimal totalWin,
        BigDecimal amount,
        int playCount,
        List<TxView> txs,
        List<PlayView> subitems) implements HighLevelEntry {

    public static final String KIND = "SESSION";
}
