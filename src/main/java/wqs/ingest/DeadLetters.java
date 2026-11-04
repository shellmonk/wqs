package wqs.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records input that could not be projected. Never stores a raw message (see the table comment in
 * V1__baseline.sql): an unreadable one by its offset and body hash, a rejected one by its decoded,
 * allowlisted fields.
 */
@Component
public class DeadLetters {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public DeadLetters(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unreadable(String feed, long offset, byte[] body, String reason, String error) {
        insert(feed, offset, null, reason, error, sha256(body), null);
    }

    /** {@code decoded} must hold only allowlisted fields: it is serialised as the payload. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(String feed, long offset, String sourceId, String reason, String error, Object decoded) {
        insert(feed, offset, sourceId, reason, error, null, json(decoded));
    }

    private void insert(String feed, long offset, String sourceId, String reason, String error, String sha, String payload) {
        jdbc.sql("""
                insert into "wqDeadLetter" ("feed", "streamOffset", "sourceId", "reason", "error", "bodySha256", "payload")
                values (:feed, :offset, :sourceId, :reason, :error, :sha, cast(:payload as jsonb))
                """)
                .param("feed", feed)
                .param("offset", offset)
                .param("sourceId", sourceId)
                .param("reason", reason)
                .param("error", error)
                .param("sha", sha)
                .param("payload", payload)
                .update();
    }

    public static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }

    private String json(Object decoded) {
        try {
            return objectMapper.writeValueAsString(decoded);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("decoded messages are records of plain values and always serialise", e);
        }
    }
}
