package wqs.entity;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A session's or a play's totals over a set of its rows: how many rows, the absolute amount played
 * and won, and the net ({@code totalWins - totalPlays}). Each session and play keeps it twice: over
 * all rows, and over rows not cancelled.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Totals {

    private int txCount;
    private BigDecimal totalPlays;
    private BigDecimal totalWins;
    private BigDecimal net;
}
