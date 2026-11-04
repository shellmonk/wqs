package wqs.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import wqs.dto.IncomingTransaction;
import wqs.dto.StreamMessage;

/** Maps each feed's decoded message to the row it becomes. */
@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "source", constant = "TRANSACTION")
    @Mapping(target = "sourceId", source = "id")
    @Mapping(target = "occurredAt", source = "created")
    @Mapping(target = "balanceAfter", source = "totalPostBalance")
    IncomingTransaction fromStream(StreamMessage message);
}
