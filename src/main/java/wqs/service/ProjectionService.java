package wqs.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import wqs.dto.AggregationLevel;
import wqs.dto.HighLevelEntry;
import wqs.dto.PlayView;
import wqs.dto.ProjectionRequest;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.dto.SessionView;
import wqs.dto.TxView;
import wqs.entity.PlayEntity;
import wqs.entity.SessionEntity;
import wqs.entity.TransactionEntity;
import wqs.mapper.ProjectionMapper;
import wqs.repository.PlayRepository;
import wqs.repository.SessionRepository;
import wqs.repository.SessionRepository.PageEntry;
import wqs.repository.TransactionRepository;

/**
 * Serves projection pages. Each request is ONE read-only REPEATABLE READ transaction on the reader
 * pool, so the total and the page come from the same snapshot.
 */
@Service
public class ProjectionService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("transactionId"));
    private static final String[] NONE = new String[0];

    private final TransactionRepository transactions;
    private final SessionRepository sessions;
    private final PlayRepository plays;
    private final ProjectionMapper mapper;

    public ProjectionService(TransactionRepository transactions, SessionRepository sessions, PlayRepository plays,
                             ProjectionMapper mapper) {
        this.transactions = transactions;
        this.sessions = sessions;
        this.plays = plays;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectionResponse<?> page(ReadPolicy policy, long userId, ProjectionRequest request) {
        return request.level() == AggregationLevel.TRANSACTIONS
                ? transactions(policy, userId, request)
                : entries(policy, userId, request);
    }

    // --- level 0 ---

    private ProjectionResponse<TxView> transactions(ReadPolicy policy, long userId, ProjectionRequest r) {
        Specification<TransactionEntity> spec = TransactionSpecifications.visibleTo(policy, userId, r.from(), r.to());
        if (r.excludeCancelled()) {
            spec = spec.and(TransactionSpecifications.notCancelled());
        }
        if (!r.productTypes().isEmpty()) {
            spec = spec.and(TransactionSpecifications.productTypeIn(r.productTypes()));
        }
        if (!r.transactionTypes().isEmpty()) {
            spec = spec.and(TransactionSpecifications.typeIn(r.transactionTypes()));
        }
        Page<TransactionEntity> page = transactions.findAll(spec, PageRequest.of(r.pageNumber() - 1, r.pageSize(), NEWEST_FIRST));
        return ProjectionResponse.of(r, page.getTotalElements(), page.map(mapper::toView).getContent());
    }

    // --- levels 1 and 2 ---

    private ProjectionResponse<HighLevelEntry> entries(ReadPolicy policy, long userId, ProjectionRequest r) {
        String[] productTypes = r.productTypes().toArray(NONE);
        String[] types = r.transactionTypes().toArray(NONE);
        long total = sessions.countPageEntries(userId, policy.ownerType(), r.from(), r.to(), policy.sourceCodes(),
                r.excludeCancelled(), productTypes.length == 0, productTypes, types.length == 0, types);
        List<PageEntry> entries = total <= r.offset() ? List.of()
                : sessions.findPageEntries(userId, policy.ownerType(), r.from(), r.to(), policy.sourceCodes(),
                        r.excludeCancelled(), productTypes.length == 0, productTypes, types.length == 0, types,
                        r.pageSize(), r.offset());

        List<Long> sessionIds = ids(entries, 0);
        Map<Long, SessionView> sessionViews = sessionViews(policy, userId, sessionIds, r);
        Map<Long, TxView> transactionViews = transactionViews(userId, entries);

        List<HighLevelEntry> data = entries.stream()
                .map(e -> e.getKind() == 0 ? (HighLevelEntry) sessionViews.get(e.getId()) : transactionViews.get(e.getId()))
                .filter(Objects::nonNull)
                .toList();
        return ProjectionResponse.of(r, total, data);
    }

    /** The page's transactions without a session, looked up within the page's own time span. */
    private Map<Long, TxView> transactionViews(long userId, List<PageEntry> entries) {
        List<PageEntry> rows = entries.stream().filter(e -> e.getKind() == 1).toList();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Instant from = rows.stream().map(PageEntry::getAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant to = rows.stream().map(PageEntry::getAt).max(Comparator.naturalOrder()).orElseThrow();
        return transactions.findByIds(userId, rows.stream().map(PageEntry::getId).toList(), from, to).stream()
                .collect(Collectors.toMap(TransactionEntity::getTransactionId, mapper::toView));
    }

    /** The page's sessions with their transactions (level 1), or their plays and transactions (level 2). */
    private Map<Long, SessionView> sessionViews(ReadPolicy policy, long userId, List<Long> ids, ProjectionRequest r) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<SessionEntity> found = sessions.findAllById(ids);
        Instant from = found.stream().map(SessionEntity::getStartedAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant to = found.stream().map(SessionEntity::getEndedAt).max(Comparator.naturalOrder()).orElseThrow();
        List<Short> sources = policy.sources().stream().map(s -> s.code()).toList();
        Map<String, List<TxView>> rowsByBatch = transactions.findSessionRows(userId, policy.ownerType(),
                        found.stream().map(SessionEntity::getBatchId).toList(), from, to, sources, !r.excludeCancelled())
                .stream()
                .filter(t -> r.transactionTypes().isEmpty() || r.transactionTypes().contains(t.getType()))
                .map(mapper::toView)
                .collect(Collectors.groupingBy(TxView::batchId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<PlayEntity>> playsBySession = r.level() == AggregationLevel.SESSIONS_AND_PLAYS
                ? plays.findBySessionIdInOrderByStartedAtDescPlayIdDesc(ids).stream()
                        .collect(Collectors.groupingBy(PlayEntity::getSessionId))
                : Map.of();

        Map<Long, SessionView> views = new LinkedHashMap<>();
        for (SessionEntity s : found) {
            List<TxView> rows = rowsByBatch.getOrDefault(s.getBatchId(), List.of());
            views.put(s.getSessionId(), r.level() == AggregationLevel.SESSIONS
                    ? sessionView(s, rows, null, r)
                    : sessionWithPlays(s, rows, playsBySession.getOrDefault(s.getSessionId(), List.of()), r));
        }
        return views;
    }

    private SessionView sessionWithPlays(SessionEntity s, List<TxView> rows, List<PlayEntity> sessionPlays, ProjectionRequest r) {
        Map<String, List<TxView>> rowsByPlay = rows.stream().filter(t -> t.subBatchId() != null)
                .collect(Collectors.groupingBy(TxView::subBatchId));
        List<PlayView> playViews = new ArrayList<>();
        for (PlayEntity p : sessionPlays) {
            List<TxView> playRows = rowsByPlay.getOrDefault(p.getSubBatchId(), List.of());
            if (playRows.isEmpty()) {
                continue; // nothing visible in it under this request's filters
            }
            playViews.add(r.excludeCancelled() ? mapper.toViewExclCancelled(p, playRows) : mapper.toView(p, playRows));
        }
        List<TxView> outsidePlays = rows.stream().filter(t -> t.subBatchId() == null).toList();
        return sessionView(s, outsidePlays.isEmpty() ? null : outsidePlays, playViews, r);
    }

    private SessionView sessionView(SessionEntity s, List<TxView> txs, List<PlayView> subitems, ProjectionRequest r) {
        return r.excludeCancelled() ? mapper.toViewExclCancelled(s, txs, subitems) : mapper.toView(s, txs, subitems);
    }

    private static List<Long> ids(List<PageEntry> entries, int kind) {
        return entries.stream().filter(e -> e.getKind() == kind).map(PageEntry::getId).toList();
    }
}
