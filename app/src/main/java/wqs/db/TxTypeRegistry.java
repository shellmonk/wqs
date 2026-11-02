package wqs.db;

import static wqs.db.generated.Tables.WQ_TX_TYPE;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import wqs.db.generated.tables.records.WqTxTypeRecord;
import wqs.domain.MoneyDirection;

/**
 * The transaction-type code table "wqTxType" (plan D2), cached. Item rows store a type as its
 * {@code typeId}; the wire and the API speak codes ({@code TT_BUY}).
 *
 * <p>{@link #idOf} registers an unseen code as NEUTRAL, so a new producer type is projected and
 * visible rather than dead-lettered. The insert runs in its OWN transaction (REQUIRES_NEW): an id
 * handed to a projection transaction that later rolls back must still name the same code, or a
 * cached id could outlive its row. The insert is {@code on conflict do nothing} followed by a
 * read, so concurrent first sightings of one code agree on its id.
 *
 * <p>Lives in {@code wqs.db} (shared infrastructure) so the read and api layers can resolve
 * filter codes without depending on the projection package. Entries are immutable once written --
 * nothing updates or deletes a type -- so the cache never needs invalidating. A miss always
 * consults the table, so a code another instance registered is found, never re-registered.
 */
@Component
public class TxTypeRegistry {

    private final DSLContext dsl;
    private final TransactionTemplate requiresNew;
    private final Map<String, Short> idsByCode = new ConcurrentHashMap<>();
    private final Map<Short, Entry> byId = new ConcurrentHashMap<>();

    public TxTypeRegistry(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The id of {@code code}, registering it as NEUTRAL if it has never been seen. */
    public short idOf(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("a transaction type code is required");
        }
        Short cached = idsByCode.get(code);
        if (cached != null) {
            return cached;
        }
        WqTxTypeRecord row = find(code);
        if (row == null) {
            row = requiresNew.execute(status -> {
                dsl.insertInto(WQ_TX_TYPE)
                        .set(WQ_TX_TYPE.code, code)
                        .set(WQ_TX_TYPE.direction, MoneyDirection.NEUTRAL.code())
                        .onConflict(WQ_TX_TYPE.code)
                        .doNothing()
                        .execute();
                return find(code);
            });
        }
        return remember(row).typeId();
    }

    /**
     * The ids of those {@code codes} that are registered. An unknown code contributes nothing and
     * is never registered: filters must not grow the table. A cache miss reads through this
     * registry's own (writer) DSL -- the write path's overload; the read path uses
     * {@link #idsOf(DSLContext, Collection)}.
     */
    public Set<Short> idsOf(Collection<String> codes) {
        return idsOf(dsl, codes);
    }

    /**
     * As {@link #idsOf(Collection)}, but a cache miss is read through {@code reader}: the read
     * path's single REPEATABLE READ transaction, so resolving a filter never touches the writer
     * pool nor steps outside the reader's snapshot (M2).
     */
    public Set<Short> idsOf(DSLContext reader, Collection<String> codes) {
        Set<Short> ids = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String code : codes) {
            Short id = idsByCode.get(code);
            if (id != null) {
                ids.add(id);
            } else if (code != null) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            // One statement for every uncached code (final review F4), the array a single bind.
            reader.selectFrom(WQ_TX_TYPE)
                    .where(WQ_TX_TYPE.code.eq(DSL.any(DSL.val(missing.toArray(String[]::new),
                            SQLDataType.CLOB.getArrayDataType()))))
                    .fetch()
                    .forEach(row -> ids.add(remember(row).typeId()));
        }
        return ids;
    }

    /** @throws IllegalArgumentException if no type has this id -- a stored id always exists */
    public MoneyDirection directionOf(short typeId) {
        return entry(dsl, typeId).direction();
    }

    /** @throws IllegalArgumentException if no type has this id -- a stored id always exists */
    public String codeOf(short typeId) {
        return entry(dsl, typeId).code();
    }

    /** As {@link #codeOf(short)}, a cache miss read through {@code reader} (the read path's tx; M2). */
    public String codeOf(DSLContext reader, short typeId) {
        return entry(reader, typeId).code();
    }

    private Entry entry(DSLContext source, short typeId) {
        Entry cached = byId.get(typeId);
        if (cached != null) {
            return cached;
        }
        WqTxTypeRecord row = source.selectFrom(WQ_TX_TYPE).where(WQ_TX_TYPE.typeId.eq(typeId)).fetchOne();
        if (row == null) {
            throw new IllegalArgumentException("no transaction type with id " + typeId);
        }
        return remember(row);
    }

    private WqTxTypeRecord find(String code) {
        return find(dsl, code);
    }

    private static WqTxTypeRecord find(DSLContext source, String code) {
        return source.selectFrom(WQ_TX_TYPE).where(WQ_TX_TYPE.code.eq(code)).fetchOne();
    }

    private Entry remember(WqTxTypeRecord row) {
        Entry entry = new Entry(row.getTypeId(), row.getCode(), MoneyDirection.ofCode(row.getDirection()));
        byId.putIfAbsent(entry.typeId(), entry);
        idsByCode.putIfAbsent(entry.code(), entry.typeId());
        return entry;
    }

    private record Entry(short typeId, String code, MoneyDirection direction) {}
}
