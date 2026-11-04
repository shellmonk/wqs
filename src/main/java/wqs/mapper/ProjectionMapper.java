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
import wqs.entity.TransactionEntity;

/**
 * Entities to the response views. Sessions and plays have two mappings each: their totals over all
 * rows, or over rows not cancelled ({@code excludeCancelledTransactions}).
 */
@Mapper(componentModel = "spring")
public interface ProjectionMapper {

    @Mapping(target = "txId", source = "sourceId")
    @Mapping(target = "kind", source = "source")
    @Mapping(target = "created", source = "occurredAt")
    @Mapping(target = "transactionType", source = "type")
    TxView toView(TransactionEntity transaction);

    @Mapping(target = "kind", constant = SessionView.KIND)
    @Mapping(target = "created", source = "session.startedAt")
    @Mapping(target = "totalPlay", source = "session.totalPlays")
    @Mapping(target = "totalWin", source = "session.totalWins")
    @Mapping(target = "amount", source = "session.net")
    @Mapping(target = "playCount", source = "session.playCount")
    SessionView toView(SessionEntity session, List<TxView> txs, List<PlayView> subitems);

    @Mapping(target = "kind", constant = SessionView.KIND)
    @Mapping(target = "created", source = "session.startedAt")
    @Mapping(target = "totalPlay", source = "session.totalPlaysExclCancelled")
    @Mapping(target = "totalWin", source = "session.totalWinsExclCancelled")
    @Mapping(target = "amount", source = "session.netExclCancelled")
    @Mapping(target = "playCount", source = "session.playCountExclCancelled")
    SessionView toViewExclCancelled(SessionEntity session, List<TxView> txs, List<PlayView> subitems);

    @Mapping(target = "playTimestamp", source = "play.startedAt")
    @Mapping(target = "playAmount", source = "play.totalPlays")
    @Mapping(target = "winAmount", source = "play.totalWins")
    @Mapping(target = "amount", source = "play.net")
    PlayView toView(PlayEntity play, List<TxView> txs);

    @Mapping(target = "playTimestamp", source = "play.startedAt")
    @Mapping(target = "playAmount", source = "play.totalPlaysExclCancelled")
    @Mapping(target = "winAmount", source = "play.totalWinsExclCancelled")
    @Mapping(target = "amount", source = "play.netExclCancelled")
    PlayView toViewExclCancelled(PlayEntity play, List<TxView> txs);

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
