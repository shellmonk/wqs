package wqs.db;

/**
 * Every monthly RANGE-partitioned table and the column it is partitioned on.
 * Phase 3 adds "wqCountDay".
 */
public enum PartitionedTable {

    WQ_WEB_ITEM("wqWebItem", "occurredAt"),
    WQ_CC_ITEM("wqCcItem", "occurredAt"),
    WQ_SESSION("wqSession", "startedAt"),
    WQ_PLAY("wqPlay", "startedAt"),
    WQ_WEB_HIGH_LEVEL("wqWebHighLevel", "occurredAt"),
    WQ_CC_HIGH_LEVEL("wqCcHighLevel", "occurredAt");

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
