package wqs.read;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A session as levels 1 and 2 return it (spec 4.1, api-contract). Every figure is the stored
 * rollup (spec 5.5), the all or the excl-cancelled variant per {@code excludeCancelledTransactions}
 * (plan D11), never recomputed on read: {@code amount} is the stored "net" ({@code totalWin -
 * totalPlay}). {@code productId}/{@code productType} are the first transaction's (plan D6); {@code
 * created} is "startedAt". {@code status} is deliberately absent (D12).
 *
 * <p>Level 1 nests the session's transactions under {@code txs} and has no {@code subitems}; level
 * 2 nests plays under {@code subitems}, and {@code txs} then holds only the session's play-less
 * transactions (no subBatchId), omitted when there are none. Null fields are omitted.
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

    public SessionView {
        txs = txs == null ? null : List.copyOf(txs);
        subitems = subitems == null ? null : List.copyOf(subitems);
    }
}
