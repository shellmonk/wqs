package wqs.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wqs.entity.IngestCursorEntity;

/** {@code "wqIngestCursor"}: the last processed position of each feed, stored in the same transaction as what it produced. */
public interface IngestCursorRepository extends JpaRepository<IngestCursorEntity, Long> {

    Optional<IngestCursorEntity> findByFeed(String feed);

    @Modifying
    @Query(nativeQuery = true, value = """
            insert into "wqIngestCursor" ("feed", "streamOffset", "updatedAt") values (:feed, :offset, now())
            on conflict ("feed") do update set "streamOffset" = excluded."streamOffset", "updatedAt" = now()
            """)
    int saveStreamOffset(@Param("feed") String feed, @Param("offset") long offset);
}
