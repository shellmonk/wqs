package wqs.read.cc;

import static wqs.db.generated.Tables.WQ_CC_HIGH_LEVEL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import wqs.domain.ViewKind;
import wqs.read.HighLevelFilter;
import wqs.read.HighLevelKey;
import wqs.read.HighLevelQuery;

/**
 * Level-1/2 reads of the cc view's page index (spec 5.6); touches only WQ_CC_HIGH_LEVEL. Every
 * predicate and sort column is in "wqCcHighLevel_cover" -- key ("userId", "occurredAt" desc,
 * "kind" desc, "refId" desc), INCLUDE ("productType", "visibleExclCancelled") -- so both queries
 * are covering index scans.
 */
@Component
public class CcHighLevelQuery implements HighLevelQuery {

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }

    @Override
    public long count(DSLContext tx, HighLevelFilter f, Instant lo, Instant hi) {
        Long n = tx.select(DSL.count())
                .from(WQ_CC_HIGH_LEVEL)
                .where(range(f, lo, hi))
                .fetchOne(0, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<HighLevelKey> keys(
            DSLContext tx, HighLevelFilter f, Instant lo, Instant hi, long offset, int limit) {
        return tx.select(WQ_CC_HIGH_LEVEL.occurredAt, WQ_CC_HIGH_LEVEL.kind, WQ_CC_HIGH_LEVEL.refId)
                .from(WQ_CC_HIGH_LEVEL)
                .where(range(f, lo, hi))
                .orderBy(WQ_CC_HIGH_LEVEL.occurredAt.desc(), WQ_CC_HIGH_LEVEL.kind.desc(),
                        WQ_CC_HIGH_LEVEL.refId.desc())
                .offset(offset)
                .limit(limit)
                .fetch(r -> new HighLevelKey(r.value1().toInstant(), r.value2(), r.value3()));
    }

    private static Condition range(HighLevelFilter f, Instant lo, Instant hi) {
        Condition c = WQ_CC_HIGH_LEVEL.userId.eq(f.userId())
                .and(WQ_CC_HIGH_LEVEL.occurredAt.ge(utc(lo)))
                .and(WQ_CC_HIGH_LEVEL.occurredAt.lt(utc(hi)));
        if (f.excludeCancelled()) {
            c = c.and(WQ_CC_HIGH_LEVEL.visibleExclCancelled.isTrue());
        }
        if (!f.productTypes().isEmpty()) {
            c = c.and(WQ_CC_HIGH_LEVEL.productType.in(f.productTypes()));
        }
        return c;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
