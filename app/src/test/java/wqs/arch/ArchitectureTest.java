package wqs.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import wqs.config.ReaderDsl;
import wqs.db.RawSqlJustified;
import wqs.api.web.archfixture.ParameterReadingWebController;
import wqs.read.archfixture.QualifiedReader;
import wqs.read.archfixture.UnqualifiedCtorParamReader;
import wqs.read.archfixture.UnqualifiedInjectedFieldReader;
import wqs.read.archfixture.UnqualifiedMethodParamReader;
import wqs.read.web.archfixture.LeakyWebQuery;

/**
 * Each rule names the spec decision it enforces. Per spec section 8.5, a rule that cannot be
 * traced to a numbered decision must not be added.
 */
class ArchitectureTest {

    // No rule carries allowEmptyShould(true): every package they target now has classes, so a rule
    // matching zero classes (a renamed package, say) fails instead of passing hollowly.

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
                .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", "wqs.db.generated..");
        rule.check(classes);
    }

    /** Spec 2.3 rule 1: MapStruct mappers belong on the inside of the boundary. */
    @Test
    void mappers_do_not_live_in_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("wqs.api..")
                .should().beAnnotatedWith("org.mapstruct.Mapper");
        rule.check(classes);
    }

    /**
     * Spec 9 control 3: security by schema -- /web cannot reach operator data. Forbids every
     * generated {@code WqCc*} type (the operator item table, its page index, their records) AND
     * any access to a {@code Tables.WQ_CC_*} field: passing {@code WQ_CC_ITEM} straight to
     * {@code selectFrom(..)} depends on no WqCc type, only on the Tables field.
     */
    @Test
    void web_layer_cannot_touch_operator_tables() {
        webRule().check(classes);
    }

    /** Proves the rule above can fail: a web-layer fixture reading Tables.WQ_CC_ITEM is rejected. */
    @Test
    void web_rule_rejects_a_web_class_touching_cc() {
        JavaClasses fixture = new ClassFileImporter().importClasses(LeakyWebQuery.class);
        assertThatThrownBy(() -> webRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyWebQuery");
    }

    private static ArchRule webRule() {
        return noClasses()
                .that().resideInAnyPackage("wqs.api.web..", "wqs.read.web..")
                .should().dependOnClassesThat().haveSimpleNameStartingWith("WqCc")
                .orShould().accessFieldWhere(operatorTableField());
    }

    /**
     * Spec 9 control 1: /web's request binding has no path to request parameters for the user.
     * Nothing in {@code wqs.api.web} may read raw request parameters -- the only way a parameter
     * reaches a /web handler is a declared, named {@code @RequestParam}, and
     * {@code WebProjectionSecurityTest.web_controller_binds_no_userId_parameter} pins that none
     * of those is userId. Matches calls on ServletRequest or any subtype (HttpServletRequest,
     * wrappers); the query string is included since it carries the same parameters unparsed.
     */
    @Test
    void web_layer_never_reads_raw_request_parameters() {
        webParameterRule().check(classes);
    }

    /** Proves the rule above can fail, on both the parameter API and the query string. */
    @Test
    void web_parameter_rule_rejects_a_web_class_reading_parameters() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ParameterReadingWebController.class);
        assertThatThrownBy(() -> webParameterRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ParameterReadingWebController")
                .hasMessageContaining("getParameter(")
                .hasMessageContaining("getQueryString(");
    }

    private static final java.util.Set<String> RAW_PARAMETER_METHODS = java.util.Set.of(
            "getParameter", "getParameterMap", "getParameterValues", "getParameterNames", "getQueryString");

    private static ArchRule webParameterRule() {
        return noClasses()
                .that().resideInAPackage("wqs.api.web..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                        "the target is a ServletRequest parameter or query-string accessor") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return RAW_PARAMETER_METHODS.contains(call.getTarget().getName())
                                && call.getTargetOwner().isAssignableTo(jakarta.servlet.ServletRequest.class);
                    }
                });
    }

    private static DescribedPredicate<JavaFieldAccess> operatorTableField() {
        return new DescribedPredicate<>("a Tables.WQ_CC_* field") {
            @Override
            public boolean test(JavaFieldAccess access) {
                return access.getTargetOwner().getName().equals("wqs.db.generated.Tables")
                        && access.getTarget().getName().startsWith("WQ_CC_");
            }
        };
    }

    /**
     * Spec 8.5 layering rule / spec 10: {@code api -> read/projection -> domain} and
     * {@code ingest -> projection}. The query and write layers never reach back up into the
     * drivers (ingest) or the edge (api).
     */
    @Test
    void read_and_projection_depend_on_neither_ingest_nor_api() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("wqs.projection..", "wqs.read..")
                .should().dependOnClassesThat().resideInAnyPackage("wqs.ingest..", "wqs.api..");
        rule.check(classes);
    }

    /** Spec 8.5 layering rule: domain is the bottom layer and depends on no other WQS package. */
    @Test
    void domain_depends_on_no_other_wqs_package() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("wqs.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "wqs.api..", "wqs.read..", "wqs.projection..", "wqs.ingest..", "wqs.jobs..",
                        "wqs.db..", "wqs.config..", "wqs.metrics..");
        rule.check(classes);
    }

    /** Spec 8.5 layering rule: nothing depends on api. */
    @Test
    void nothing_depends_on_api() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("wqs.api..")
                .should().dependOnClassesThat().resideInAPackage("wqs.api..");
        rule.check(classes);
    }

    /**
     * Spec 8.5 layering rule: a layering is only a layering without cycles. Checked over the
     * top-level WQS packages, so any new package is covered without editing this rule.
     */
    @Test
    void top_level_packages_are_free_of_cycles() {
        ArchRule rule = slices().matching("wqs.(*)..").should().beFreeOfCycles();
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

    /** Spec 10 "datasource routing is explicit" (ruling D11): reads never land on the writer. */
    @Test
    void read_layer_injects_only_the_reader_dsl() {
        readerDslRule().check(classes);
    }

    /** Proves the rule above can fail, and that each branch of it does. */
    @Test
    void read_layer_rule_rejects_each_kind_of_unqualified_dsl() {
        assertRejected(UnqualifiedCtorParamReader.class, "UnqualifiedCtorParamReader");
        assertRejected(UnqualifiedMethodParamReader.class, "run");
        assertRejected(UnqualifiedInjectedFieldReader.class, "injectedDsl");
    }

    @Test
    void read_layer_rule_accepts_a_plain_field_assigned_from_a_qualified_constructor() {
        readerDslRule().check(new ClassFileImporter().importClasses(QualifiedReader.class));
    }

    @Test
    void read_layer_rule_accepts_a_plain_method_taking_the_transaction_dsl() {
        readerDslRule().check(new ClassFileImporter().importClasses(
                wqs.read.archfixture.TransactionDslParamReader.class));
    }

    private static void assertRejected(Class<?> fixtureClass, String memberName) {
        JavaClasses fixture = new ClassFileImporter().importClasses(fixtureClass);
        assertThatThrownBy(() -> readerDslRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(memberName)
                .hasMessageContaining(fixtureClass.getSimpleName());
    }

    private static ArchRule readerDslRule() {
        // Not noClasses(): see string_based_sql_requires_justification.
        return classes()
                .that().resideInAPackage("wqs.read..")
                .should(injectOnlyQualifiedDsl());
    }

    private static ArchCondition<JavaClass> injectOnlyQualifiedDsl() {
        return new ArchCondition<>("take org.jooq.DSLContext only as @ReaderDsl injection parameters, "
                + "and never as an @Autowired/@Inject field without @ReaderDsl") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                // A plain field assigned from a @ReaderDsl constructor parameter is the normal
                // pattern; only injected fields are checked.
                item.getFields().stream()
                        .filter(f -> f.getRawType().isAssignableTo(org.jooq.DSLContext.class))
                        .filter(f -> f.isAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                                || f.isAnnotatedWith("jakarta.inject.Inject"))
                        .filter(f -> !f.isAnnotatedWith(ReaderDsl.class))
                        .forEach(f -> events.add(SimpleConditionEvent.violated(
                                f, f.getDescription() + " is an injected DSLContext without @ReaderDsl")));
                // Injection points only: constructors and @Autowired/@Inject/@Bean methods. A plain
                // method taking the caller's transaction DSLContext ("tx") is not an injection point.
                item.getCodeUnits().stream()
                        .filter(u -> u instanceof com.tngtech.archunit.core.domain.JavaConstructor
                                || u.isAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                                || u.isAnnotatedWith("jakarta.inject.Inject")
                                || u.isAnnotatedWith("org.springframework.context.annotation.Bean"))
                        .forEach(u -> {
                            for (int i = 0; i < u.getRawParameterTypes().size(); i++) {
                                if (u.getRawParameterTypes().get(i).isAssignableTo(org.jooq.DSLContext.class)
                                        && !u.getParameters().get(i).isAnnotatedWith(ReaderDsl.class)) {
                                    events.add(SimpleConditionEvent.violated(
                                            u, u.getDescription() + " takes a DSLContext parameter without @ReaderDsl"));
                                }
                            }
                        });
            }
        };
    }
}
