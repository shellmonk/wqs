package wqs.db;

/**
 * Every monthly RANGE-partitioned table and the column it is partitioned on.
 * "wqPrjCountDay" is partitioned on a date, the rest on timestamptz.
 */
public enum PartitionedTable {

    WQ_PRJ_WEB_TRANSACTION("wqPrjWebTransaction", "occurredAt"),
    WQ_PRJ_CC_TRANSACTION("wqPrjCcTransaction", "occurredAt"),
    WQ_PRJ_SESSION("wqPrjSession", "startedAt"),
    WQ_PRJ_PLAY("wqPrjPlay", "startedAt"),
    WQ_PRJ_WEB_HIGH_LEVEL("wqPrjWebHighLevel", "occurredAt"),
    WQ_PRJ_CC_HIGH_LEVEL("wqPrjCcHighLevel", "occurredAt"),
    WQ_PRJ_COUNT_DAY("wqPrjCountDay", "period");

    private final String tableName;
    private final String partitionKey;

    PartitionedTable(String tableName, String partitionKey) {
        this.tableName = tableName;
        this.partitionKey = partitionKey;
    }

    public String tableName() {
        return tableName;
    }

    public String partitionKey() {
        return partitionKey;
    }
}
