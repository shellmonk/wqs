package wqs.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import wqs.db.RawSqlJustified;

/**
 * Each rule names the spec decision it enforces. Per spec section 8.5, a rule that cannot be
 * traced to a numbered decision must not be added.
 */
class ArchitectureTest {

    // Rules marked allowEmptyShould(true) target packages that do not exist until phases 2-3,
    // so ArchUnit would otherwise fail them for matching zero classes. They go live then.

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("wqs");
    }

    /** Spec 2.2 rules 2 and 3: jOOQ types stay in the query layer. */
    @Test
    void api_does_not_depend_on_jooq() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("wqs.api..")
                .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", "wqs.db.generated..")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    /** Spec 2.3 rule 1: MapStruct mappers belong on the inside of the boundary. */
    @Test
    void mappers_do_not_live_in_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("wqs.api..")
                .should().beAnnotatedWith("org.mapstruct.Mapper")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    /** Spec 9 control 3: security by schema -- /web cannot reach the operator table. */
    @Test
    void web_layer_cannot_touch_the_operator_item_table() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("wqs.api.web..", "wqs.read.web..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("WqCcItem")
                .allowEmptyShould(true);
        rule.check(classes);
    }

    /** Spec 2.1: no regression to the superseded JPA-plus-raw-SQL pattern. */
    @Test
    void nothing_depends_on_spring_data_jpa() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAPackage("org.springframework.data.jpa..");
        rule.check(classes);
    }

    /** Spec 10: jobs are entry points, never collaborators. */
    @Test
    void nothing_depends_on_job_classes() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("wqs.jobs..")
                .should().dependOnClassesThat().resideInAPackage("wqs.jobs..");
        rule.check(classes);
    }

    /** Spec 2.2 rule 1: plain-SQL API use requires an explicit, reviewed justification. */
    @Test
    void string_based_sql_requires_justification() {
        // classes().should(...), NOT noClasses(): noClasses() negates the condition, which would
        // turn this condition's violation events into passes and make the rule unable to fail.
        ArchRule rule = classes()
                .that().areNotAnnotatedWith(RawSqlJustified.class)
                // Generated jOOQ code uses plain SQL for sequence defaults; it is outside the
                // intent of the rule, which governs hand-written code.
                .and().resideOutsideOfPackage("wqs.db.generated..")
                .should(callPlainSqlApi());
        rule.check(classes);
    }

    /**
     * Flags any call whose resolved target is annotated {@code @org.jooq.PlainSQL}: jOOQ marks
     * exactly its plain-SQL API (DSL.field(String), DSLContext.execute(String),
     * .from(String), .where(String, ...), ...) with that annotation.
     */
    private static ArchCondition<JavaClass> callPlainSqlApi() {
        return new ArchCondition<>("call the jOOQ plain-SQL API (@org.jooq.PlainSQL)") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getCodeUnitCallsFromSelf().stream()
                        .filter(call -> call.getTarget().resolveMember()
                                .map(m -> m.isAnnotatedWith("org.jooq.PlainSQL"))
                                .orElse(false))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                item,
                                "plain-SQL API used in " + call.getOriginOwner().getFullName()
                                        + " -- annotate with @RawSqlJustified and explain why"
                                        + " (spec 2.2 rule 1): " + call.getDescription())));
            }
        };
    }
}
