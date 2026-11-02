package wqs.codegen;

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.meta.ColumnDefinition;
import org.jooq.meta.Definition;

/**
 * Makes generated code read like the schema. Every identifier in the migrations is quoted
 * camelCase ("wqWebItem", "occurredAt"), but jOOQ's DefaultGeneratorStrategy assumes
 * snake_case input and runs each name through StringUtils.toCamelCase, which upper-cases the
 * first character and lower-cases the rest. That flattens the humps on every surface:
 * Wqwebitem, WqwebitemRecord, Tables.WQWEBITEM, getOccurredat(). This strategy preserves
 * them:
 *
 * <ul>
 *   <li>class and record names: WqWebItem, WqWebItemRecord
 *   <li>column identifiers: WqWebItem.occurredAt, verbatim from the database
 *   <li>table, sequence, key and index identifiers: Tables.WQ_WEB_ITEM, Sequences.WQ_ITEM_ID_SEQ
 *   <li>record accessors: getOccurredAt(), setOccurredAt()
 * </ul>
 *
 * <p>Member names are not covered -- {@code getJavaMemberName} is left alone -- so the
 * generated record's all-args constructor parameters stay flattened ({@code Long userid}).
 * Nothing names those: records are built field by field through the accessors above.
 *
 * <p>Overriding the getter and setter names needs care rather than avoidance. jOOQ's own
 * documented example returns {@code "get" + definition.getOutputName()}, which yields
 * getoccurredAt() -- a lower-case first letter, so not a JavaBean accessor. MapStruct (spec
 * section 2.3) would not recognise it as a property and would generate a mapper that compiles
 * while mapping nothing. The hazard is the uncapitalised accessor, not the override, so the
 * overrides below capitalise the first character and preserve only the interior humps.
 *
 * <p>See spec section 8.3. GeneratedCodeTest asserts all four surfaces against the real
 * generated classes.
 */
public class AsInDatabaseStrategy extends DefaultGeneratorStrategy {

    /**
     * PascalCase of the database name, carrying over whatever affix jOOQ attaches for the
     * mode. Rather than restate jOOQ's mode table (as of 3.19 Mode.RECORD appends "Record",
     * Mode.DAO "Dao" and Mode.PATH "Path", while Mode.INTERFACE *prefixes* "I" -- see
     * DefaultGeneratorStrategy.getJavaClassName0), ask the default strategy for both the
     * decorated and the undecorated name and splice our hump-preserving name into the
     * position the undecorated one occupied. Prefix or suffix, present mode or future one,
     * the affix survives unchanged.
     */
    @Override
    public String getJavaClassName(Definition definition, Mode mode) {
        String name = definition.getOutputName();
        String decorated = super.getJavaClassName(definition, mode);
        String undecorated = super.getJavaClassName(definition, Mode.DEFAULT);

        // An unnamed catalog or schema has no database name to preserve; jOOQ pins those
        // class names to DefaultCatalog/DefaultSchema, and that answer stands.
        int at = name.isBlank() ? -1 : decorated.indexOf(undecorated);
        if (at < 0) {
            return decorated;
        }
        return decorated.substring(0, at)
                + capitalise(name)
                + decorated.substring(at + undecorated.length());
    }

    @Override
    public String getJavaIdentifier(Definition definition) {
        // Columns verbatim, so a query reads like the schema: WqWebItem.occurredAt.
        if (definition instanceof ColumnDefinition) {
            return definition.getOutputName();
        }

        String name = definition.getOutputName();
        String fromDefault = super.getJavaIdentifier(definition);

        // Everything else becomes UPPER_SNAKE: wqWebItem -> WQ_WEB_ITEM. The default
        // strategy's plain path is just outputName.toUpperCase(), which flattens the humps
        // to WQWEBITEM. Where it answers something else it had a reason we cannot improve on
        // -- DEFAULT_CATALOG and DEFAULT_SCHEMA for an unnamed catalog or schema, an identity
        // borrowing its table's identifier, a Postgres foreign key prefixed with its table to
        // keep it unique -- so that answer stands.
        return fromDefault.equals(name.toUpperCase(getTargetLocale()))
                ? upperSnake(name)
                : fromDefault;
    }

    @Override
    public String getJavaGetterName(Definition definition, Mode mode) {
        return "get" + capitalise(definition.getOutputName());
    }

    @Override
    public String getJavaSetterName(Definition definition, Mode mode) {
        return "set" + capitalise(definition.getOutputName());
    }

    /** {@code wqWebItem -> WqWebItem}, {@code occurredAt -> OccurredAt}. */
    private static String capitalise(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * {@code wqWebItem -> WQ_WEB_ITEM}, {@code wqItemIdSeq -> WQ_ITEM_ID_SEQ},
     * {@code wqItemLocator_user_idx -> WQ_ITEM_LOCATOR_USER_IDX}: an underscore before each
     * hump, then upper-case throughout.
     */
    private static String upperSnake(String name) {
        StringBuilder result = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean needsSeparator =
                    Character.isUpperCase(c) && i > 0 && result.charAt(result.length() - 1) != '_';
            if (needsSeparator) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }
}
