package wqs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import wqs.TestTransactions;
import wqs.dto.IncomingTransaction;
import wqs.dto.IngestResult;
import wqs.dto.Source;
import wqs.exception.RejectedException;
import wqs.repository.PlayRepository;
import wqs.repository.SessionRepository;
import wqs.repository.TransactionRepository;

/**
 * Which writes one row leads to. The SQL itself (totals, least/greatest, the currency check) is
 * checked against Postgres in {@link ProjectionWriterIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProjectionWriterTest {

    private static final long SESSION_ID = 7;

    @Mock PartitionService partitionService;
    @Mock TransactionRepository transactionRepository;
    @Mock SessionRepository sessionRepository;
    @Mock PlayRepository playRepository;
    @InjectMocks ProjectionWriter projectionWriter;

    @Test
    void storesTheRowInItsPartitionFirst() {
        IncomingTransaction t = row().build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);

        assertThat(projectionWriter.write(t)).isEqualTo(IngestResult.STORED);

        InOrder order = inOrder(partitionService, transactionRepository);
        order.verify(partitionService).ensure(TransactionRepository.TABLE, t.created());
        order.verify(transactionRepository).insertIfAbsent(t);
    }

    @Test
    void aRowWithoutABatchTouchesNoSession() {
        IncomingTransaction t = row().build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);

        projectionWriter.write(t);

        verifyNoInteractions(sessionRepository, playRepository);
    }

    @Test
    void aDuplicateAddsNothingToTotals() {
        IncomingTransaction t = row().session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(0);

        assertThat(projectionWriter.write(t)).isEqualTo(IngestResult.ALREADY_STORED);

        verifyNoInteractions(sessionRepository, playRepository);
    }

    @Test
    void aZeroAmountRowAddsNothingToTotals() {
        IncomingTransaction t = row().amount("0").session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);

        assertThat(projectionWriter.write(t)).isEqualTo(IngestResult.STORED);

        verifyNoInteractions(sessionRepository, playRepository);
    }

    @Test
    void aRowWithoutAPlayIsAddedToItsSessionOnly() {
        IncomingTransaction t = row().session("S1", null).build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);
        when(sessionRepository.addRow(any())).thenReturn(Optional.of(SESSION_ID));

        projectionWriter.write(t);

        verify(sessionRepository).addRow(argThat(c -> c.batchId().equals("S1") && c.plays().compareTo(BigDecimal.ONE) == 0));
        verifyNoInteractions(playRepository);
        verify(sessionRepository, never()).addPlay(anyLong());
    }

    @Test
    void aNewPlayIsCountedInItsSession() {
        IncomingTransaction t = row().session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);
        when(sessionRepository.addRow(any())).thenReturn(Optional.of(SESSION_ID));
        PlayRepository.Added play = added(true); // built first: it stubs a mock of its own
        when(playRepository.addRow(eq(SESSION_ID), any())).thenReturn(Optional.of(play));

        projectionWriter.write(t);

        verify(sessionRepository).addPlay(SESSION_ID);
    }

    @Test
    void anExistingPlayIsNotCountedAgain() {
        IncomingTransaction t = row().session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);
        when(sessionRepository.addRow(any())).thenReturn(Optional.of(SESSION_ID));
        PlayRepository.Added play = added(false); // built first: it stubs a mock of its own
        when(playRepository.addRow(eq(SESSION_ID), any())).thenReturn(Optional.of(play));

        projectionWriter.write(t);

        verify(sessionRepository, never()).addPlay(anyLong());
    }

    @Test
    void anotherCurrencyThanTheSessionsIsRejected() {
        IncomingTransaction t = row().session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);
        when(sessionRepository.addRow(any())).thenReturn(Optional.empty());

        assertRejected(t, "currency-mismatch");
        verifyNoInteractions(playRepository);
    }

    @Test
    void anotherCurrencyThanThePlaysIsRejected() {
        IncomingTransaction t = row().session("S1", "P1").build();
        when(transactionRepository.insertIfAbsent(t)).thenReturn(1);
        when(sessionRepository.addRow(any())).thenReturn(Optional.of(SESSION_ID));
        when(playRepository.addRow(eq(SESSION_ID), any())).thenReturn(Optional.empty());

        assertRejected(t, "currency-mismatch");
        verify(sessionRepository, never()).addPlay(anyLong());
    }

    @Test
    void aBatchIdOnASourceThatCannotCarryOneIsRejectedBeforeAnyWrite() {
        assertRejected(row().source(Source.PAYMENT).session("S1", null).build(), "batch-on-unbatched-source");
        verifyNoInteractions(partitionService, transactionRepository, sessionRepository, playRepository);
    }

    @Test
    void aSubBatchWithoutABatchIsRejectedBeforeAnyWrite() {
        assertRejected(row().session(null, "P1").build(), "sub-batch-without-batch");
        verifyNoInteractions(partitionService, transactionRepository, sessionRepository, playRepository);
    }

    // --- helpers ---

    private static TestTransactions.Builder row() {
        return TestTransactions.transaction("1-1717").at("2026-10-26T11:00:00Z");
    }

    private void assertRejected(IncomingTransaction t, String reason) {
        assertThatThrownBy(() -> projectionWriter.write(t))
                .isInstanceOfSatisfying(RejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static PlayRepository.Added added(boolean created) {
        PlayRepository.Added added = mock(PlayRepository.Added.class);
        when(added.getCreated()).thenReturn(created);
        return added;
    }
}
