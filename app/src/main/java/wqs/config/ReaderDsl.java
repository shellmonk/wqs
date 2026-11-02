package wqs.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Selects the read-only {@code DSLContext} (reader pool) over the primary writer one. Reads in
 * {@code wqs.read..} must carry it so they never silently land on the writer (spec 10, ruling D11).
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface ReaderDsl {}
