package wqs.projection.source;

import java.time.Instant;
import java.util.Objects;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;

/**
 * One decoded {@code /wallet/delta} row (phase 6, plan D3): the normalised transaction plus what the
 * versioned upsert needs beside it. Lives with {@link StreamPayload} in the projection package so
 * the writer can consume it without depending on the ingest package (spec 8.5 layering).
 *
 * @param ownerType the owner's user type, equal to {@code transaction.ownerType()}; every type is
 *                  stored, the read policy decides what an endpoint shows
 * @param userId    the owner's id, equal to {@code transaction.userId()}
 * @param version   the row's change timestamp ({@code updated}; {@code statusDate} for adjustment
 *                  requests): applied when strictly newer than the stored one (spec 4, D6)
 */
public record DeltaRow(SourceKind source, int ownerType, long userId, Instant version, ProjectionTransaction transaction) {

    public DeltaRow {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.source() != source || transaction.userId() != userId
                || transaction.ownerType() != ownerType) {
            throw new IllegalArgumentException("transaction must carry the row's source, ownerType and userId");
        }
    }
}
