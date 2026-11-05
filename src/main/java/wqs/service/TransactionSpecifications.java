package wqs.service;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;
import wqs.dto.ReadPolicy;
import wqs.entity.TransactionEntity;

/** The level-0 filters as composable JPA specifications. */
final class TransactionSpecifications {

    private TransactionSpecifications() {}

    /** An owner's rows in [from, to) that the policy shows: its sources, and never zero amounts. */
    static Specification<TransactionEntity> visibleTo(ReadPolicy policy, String ownerId, Instant from, Instant to) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("ownerId"), ownerId),
                cb.greaterThanOrEqualTo(root.get("created"), from),
                cb.lessThan(root.get("created"), to),
                root.get("source").in(policy.sourceCodes()),
                cb.isFalse(root.get("zeroAmount")));
    }

    static Specification<TransactionEntity> notCancelled() {
        return (root, query, cb) -> cb.isFalse(root.get("cancelled"));
    }

    static Specification<TransactionEntity> productTypeIn(Set<String> productTypes) {
        return (root, query, cb) -> root.get("productType").in(productTypes);
    }

    static Specification<TransactionEntity> typeIn(Set<String> types) {
        return (root, query, cb) -> root.get("transactionType").in(types);
    }
}
