package wqs.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A play nested in a level-2 session (api-contract). {@code playTimestamp} is the play's
 * "startedAt"; the amounts are the stored rollup (all or excl-cancelled variant, plan D11), {@code
 * amount} the stored "net" ({@code winAmount - playAmount}).
 */
public record PlayView(
        String subBatchId,
        Instant playTimestamp,
        BigDecimal playAmount,
        BigDecimal winAmount,
        BigDecimal amount,
        List<TxView> txs) {

    public PlayView {
        txs = List.copyOf(txs);
    }
}
