package wqs.projection.source;

import java.time.Instant;
import java.util.Objects;
import wqs.domain.ProjectionItem;
import wqs.domain.SourceKind;

/**
 * One decoded {@code /wallet/delta} row (phase 6, plan D3): the normalised item plus what the
 * versioned upsert needs beside it. Lives with {@link StreamPayload} in the projection package so
 * the writer can consume it without depending on the ingest package (spec 8.5 layering).
 *
 * @param ownerType the owner's user type; only 1 (player) is projected (the writer's gate, R1)
 * @param userId    the owner's id, equal to {@code item.userId()}
 * @param version   the row's change timestamp ({@code updated}; {@code statusDate} for adjustment
 *                  requests): applied when strictly newer than the stored one (spec 4, D6)
 */
public record DeltaRow(SourceKind source, int ownerType, long userId, Instant version, ProjectionItem item) {

    public DeltaRow {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(item, "item");
        if (item.source() != source || item.userId() != userId) {
            throw new IllegalArgumentException("item must carry the row's source and userId");
        }
    }
}
