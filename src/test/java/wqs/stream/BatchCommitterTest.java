package wqs.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import wqs.dto.IngestResult;
import wqs.dto.StreamBatchResult;
import wqs.dto.StreamMessage;
import wqs.exception.BatchItemRejectedException;
import wqs.exception.RejectedException;
import wqs.service.StreamIngestService;

/**
 * How a batch is committed, and every way it falls back. The service is mocked; the decoder is
 * the real one, fed the captured messages. End-to-end behaviour (offsets, dead-letter rows) is
 * covered by {@link StreamIngestTest}.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(10) // every retry loop must end; a broken one fails here instead of hanging the build
class BatchCommitterTest {

    private static final String FEED = "wallet-transactions";

    @Mock StreamIngestService streamIngestService;

    private final StreamMessageDecoder streamMessageDecoder = spy(new StreamMessageDecoder(new ObjectMapper()));
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final RecordingProgress progress = new RecordingProgress();
    private BatchCommitter batchCommitter;

    @BeforeEach
    void setUp() {
        batchCommitter = new BatchCommitter(FEED, streamMessageDecoder, streamIngestService,
                new StreamMetrics(new SimpleMeterRegistry()), progress, active::get);
    }

    @Test
    void aBatchCommitsInOneTransactionWithItsLastOffset() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenReturn(new StreamBatchResult(3, 0, 0));

        batchCommitter.commit(batch(1, 2, 3));

        InOrder order = inOrder(streamIngestService);
        order.verify(streamIngestService).preparePartitions(anyList());
        order.verify(streamIngestService).ingestBatch(eq(FEED), anyList());
        verify(streamIngestService, never()).ingestOne(anyString(), anyLong(), any());
        assertThat(progress.committed).containsExactly(3L);
    }

    @Test
    void aTransientFailureRetriesTheWholeBatch() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(new StreamBatchResult(2, 0, 0));

        batchCommitter.commit(batch(1, 2));

        verify(streamIngestService, times(2)).ingestBatch(eq(FEED), anyList());
        verify(streamIngestService, never()).ingestOne(anyString(), anyLong(), any());
        assertThat(progress.committed).containsExactly(2L);
    }

    @Test
    void aRejectedMessageSendsTheBatchOneByOneAndIsDeadLetteredAlone() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList()))
                .thenThrow(new BatchItemRejectedException(2, rejection()));
        when(streamIngestService.ingestOne(eq(FEED), anyLong(), any())).thenReturn(IngestResult.STORED);
        when(streamIngestService.ingestOne(eq(FEED), eq(2L), any())).thenThrow(rejection());

        batchCommitter.commit(batch(1, 2, 3));

        verify(streamIngestService).recordRejected(eq(FEED), eq(2L), any(), eq("currency-mismatch"), anyString());
        verify(streamIngestService, never()).recordRejected(eq(FEED), eq(1L), any(), anyString(), anyString());
        assertThat(progress.committed).containsExactly(1L, 2L, 3L);
    }

    @Test
    void anyOtherBatchFailureFallsBackToOneByOne() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));
        when(streamIngestService.ingestOne(eq(FEED), anyLong(), any())).thenReturn(IngestResult.STORED);

        batchCommitter.commit(batch(1, 2));

        verify(streamIngestService, times(2)).ingestOne(eq(FEED), anyLong(), any());
        assertThat(progress.committed).containsExactly(1L, 2L);
    }

    @Test
    void theFallbackSkipsMessagesAlreadyCommitted() throws IOException {
        progress.committed(1);
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));
        when(streamIngestService.ingestOne(eq(FEED), anyLong(), any())).thenReturn(IngestResult.STORED);

        batchCommitter.commit(batch(1, 2, 3));

        verify(streamIngestService, never()).ingestOne(eq(FEED), eq(1L), any());
        assertThat(progress.committed).containsExactly(1L, 2L, 3L);
    }

    @Test
    void anUnreadableMessageIsDeadLetteredByHashInTheFallback() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));
        when(streamIngestService.ingestOne(eq(FEED), anyLong(), any())).thenReturn(IngestResult.STORED);
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);

        batchCommitter.commit(List.of(message(1), new ReceivedMessage(2, garbage)));

        verify(streamIngestService).recordUnreadable(FEED, 2, garbage, "unreadable", "not valid JSON");
        verify(streamIngestService, never()).ingestOne(eq(FEED), eq(2L), any());
        assertThat(progress.committed).containsExactly(1L, 2L);
    }

    @Test
    void aDecoderBugMakesTheMessageUnreadableInsteadOfStoppingTheWorker() throws IOException {
        ReceivedMessage poison = message(1);
        doThrow(new NullPointerException()).when(streamMessageDecoder).decode(poison.body());
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));

        batchCommitter.commit(List.of(poison));

        verify(streamIngestService).recordUnreadable(FEED, 1, poison.body(), "unreadable", "decoder failed: NullPointerException");
        assertThat(progress.committed).containsExactly(1L);
    }

    @Test
    void aWriteThatFailsForGoodIsDeadLetteredWithItsFields() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));
        when(streamIngestService.ingestOne(eq(FEED), eq(1L), any())).thenThrow(new DataIntegrityViolationException("value"));

        batchCommitter.commit(batch(1));

        // The error names the exception, never the database's message: it can quote values.
        verify(streamIngestService).recordRejected(eq(FEED), eq(1L), any(StreamMessage.class), eq("write-failed"),
                eq("DataIntegrityViolationException"));
        assertThat(progress.committed).containsExactly(1L);
    }

    @Test
    void whenItsFieldsCannotBeStoredTheDeadLetterIsHashOnly() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenThrow(new IllegalStateException("bug"));
        when(streamIngestService.ingestOne(eq(FEED), eq(1L), any())).thenThrow(new DataIntegrityViolationException("value"));
        doThrow(new DataIntegrityViolationException("payload")).when(streamIngestService)
                .recordRejected(eq(FEED), eq(1L), any(), eq("write-failed"), anyString());
        ReceivedMessage m = message(1);

        batchCommitter.commit(List.of(m));

        verify(streamIngestService).recordUnreadable(eq(FEED), eq(1L), eq(m.body()), eq("write-failed"),
                startsWith("DataIntegrityViolationException"));
        assertThat(progress.committed).containsExactly(1L);
    }

    @Test
    void anEndedTermStopsRetryingAndCommitsNothing() throws IOException {
        when(streamIngestService.ingestBatch(eq(FEED), anyList())).thenAnswer(invocation -> {
            active.set(false); // the lock is lost while the batch is in flight
            throw new CannotAcquireLockException("deadlock");
        });

        batchCommitter.commit(batch(1, 2));

        verify(streamIngestService, times(1)).ingestBatch(eq(FEED), anyList());
        verify(streamIngestService, never()).ingestOne(anyString(), anyLong(), any());
        assertThat(progress.committed).isEmpty();
    }

    // --- helpers ---

    private static RejectedException rejection() {
        return new RejectedException("currency-mismatch", "currency differs from the session's currency");
    }

    private static List<ReceivedMessage> batch(long... offsets) throws IOException {
        List<ReceivedMessage> batch = new ArrayList<>();
        for (long offset : offsets) {
            batch.add(message(offset));
        }
        return batch;
    }

    /** The captured transfer, with an id of its own so each message is distinct. */
    private static ReceivedMessage message(long offset) throws IOException {
        String json = new String(StreamMessageDecoderTest.fixture("transfer-out.json"), StandardCharsets.UTF_8)
                .replace("\"id\": \"5962\"", "\"id\": \"" + (9000 + offset) + "\"");
        return new ReceivedMessage(offset, json.getBytes(StandardCharsets.UTF_8));
    }

    /** The consumer's side of {@link BatchCommitter.Progress}: the offsets committed, in order. */
    private static final class RecordingProgress implements BatchCommitter.Progress {
        final List<Long> committed = new ArrayList<>();

        @Override
        public long lastOffset() {
            return committed.isEmpty() ? -1 : committed.getLast();
        }

        @Override
        public void committed(long offset) {
            committed.add(offset);
        }
    }
}
