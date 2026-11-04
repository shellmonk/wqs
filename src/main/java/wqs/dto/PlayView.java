package wqs.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A play nested in a level-2 session. {@code playTimestamp} is the play's start; {@code amount} its net. */
public record PlayView(
        String subBatchId,
        Instant playTimestamp,
        BigDecimal playAmount,
        BigDecimal winAmount,
        BigDecimal amount,
        List<TxView> txs) {}
