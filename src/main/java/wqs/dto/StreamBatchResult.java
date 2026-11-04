package wqs.dto;

/** What one committed stream batch did. */
public record StreamBatchResult(int stored, int alreadyStored, int deadLettered) {}
