package wqs;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.Builder;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * Guards the annotationProcessor ordering required by the spec (section 2.4).
 * If lombok-mapstruct-binding is missing or the processors are ordered wrongly,
 * MapStruct cannot see Lombok-generated accessors and silently maps null.
 */
class ProcessorChainTest {

    @Getter
    @Builder
    static class Source {
        private final String name;
        private final int count;
    }

    record Target(String name, int count) {}

    @Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
    interface ChainMapper {
        ChainMapper INSTANCE = Mappers.getMapper(ChainMapper.class);

        Target toTarget(Source source);
    }

    @Test
    void mapstruct_sees_lombok_generated_accessors() {
        Source source = Source.builder().name("wqs").count(3).build();

        Target target = ChainMapper.INSTANCE.toTarget(source);

        assertThat(target.name()).isEqualTo("wqs");
        assertThat(target.count()).isEqualTo(3);
    }
}
