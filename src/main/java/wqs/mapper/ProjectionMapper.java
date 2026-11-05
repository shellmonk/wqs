package wqs.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import wqs.dto.PlayView;
import wqs.dto.SessionView;
import wqs.dto.Source;
import wqs.dto.TxView;
import wqs.entity.PlayEntity;
import wqs.entity.SessionEntity;
import wqs.entity.Totals;
import wqs.entity.TransactionEntity;

/**
 * Entities to the response views. A session or play is shown with the {@link Totals} the request
 * asked for ({@code SessionEntity.totals(excludeCancelled)}).
 */
@Mapper(componentModel = "spring")
public interface ProjectionMapper {

    @Mapping(target = "txId", source = "sourceId")
    @Mapping(target = "kind", source = "source")
    TxView toView(TransactionEntity transaction);

    @Mapping(target = "kind", constant = SessionView.KIND)
    @Mapping(target = "batchId", source = "session.batchId")
    @Mapping(target = "created", source = "session.startedAt")
    @Mapping(target = "endedAt", source = "session.endedAt")
    @Mapping(target = "productId", source = "session.productId")
    @Mapping(target = "productType", source = "session.productType")
    @Mapping(target = "currency", source = "session.currency")
    @Mapping(target = "totalPlay", source = "totals.totalPlays")
    @Mapping(target = "totalWin", source = "totals.totalWins")
    @Mapping(target = "amount", source = "totals.net")
    @Mapping(target = "playCount", source = "playCount")
    SessionView toView(SessionEntity session, Totals totals, int playCount, List<TxView> txs, List<PlayView> subitems);

    @Mapping(target = "subBatchId", source = "play.subBatchId")
    @Mapping(target = "playTimestamp", source = "play.startedAt")
    @Mapping(target = "playAmount", source = "totals.totalPlays")
    @Mapping(target = "winAmount", source = "totals.totalWins")
    @Mapping(target = "amount", source = "totals.net")
    PlayView toView(PlayEntity play, Totals totals, List<TxView> txs);

    /** The stored source code as its name. */
    default String kind(short source) {
        for (Source s : Source.values()) {
            if (s.code() == source) {
                return s.name();
            }
        }
        return "UNKNOWN";
    }
}
