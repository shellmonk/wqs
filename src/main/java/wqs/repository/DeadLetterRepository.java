package wqs.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import wqs.entity.DeadLetterEntity;

/** {@code "wqDeadLetter"}: input that could not be projected. */
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntity, Long> {

    List<DeadLetterEntity> findBySourceId(String sourceId);

    List<DeadLetterEntity> findByBodySha256(String bodySha256);
}
