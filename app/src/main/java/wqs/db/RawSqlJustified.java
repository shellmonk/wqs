package wqs.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that is permitted to build SQL from strings, as an explicit and reviewed
 * exception to spec section 2.2 rule 1.
 *
 * The {@code value} must state WHY jOOQ's typed DSL cannot express the statement. Adding this
 * annotation to silence the architecture test without a real reason defeats the purpose of
 * adopting jOOQ at all.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RawSqlJustified {
    String value();
}
