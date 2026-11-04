package wqs.db;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.YearMonth;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Which months may get a partition. A row outside [floor, now + maxMonthsAhead] has a garbage
 * timestamp: it is dead-lettered and creates no partition, so bad input cannot create thousands.
 */
@Validated
@ConfigurationProperties("wqs.partition")
public record PartitionProperties(
        @NotNull @DefaultValue("2010-01") YearMonth floor,
        @Min(0) @DefaultValue("12") int maxMonthsAhead) {}
