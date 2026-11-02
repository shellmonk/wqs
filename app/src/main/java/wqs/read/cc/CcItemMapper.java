package wqs.read.cc;

import java.time.OffsetDateTime;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import wqs.db.generated.tables.records.WqCcItemRecord;
import wqs.domain.SourceKind;
import wqs.read.ItemEntry;

@Mapper(componentModel = "spring")
public interface CcItemMapper {

    @Mapping(target = "kind", source = "source", qualifiedByName = "kindName")
    @Mapping(target = "occurredAt", source = "occurredAt")
    @Mapping(target = "detail", source = "detail", qualifiedByName = "detailJson")
    ItemEntry toEntry(WqCcItemRecord r);

    @Named("kindName")
    default String kindName(Short source) {
        return SourceKind.ofCode(source).name();
    }

    default java.time.Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    @Named("detailJson")
    default String detailJson(JSONB d) {
        return d == null ? null : d.data();
    }
}
