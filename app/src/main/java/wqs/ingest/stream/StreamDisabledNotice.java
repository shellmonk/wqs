package wqs.ingest.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Makes a disabled stream consumer visible: a replica that silently ingests nothing looks healthy
 * otherwise. Expected on every replica but one -- exactly one may set
 * {@code WQS_STREAM_ENABLED=true} (spec 6.1, ruling R101).
 */
@Component
@ConditionalOnProperty(prefix = "wqs.stream", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StreamDisabledNotice {

    private static final Logger log = LoggerFactory.getLogger(StreamDisabledNotice.class);

    @EventListener(ApplicationReadyEvent.class)
    public void warn() {
        log.warn("Stream consumer is DISABLED on this replica (wqs.stream.enabled=false): it ingests "
                + "nothing. Exactly one replica must set WQS_STREAM_ENABLED=true.");
    }
}
