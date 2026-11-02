package wqs.projection;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which product types are eInstant (spec 4.1, plan D3). The eInstant view is selected by product
 * type, and an eInstant item is always sessioned (spec 7.4, P-7): one without a batchId is
 * dead-lettered at ingest ({@code einstant-unsessioned}, plan D7).
 *
 * <p>Matched exactly, like the API's {@code productTypes}. A blank entry fails startup: it could
 * never match (blank product types are normalised to null) and would hide a configuration typo.
 */
@ConfigurationProperties("wqs.einstant")
public record EInstantProperties(@DefaultValue("instants") List<String> productTypes) {

    public EInstantProperties {
        productTypes = productTypes == null ? List.of() : List.copyOf(productTypes);
        for (String type : productTypes) {
            if (type.isBlank()) {
                throw new IllegalArgumentException("wqs.einstant.product-types must not contain a blank entry");
            }
        }
    }

    public boolean isEInstant(String productType) {
        return productType != null && productTypes.contains(productType);
    }
}
