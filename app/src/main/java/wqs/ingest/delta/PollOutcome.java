package wqs.ingest.delta;

/**
 * One {@link DeltaPoller#runOnce} run.
 *
 * @param pages pages fetched
 * @param rows  rows fetched, poison included
 * @param cliff the run ended on a timestamp cliff (plan D7)
 */
public record PollOutcome(int pages, int rows, boolean cliff) {}
