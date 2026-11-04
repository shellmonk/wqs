package wqs.stream;

/** What became of one stream message; the {@code outcome} tag of {@code wqs.ingest.stream.messages}. */
enum StreamOutcome {
    STORED("stored"),
    ALREADY_STORED("already_stored"),
    DEAD_LETTERED("dead_lettered");

    final String tag;

    StreamOutcome(String tag) {
        this.tag = tag;
    }
}
