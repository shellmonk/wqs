package wqs.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Selects the reader-pool {@code TransactionTemplate} (read-only, REPEATABLE READ) over the
 * primary writer one. A read path runs ALL its statements inside one such transaction, so they
 * share one snapshot (spec 10, ruling D11, P-3).
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface ReaderTx {}
