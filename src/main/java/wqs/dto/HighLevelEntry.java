package wqs.dto;

/** A top-level entry at levels 1 and 2: a session, or a transaction that has no session. */
public sealed interface HighLevelEntry permits SessionView, TxView {}
