package wqs.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * The data domain {@code [START, END)}: every transaction's {@code "occurredAt"} lies inside it (final-
 * review rulings C1, F2). The API clamps request ranges into it, which is exact only because the
 * partition provisioner refuses, at startup, any window that could create a partition outside it.
 * The transaction tables have no default partition, so no row can exist outside a provisioned month.
 *
 * <p>It also bounds any range decomposition to ~2,760 months, and keeps every bound inside
 * Postgres' timestamptz range.
 */
public final class DataDomain {

    public static final Instant START = Instant.parse("1970-01-01T00:00:00Z");
    public static final Instant END = Instant.parse("2200-01-01T00:00:00Z");

    /** The first month inside the domain. */
    public static final YearMonth FIRST_MONTH = YearMonth.from(START.atOffset(ZoneOffset.UTC));
    /** The first month past the domain: the last provisionable month is the one before it. */
    public static final YearMonth END_MONTH = YearMonth.from(END.atOffset(ZoneOffset.UTC));

    private DataDomain() {}
}
