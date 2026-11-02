package wqs.api;

import wqs.domain.AggregationLevel;

/** A valid aggregation level whose read path does not exist yet (501, D4: levels 1-2 until Phase 5). */
public class LevelNotImplementedException extends RuntimeException {

    private final AggregationLevel level;

    public LevelNotImplementedException(AggregationLevel level) {
        super("aggregationLevel " + level.value() + " is not implemented");
        this.level = level;
    }

    public AggregationLevel level() {
        return level;
    }
}
