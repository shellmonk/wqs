package wqs.db;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final String SELECT = """
            select "typeId", "code", "direction" from "wqTxType"
            """;
    private static final String BY_CODE = SELECT + "where \"code\" = ?";
    private static final String BY_CODES = SELECT + "where \"code\" = any(cast(? as text[]))";
    private static final String BY_ID = SELECT + "where \"typeId\" = ?";
    private static final String INSERT = """
            insert into "wqTxType" ("code", "direction") values (?, ?)
            on conflict ("code") do nothing
            """;
    private static final RowMapper<Entry> ROW = (rs, n) -> new Entry(
            rs.getShort("typeId"), rs.getString("code"), MoneyDirection.ofCode(rs.getShort("direction")));

    private final JdbcClient jdbc;
    private final TransactionTemplate requiresNew;
    private final Map<String, Short> idsByCode = new ConcurrentHashMap<>();
    private final Map<Short, Entry> byId = new ConcurrentHashMap<>();

    public TxTypeRegistry(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
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
        Entry row = find(code);
        if (row == null) {
            row = requiresNew.execute(status -> {
                jdbc.sql(INSERT).params(code, MoneyDirection.NEUTRAL.code()).update();
                return find(code);
            });
        }
        return remember(row).typeId();
    }

    /**
     * The ids of those {@code codes} that are registered. An unknown code contributes nothing and
     * is never registered: filters must not grow the table. A cache miss reads through this
     * registry's own (writer) client -- the write path's overload; the read path uses
     * {@link #idsOf(JdbcClient, Collection)}.
     */
    public Set<Short> idsOf(Collection<String> codes) {
        return idsOf(jdbc, codes);
    }

    /**
     * As {@link #idsOf(Collection)}, but a cache miss is read through {@code reader}: the read
     * path's single REPEATABLE READ transaction, so resolving a filter never touches the writer
     * pool nor steps outside the reader's snapshot (M2).
     */
    public Set<Short> idsOf(JdbcClient reader, Collection<String> codes) {
        Set<Short> ids = new LinkedHashSet<>();
        Set<String> missing = cachedIds(codes, ids);
        if (!missing.isEmpty()) {
            // One statement for every uncached code (final review F4), the array a single bind.
            reader.sql(BY_CODES).param(missing.toArray(String[]::new)).query(ROW).list()
                    .forEach(row -> ids.add(remember(row).typeId()));
        }
        return ids;
    }

    private Set<String> cachedIds(Collection<String> codes, Set<Short> ids) {
        Set<String> missing = new LinkedHashSet<>();
        for (String code : codes) {
            Short id = idsByCode.get(code);
            if (id != null) {
                ids.add(id);
            } else if (code != null) {
                missing.add(code);
            }
        }
        return missing;
    }

    /** @throws IllegalArgumentException if no type has this id -- a stored id always exists */
    public MoneyDirection directionOf(short typeId) {
        return entry(jdbc, typeId).direction();
    }

    /** @throws IllegalArgumentException if no type has this id -- a stored id always exists */
    public String codeOf(short typeId) {
        return entry(jdbc, typeId).code();
    }

    /** As {@link #codeOf(short)}, a cache miss read through {@code reader} (the read path's tx; M2). */
    public String codeOf(JdbcClient reader, short typeId) {
        return entry(reader, typeId).code();
    }

    private Entry entry(JdbcClient source, short typeId) {
        Entry cached = byId.get(typeId);
        if (cached != null) {
            return cached;
        }
        Entry row = source.sql(BY_ID).param(typeId).query(ROW).optional()
                .orElseThrow(() -> new IllegalArgumentException("no transaction type with id " + typeId));
        return remember(row);
    }

    private Entry find(String code) {
        return jdbc.sql(BY_CODE).param(code).query(ROW).optional().orElse(null);
    }

    private Entry remember(Entry entry) {
        byId.putIfAbsent(entry.typeId(), entry);
        idsByCode.putIfAbsent(entry.code(), entry.typeId());
        return entry;
    }

    private record Entry(short typeId, String code, MoneyDirection direction) {}
}
