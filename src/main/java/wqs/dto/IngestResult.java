package wqs.dto;

/** What ingesting one {@link IncomingTransaction} did. */
public enum IngestResult {

    /** Stored now, with its session and play totals. */
    STORED,
    /** Stored before (a redelivery): nothing changed. */
    ALREADY_STORED
}
