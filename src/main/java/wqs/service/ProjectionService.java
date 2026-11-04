package wqs.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
 *
 * <ul>
 *   <li>Level 0: the owner's transactions, newest first.</li>
 *   <li>Levels 1 and 2: the owner's sessions merged with their transactions that have no session,
 *       newest first. A session carries its transactions (level 1), or its plays with their
 *       transactions (level 2).</li>
 * </ul>
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
    public ProjectionResponse<?> page(ReadPolicy policy, String ownerId, ProjectionRequest request) {
        return request.level() == AggregationLevel.TRANSACTIONS
                ? transactionPage(policy, ownerId, request)
                : sessionPage(policy, ownerId, request);
    }

    // --- level 0 ---

    private ProjectionResponse<TxView> transactionPage(ReadPolicy policy, String ownerId, ProjectionRequest request) {
        Specification<TransactionEntity> filter = TransactionSpecifications.visibleTo(policy, ownerId, request.from(), request.to());
        if (request.excludeCancelled()) {
            filter = filter.and(TransactionSpecifications.notCancelled());
        }
        if (!request.productTypes().isEmpty()) {
            filter = filter.and(TransactionSpecifications.productTypeIn(request.productTypes()));
        }
        if (!request.transactionTypes().isEmpty()) {
            filter = filter.and(TransactionSpecifications.typeIn(request.transactionTypes()));
        }
        Page<TransactionEntity> page = transactions.findAll(filter,
                PageRequest.of(request.pageNumber() - 1, request.pageSize(), NEWEST_FIRST));
        return ProjectionResponse.of(request, page.getTotalElements(), page.map(mapper::toView).getContent());
    }

    // --- levels 1 and 2 ---

    private ProjectionResponse<HighLevelEntry> sessionPage(ReadPolicy policy, String ownerId, ProjectionRequest request) {
        String[] productTypes = request.productTypes().toArray(NONE);
        String[] types = request.transactionTypes().toArray(NONE);
        long total = sessions.countPageEntries(ownerId, request.from(), request.to(), policy.sourceCodeArray(),
                request.excludeCancelled(), productTypes.length == 0, productTypes, types.length == 0, types);
        List<PageEntry> pageEntries = total <= request.offset() ? List.of()
                : sessions.findPageEntries(ownerId, request.from(), request.to(), policy.sourceCodeArray(),
                        request.excludeCancelled(), productTypes.length == 0, productTypes, types.length == 0, types,
                        request.pageSize(), request.offset());

        List<PageEntry> sessionEntries = pageEntries.stream().filter(PageEntry::isSession).toList();
        List<PageEntry> transactionEntries = pageEntries.stream().filter(e -> !e.isSession()).toList();
        Map<Long, SessionView> sessionViews = sessionViews(policy, ownerId, sessionEntries, request);
        Map<Long, TxView> transactionViews = transactionViews(ownerId, transactionEntries);

        List<HighLevelEntry> data = pageEntries.stream()
                .map(e -> e.isSession() ? (HighLevelEntry) sessionViews.get(e.getId()) : transactionViews.get(e.getId()))
                .filter(Objects::nonNull)
                .toList();
        return ProjectionResponse.of(request, total, data);
    }

    /** The page's transactions without a session, looked up within the page's own time span. */
    private Map<Long, TxView> transactionViews(String ownerId, List<PageEntry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        Instant from = earliest(entries, PageEntry::getAt);
        Instant to = latest(entries, PageEntry::getAt);
        return transactions.findByIds(ownerId, entries.stream().map(PageEntry::getId).toList(), from, to).stream()
                .collect(Collectors.toMap(TransactionEntity::getTransactionId, mapper::toView));
    }

    /** The page's sessions with their transactions (level 1), or with their plays and transactions (level 2). */
    private Map<Long, SessionView> sessionViews(ReadPolicy policy, String ownerId, List<PageEntry> entries,
                                                ProjectionRequest request) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        List<Long> sessionIds = entries.stream().map(PageEntry::getId).toList();
        List<SessionEntity> pageSessions = sessions.findAllById(sessionIds);
        Map<String, List<TxView>> rowsByBatch = visibleRowsByBatch(policy, ownerId, pageSessions, request);
        Map<Long, List<PlayEntity>> playsBySession = request.level() == AggregationLevel.SESSIONS_AND_PLAYS
                ? plays.findBySessionIdInOrderByStartedAtDescPlayIdDesc(sessionIds).stream()
                        .collect(Collectors.groupingBy(PlayEntity::getSessionId))
                : Map.of();

        Map<Long, SessionView> views = new LinkedHashMap<>();
        for (SessionEntity session : pageSessions) {
            List<TxView> rows = rowsByBatch.getOrDefault(session.getBatchId(), List.of());
            SessionView view = request.level() == AggregationLevel.SESSIONS
                    ? sessionView(session, rows, null, request)
                    : sessionWithPlays(session, rows, playsBySession.getOrDefault(session.getSessionId(), List.of()), request);
            views.put(session.getSessionId(), view);
        }
        return views;
    }

    /**
     * The visible rows of these sessions, grouped by batch id, newest first. A transaction-type
     * filter applies to the rows shown; the sessions' stored totals stay whole.
     */
    private Map<String, List<TxView>> visibleRowsByBatch(ReadPolicy policy, String ownerId, List<SessionEntity> pageSessions,
                                                         ProjectionRequest request) {
        return transactions.findSessionRows(ownerId, pageSessions.stream().map(SessionEntity::getBatchId).toList(),
                        earliest(pageSessions, SessionEntity::getStartedAt), latest(pageSessions, SessionEntity::getEndedAt),
                        policy.sourceCodes(), !request.excludeCancelled())
                .stream()
                .filter(t -> request.transactionTypes().isEmpty() || request.transactionTypes().contains(t.getType()))
                .map(mapper::toView)
                .collect(Collectors.groupingBy(TxView::batchId, LinkedHashMap::new, Collectors.toList()));
    }

    /** Level 2: plays with their rows under {@code subitems}; {@code txs} holds the rows outside any play. */
    private SessionView sessionWithPlays(SessionEntity session, List<TxView> rows, List<PlayEntity> sessionPlays,
                                         ProjectionRequest request) {
        Map<String, List<TxView>> rowsByPlay = rows.stream().filter(t -> t.subBatchId() != null)
                .collect(Collectors.groupingBy(TxView::subBatchId));
        List<PlayView> playViews = new ArrayList<>();
        for (PlayEntity play : sessionPlays) {
            List<TxView> playRows = rowsByPlay.getOrDefault(play.getSubBatchId(), List.of());
            if (playRows.isEmpty()) {
                continue; // nothing visible in it under this request's filters
            }
            playViews.add(request.excludeCancelled() ? mapper.toViewExclCancelled(play, playRows) : mapper.toView(play, playRows));
        }
        List<TxView> outsidePlays = rows.stream().filter(t -> t.subBatchId() == null).toList();
        return sessionView(session, outsidePlays.isEmpty() ? null : outsidePlays, playViews, request);
    }

    private SessionView sessionView(SessionEntity session, List<TxView> txs, List<PlayView> subitems, ProjectionRequest request) {
        return request.excludeCancelled()
                ? mapper.toViewExclCancelled(session, txs, subitems)
                : mapper.toView(session, txs, subitems);
    }

    private static <T> Instant earliest(List<T> items, Function<T, Instant> time) {
        return items.stream().map(time).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static <T> Instant latest(List<T> items, Function<T, Instant> time) {
        return items.stream().map(time).max(Comparator.naturalOrder()).orElseThrow();
    }
}
