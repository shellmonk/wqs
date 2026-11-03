package wqs.domain.archfixture;

/** Fixture: a domain value carrying a java.sql type (must be rejected). */
public record SqlTimestampValue(java.sql.Timestamp at) {}
