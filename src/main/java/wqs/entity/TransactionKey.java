package wqs.entity;

import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The primary key of {@code "wqPrjTransactions"}. {@code transactionId} alone is the row's synthetic
 * id; Postgres requires the partition column ({@code created}) in every key of a partitioned table.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TransactionKey implements Serializable {

    private String ownerId;
    private Instant created;
    private Long transactionId;
}
