package wqs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import wqs.dto.AggregationLevel;
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
 * What the service asks the repositories for, and how it assembles the page. The queries and the
 * JSON are checked against Postgres in {@code ProjectionApiTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    private static final String OWNER = "1-1717";
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");

    @Mock TransactionRepository transactionRepository;
    @Mock SessionRepository sessionRepository;
    @Mock PlayRepository playRepository;
    @Mock ProjectionMapper projectionMapper;
    @InjectMocks ProjectionService projectionService;

    // --- level 0 ---

    @Test
    void level0AsksForTheRequestedPageNewestFirst() {
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.TRANSACTIONS, 3, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).as("pageNumber is 1-based").isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("created"), Sort.Order.desc("transactionId")));
        verifyNoInteractions(sessionRepository, playRepository);
    }

    @Test
    void level0ReportsTheTotalOfAllPages() {
        TransactionEntity row = mock(TransactionEntity.class);
        TxView view = tx(1, null, null, "TT_BUY");
        when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(1), 41));
        when(projectionMapper.toView(row)).thenReturn(view);

        ProjectionResponse<?> response = projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.TRANSACTIONS, 1, 1));

        assertThat(response.totalRowCount()).isEqualTo(41);
        assertThat(List.<Object>copyOf(response.data())).containsExactly(view);
    }

    // --- levels 1 and 2 ---

    @Test
    void aPageBeyondTheLastIsEmptyWithoutLoadingAnything() {
        when(sessionRepository.countPageEntries(any())).thenReturn(10L);

        ProjectionResponse<?> response = projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.SESSIONS, 2, 10));

        assertThat(response.totalRowCount()).isEqualTo(10);
        assertThat(response.data()).isEmpty();
        verify(sessionRepository, never()).findPageEntries(any(), anyInt(), anyLong());
        verifyNoInteractions(transactionRepository, playRepository);
    }

    @Test
    void transactionsWithoutASessionAreLoadedWithinThePagesTimeSpan() {
        Instant newer = Instant.parse("2026-10-20T00:00:00Z"), older = Instant.parse("2026-10-05T00:00:00Z");
        pageOf(entry(PageEntry.TRANSACTION, 11, newer), entry(PageEntry.TRANSACTION, 12, older));

        projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.SESSIONS, 1, 10));

        verify(transactionRepository).findByIds(OWNER, List.of(11L, 12L), older, newer);
        verifyNoInteractions(playRepository);
    }

    @Test
    void thePageKeepsTheOrderOfItsEntries() {
        Instant t1 = Instant.parse("2026-10-20T00:00:00Z"), t2 = Instant.parse("2026-10-10T00:00:00Z"),
                t3 = Instant.parse("2026-10-05T00:00:00Z");
        pageOf(entry(PageEntry.TRANSACTION, 11, t1), entry(PageEntry.SESSION, 5, t2), entry(PageEntry.TRANSACTION, 12, t3));
        TransactionEntity row11 = transaction(11), row12 = transaction(12);
        // The database returns rows in any order; the page order comes from the entries.
        when(transactionRepository.findByIds(eq(OWNER), anyList(), any(), any())).thenReturn(List.of(row12, row11));
        TxView view11 = tx(11, null, null, "TT_TRANSFER_OUT"), view12 = tx(12, null, null, "TT_TRANSFER_OUT");
        when(projectionMapper.toView(row11)).thenReturn(view11);
        when(projectionMapper.toView(row12)).thenReturn(view12);
        SessionEntity session = session(5, "S5", t2, t2);
        when(sessionRepository.findAllById(List.of(5L))).thenReturn(List.of(session));
        SessionView sessionView = mock(SessionView.class);
        when(projectionMapper.toView(eq(session), any(), anyInt(), any(), any())).thenReturn(sessionView);

        ProjectionResponse<?> response = projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.SESSIONS, 1, 10));

        assertThat(List.<Object>copyOf(response.data())).containsExactly(view11, sessionView, view12);
    }

    @Test
    void sessionRowsAreLoadedWithinTheSessionsSpanAndFilteredByType() {
        Instant start1 = Instant.parse("2026-10-03T00:00:00Z"), end1 = Instant.parse("2026-10-04T00:00:00Z");
        Instant start2 = Instant.parse("2026-10-08T00:00:00Z"), end2 = Instant.parse("2026-10-09T00:00:00Z");
        pageOf(entry(PageEntry.SESSION, 2, start2), entry(PageEntry.SESSION, 1, start1));
        SessionEntity s1 = session(1, "S1", start1, end1), s2 = session(2, "S2", start2, end2);
        when(sessionRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(s2, s1));
        TransactionEntity bet = transaction(21, "TT_BUY"), prize = transaction(22, "PRIZE");
        when(transactionRepository.findSessionRows(eq(OWNER), eq(List.of("S2", "S1")), eq(start1), eq(end2),
                eq(ReadPolicy.WEB.sourceCodes()), eq(true))).thenReturn(List.of(bet, prize));
        TxView betView = tx(21, "S1", null, "TT_BUY");
        when(projectionMapper.toView(bet)).thenReturn(betView);

        projectionService.page(ReadPolicy.WEB, OWNER,
                request(AggregationLevel.SESSIONS, 1, 10, Set.of("TT_BUY")));

        verify(projectionMapper, never()).toView(prize);
        verify(projectionMapper).toView(eq(s1), any(), anyInt(), eq(List.of(betView)), eq(null));
        verifyNoInteractions(playRepository);
    }

    @Test
    void level2PutsRowsUnderTheirPlaysAndSkipsPlaysWithNothingVisible() {
        Instant start = Instant.parse("2026-10-03T00:00:00Z"), end = Instant.parse("2026-10-04T00:00:00Z");
        pageOf(entry(PageEntry.SESSION, 1, start));
        SessionEntity session = session(1, "S1", start, end);
        when(sessionRepository.findAllById(List.of(1L))).thenReturn(List.of(session));
        TransactionEntity inPlay = transaction(31), outside = transaction(32);
        when(transactionRepository.findSessionRows(eq(OWNER), anyList(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(List.of(inPlay, outside));
        TxView inPlayView = tx(31, "S1", "P1", "TT_BUY"), outsideView = tx(32, "S1", null, "TT_BUY");
        when(projectionMapper.toView(inPlay)).thenReturn(inPlayView);
        when(projectionMapper.toView(outside)).thenReturn(outsideView);
        PlayEntity visible = play(1, "P1"), empty = play(1, "P2");
        when(playRepository.findBySessionIdInOrderByStartedAtDescPlayIdDesc(List.of(1L))).thenReturn(List.of(visible, empty));
        PlayView playView = mock(PlayView.class);
        when(projectionMapper.toView(eq(visible), any(), eq(List.of(inPlayView)))).thenReturn(playView);

        projectionService.page(ReadPolicy.WEB, OWNER, request(AggregationLevel.SESSIONS_AND_PLAYS, 1, 10));

        verify(projectionMapper, never()).toView(eq(empty), any(), any());
        verify(projectionMapper).toView(eq(session), any(), anyInt(), eq(List.of(outsideView)), eq(List.of(playView)));
    }

    // --- helpers ---

    private static ProjectionRequest request(AggregationLevel level, int pageNumber, int pageSize) {
        return request(level, pageNumber, pageSize, Set.of());
    }

    private static ProjectionRequest request(AggregationLevel level, int pageNumber, int pageSize, Set<String> transactionTypes) {
        return new ProjectionRequest(FROM, TO, level, false, Set.of(), transactionTypes, pageNumber, pageSize);
    }

    private void pageOf(PageEntry... entries) {
        when(sessionRepository.countPageEntries(any())).thenReturn((long) entries.length);
        when(sessionRepository.findPageEntries(any(), anyInt(), anyLong())).thenReturn(List.of(entries));
    }

    private static PageEntry entry(int kind, long id, Instant at) {
        return new Entry(kind, id, at);
    }

    private record Entry(Integer getKind, Long getId, Instant getAt) implements PageEntry {}

    // Entities have no public constructor (they are only ever read), so tests use lenient mocks:
    // each test reads only some of their fields.

    private static TransactionEntity transaction(long id) {
        return transaction(id, "TT_BUY");
    }

    private static TransactionEntity transaction(long id, String transactionType) {
        TransactionEntity t = mock(TransactionEntity.class, withSettings().strictness(Strictness.LENIENT));
        when(t.getTransactionId()).thenReturn(id);
        when(t.getTransactionType()).thenReturn(transactionType);
        return t;
    }

    private static SessionEntity session(long id, String batchId, Instant startedAt, Instant endedAt) {
        SessionEntity s = mock(SessionEntity.class, withSettings().strictness(Strictness.LENIENT));
        when(s.getSessionId()).thenReturn(id);
        when(s.getBatchId()).thenReturn(batchId);
        when(s.getStartedAt()).thenReturn(startedAt);
        when(s.getEndedAt()).thenReturn(endedAt);
        return s;
    }

    private static PlayEntity play(long sessionId, String subBatchId) {
        PlayEntity p = mock(PlayEntity.class, withSettings().strictness(Strictness.LENIENT));
        when(p.getSessionId()).thenReturn(sessionId);
        when(p.getSubBatchId()).thenReturn(subBatchId);
        return p;
    }

    private static TxView tx(long id, String batchId, String subBatchId, String transactionType) {
        return new TxView("TRANSACTION", Instant.EPOCH, String.valueOf(id), 1, "1717", "AT_MAIN", "0", "AT_MAIN-0",
                batchId, subBatchId, null, transactionType, null, null, null, "EUR", null, null, null, null,
                true, null, null, null, null, null, null, null, "WEB", Instant.EPOCH, false);
    }
}
