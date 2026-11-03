package wqs.projection;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * Typed binds for the projection's SQL: a nullable value is sent with its column's type rather
 * than an unknown one, and every instant is bound as a UTC {@code timestamptz}.
 */
final class Binds {

    private Binds() {}

    static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    static SqlParameterValue numeric(BigDecimal value) {
        return new SqlParameterValue(Types.NUMERIC, value);
    }

    static SqlParameterValue timestamptz(OffsetDateTime value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, value);
    }

    static SqlParameterValue timestamptz(Instant value) {
        return timestamptz(value == null ? null : value.atOffset(ZoneOffset.UTC));
    }
}
