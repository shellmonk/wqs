package wqs.db;

/**
 * Every monthly RANGE-partitioned table and the column it is partitioned on.
 * Phase 3 adds "wqSession", "wqPlay", the high-level indexes and "wqCountDay".
 */
public enum PartitionedTable {

    WQ_WEB_ITEM("wqWebItem", "occurredAt"),
    WQ_CC_ITEM("wqCcItem", "occurredAt");

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
