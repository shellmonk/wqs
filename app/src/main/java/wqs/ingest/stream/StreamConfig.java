package wqs.ingest.stream;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import wqs.db.TxTypeRegistry;
import wqs.ingest.DeadLetterStore;
import wqs.projection.ProjectionWriter;
import wqs.projection.StreamPayloadMapper;

/**
 * Production wiring of the stream consumer. Off unless {@code wqs.stream.enabled=true}
 * (env {@code WQS_STREAM_ENABLED=true}), so contexts without a broker start cleanly.
 *
 * <p><b>Exactly one replica may set {@code WQS_STREAM_ENABLED=true}</b> (spec 6.1, ruling R101).
 * Nothing enforces this yet -- single-active-consumer or an advisory lock is deferred -- and two
 * consumers would project the same events and race on the stored offset.
 * {@link StreamDisabledNotice} logs a WARN wherever the consumer is off.
 *
 * <p>A test supplies a fake {@link StreamSource} bean; the broker beans ({@link StreamBrokerConfig})
 * then back off, so the context never connects to a broker.
 */
@Configuration
@ConditionalOnProperty(prefix = "wqs.stream", name = "enabled", havingValue = "true")
@Import(StreamConfig.BrokerSelector.class)
public class StreamConfig {

    /** Defers {@link StreamBrokerConfig} until all other configuration is parsed (see its javadoc). */
    static class BrokerSelector implements DeferredImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[] {StreamBrokerConfig.class.getName()};
        }
    }

    @Bean
    StreamConsumer streamConsumer(
            StreamSource source,
            StreamProperties properties,
            StreamPayloadMapper payloadMapper,
            TxTypeRegistry txTypes,
            ProjectionWriter writer,
            StreamOffsetStore offsetStore,
            MeterRegistry registry,
            Clock clock,
            DeadLetterStore deadLetterStore) {
        return new StreamConsumer(
                source, properties, payloadMapper, txTypes, writer, offsetStore, registry, clock, deadLetterStore);
    }

    @Bean
    StreamLifecycle streamLifecycle(StreamConsumer consumer, StreamProperties properties) {
        return new StreamLifecycle(consumer, properties.batchMaxWait());
    }
}
