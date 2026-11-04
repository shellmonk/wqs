package wqs.db;

/**
 * Every monthly RANGE-partitioned table and the column it is partitioned on: the transactions
 * only (the owner-month model's other tables are small and unpartitioned).
 */
public enum PartitionedTable {

    WQ_PRJ_TRANSACTION("wqPrjTransaction", "occurredAt");

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
