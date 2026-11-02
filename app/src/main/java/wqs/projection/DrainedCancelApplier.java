package wqs.projection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;

/**
 * Applies one drained cancel inside a SAVEPOINT of the projection transaction (NESTED). If it
 * fails, only the cancel's own partial effects roll back; the batch -- including the original
 * whose arrival drained it -- carries on. A separate bean so the NESTED boundary goes through the
 * Spring proxy (a self-call from the writer would bypass it).
 */
@Component
public class DrainedCancelApplier {

    private final CancellationApplier cancellationApplier;

    public DrainedCancelApplier(CancellationApplier cancellationApplier) {
        this.cancellationApplier = cancellationApplier;
    }

    @Transactional(propagation = Propagation.NESTED)
    public void apply(ProjectionItem drainedCancel) {
        cancellationApplier.apply(drainedCancel);
    }
}
