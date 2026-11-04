package wqs.dto;

/** One message of a stream batch, as the batch transaction handles it. */
public sealed interface StreamBatchItem {

    long offset();

    /** A readable message to project. */
    record Readable(long offset, StreamMessage message) implements StreamBatchItem {}

    /** A message that could not be read: recorded by offset and body hash only. */
    record Unreadable(long offset, byte[] body, String error) implements StreamBatchItem {}

    /** A readable message already known to be refused: recorded with its allowlisted fields. */
    record Refused(long offset, StreamMessage message, String reason, String error) implements StreamBatchItem {}
}
