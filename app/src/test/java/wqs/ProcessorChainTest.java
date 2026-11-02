package wqs;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.Builder;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * Verifies that, with the annotationProcessor chain declared as spec section 2.4 requires
 * (lombok, then lombok-mapstruct-binding, then mapstruct-processor), MapStruct sees
 * Lombok-generated accessors rather than silently mapping null.
 *
 * <p>This is a positive-case check only: it was measured not to fail even with
 * lombok-mapstruct-binding removed on this toolchain (Gradle 8.12, JDK 21, Lombok 1.18.36),
 * so passing here does not prove the binding is load-bearing in this build. The binding is
 * kept regardless, per spec section 2.4, as insurance against the documented failure mode
 * recurring on a future Lombok/MapStruct/JDK upgrade.
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
