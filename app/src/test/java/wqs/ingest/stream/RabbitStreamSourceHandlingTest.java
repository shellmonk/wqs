package wqs.ingest.stream;

import wqs.projection.source.StreamPayload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.stream.Message;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RabbitStreamSourceHandlingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static class Recorder implements StreamSource.StreamHandler {
        final List<Long> messages = new ArrayList<>();
        final List<Long> poison = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final List<Exception> errors = new ArrayList<>();

        @Override
        public void onMessage(StreamPayload payload, long offset) {
            messages.add(offset);
        }

        @Override
        public void onUndeserializable(byte[] body, long offset, Exception error) {
            poison.add(offset);
            bodies.add(body);
            errors.add(error);
        }
    }

    @Test
    void a_body_the_client_cannot_extract_is_poison_not_a_thrown_exception() {
        Message message = mock(Message.class);
        when(message.getBodyAsBinary()).thenThrow(new ClassCastException("AmqpValue is not Data"));
        when(message.getBody()).thenReturn("hello");
        Recorder handler = new Recorder();

        RabbitStreamSource.handleMessage(mapper, message, 12, handler);

        assertThat(handler.messages).isEmpty();
        assertThat(handler.poison).containsExactly(12L);
        assertThat(handler.errors.get(0)).isInstanceOf(ClassCastException.class);
        assertThat(handler.bodies.get(0)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void an_unreadable_body_falls_back_to_an_empty_array() {
        Message message = mock(Message.class);
        when(message.getBodyAsBinary()).thenThrow(new ClassCastException("x"));
        when(message.getBody()).thenThrow(new ClassCastException("y"));
        Recorder handler = new Recorder();

        RabbitStreamSource.handleMessage(mapper, message, 3, handler);

        assertThat(handler.poison).containsExactly(3L);
        assertThat(handler.bodies.get(0)).isEmpty();
    }

    @Test
    void a_valid_body_is_delivered_and_malformed_json_is_poison_with_its_bytes() {
        String json = "{\"transactionId\":\"t\",\"userId\":1,\"occurredAt\":\"2033-09-02T12:00:00Z\","
                + "\"type\":1,\"amount\":1,\"currency\":\"EUR\"}";
        Message ok = mock(Message.class);
        when(ok.getBodyAsBinary()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        Message bad = mock(Message.class);
        when(bad.getBodyAsBinary()).thenReturn("{nope".getBytes(StandardCharsets.UTF_8));
        Recorder handler = new Recorder();

        RabbitStreamSource.handleMessage(mapper, ok, 1, handler);
        RabbitStreamSource.handleMessage(mapper, bad, 2, handler);

        assertThat(handler.messages).containsExactly(1L);
        assertThat(handler.poison).containsExactly(2L);
        assertThat(handler.bodies.get(0)).isEqualTo("{nope".getBytes(StandardCharsets.UTF_8));
    }
}
