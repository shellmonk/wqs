package wqs.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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
import wqs.config.ReaderJdbc;
import wqs.config.ReaderTx;
import wqs.api.archfixture.JdbcClientController;
import wqs.domain.archfixture.SqlTimestampValue;
import wqs.api.archfixture.ParameterReadingAdvice;
import wqs.api.cc.archfixture.CcParameterReader;
import wqs.api.web.archfixture.ParameterReadingWebController;
import wqs.api.web.archfixture.UriBuilderWebController;
import wqs.api.web.archfixture.WebRequestParameterReader;
import wqs.read.archfixture.QualifiedReader;
import wqs.read.archfixture.UnqualifiedCtorParamReader;
import wqs.read.archfixture.UnqualifiedInjectedFieldReader;
import wqs.read.archfixture.UnqualifiedMethodParamReader;
import wqs.read.archfixture.ReaderJdbcFieldWithoutTxReader;
import wqs.read.archfixture.ReaderJdbcWithoutTxReader;
import wqs.read.archfixture.TransactionClientParamReader;

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

    /**
     * Spec 2.2 rules 2 and 3 (refactor D5a): database access stays in the query and write layers.
     * The edge (api) and the bottom layer (domain) touch neither Spring JDBC nor JDBC itself.
     */
    @Test
    void api_and_domain_do_not_depend_on_jdbc() {
        jdbcRule().check(classes);
    }

    /** Proves the rule above can fail, for each package and each forbidden API. */
    @Test
    void jdbc_rule_rejects_an_api_class_using_jdbc_client_and_a_domain_class_using_java_sql() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(JdbcClientController.class, SqlTimestampValue.class);
        assertThatThrownBy(() -> jdbcRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JdbcClientController")
                .hasMessageContaining("org.springframework.jdbc.core.simple.JdbcClient")
                .hasMessageContaining("SqlTimestampValue")
                .hasMessageContaining("java.sql.Timestamp");
    }

    private static ArchRule jdbcRule() {
        return noClasses()
                .that().resideInAnyPackage("wqs.api..", "wqs.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..");
    }

    /**
     * Refactor D5c: jOOQ was removed (SQL is JdbcClient text); nothing may depend on it again.
     * Belt and braces: the rule below, and no jOOQ class on the test runtime classpath.
     */
    @Test
    void nothing_depends_on_jooq() {
        noJooqRule().check(classes);
        assertThatThrownBy(() -> Class.forName("org.jooq.DSLContext"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * Proves the rule above can fail. jOOQ is off the classpath, so the fixture is compiled here,
     * against a one-interface stand-in for {@code org.jooq.DSLContext}, and imported from disk.
     */
    @Test
    void jooq_rule_rejects_a_class_depending_on_jooq(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path src = dir.resolve("src");
        java.nio.file.Path out = dir.resolve("out");
        java.nio.file.Files.createDirectories(src.resolve("org/jooq"));
        java.nio.file.Files.createDirectories(src.resolve("wqs/read/archfixture"));
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Path stub = java.nio.file.Files.writeString(src.resolve("org/jooq/DSLContext.java"),
                "package org.jooq; public interface DSLContext {}");
        java.nio.file.Path reader = java.nio.file.Files.writeString(
                src.resolve("wqs/read/archfixture/JooqReader.java"),
                "package wqs.read.archfixture; public class JooqReader { org.jooq.DSLContext dsl; }");
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        int status = javac.run(null, null, null, "-d", out.toString(), stub.toString(), reader.toString());
        org.assertj.core.api.Assertions.assertThat(status).as("fixture compiles").isZero();

        JavaClasses fixture = new ClassFileImporter().importPath(out);
        assertThatThrownBy(() -> noJooqRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JooqReader")
                .hasMessageContaining("org.jooq.DSLContext");
    }

    private static ArchRule noJooqRule() {
        return noClasses()
                .that().resideOutsideOfPackage("org.jooq..")
                .should().dependOnClassesThat().resideInAPackage("org.jooq..");
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
     * Spec 9 control 1: /web's request binding has no path to request parameters for the user.
     * Nothing in {@code wqs.api} except {@code wqs.api.cc} (which takes userId as a parameter by
     * design, spec 7.1) may read raw request parameters, through the servlet API
     * (ServletRequest and subtypes) or Spring's (WebRequest and subtypes); the query string is
     * included since it carries the same parameters unparsed, and so is
     * {@code ServletUriComponentsBuilder}, which re-reads the current request. The only way a
     * parameter reaches a /web handler is a declared, named {@code @RequestParam}, and
     * {@code WebProjectionSecurityTest.web_controller_binds_only_allowlisted_parameters} pins
     * which ones.
     */
    @Test
    void web_layer_never_reads_raw_request_parameters() {
        webParameterRule().check(classes);
    }

    /** Proves the rule above can fail, branch by branch, each violation naming its member. */
    @Test
    void web_parameter_rule_rejects_each_way_of_reading_parameters() {
        assertParameterRuleRejects(ParameterReadingWebController.class, "getParameter(", "getQueryString(");
        assertParameterRuleRejects(WebRequestParameterReader.class, "getParameter(");
        assertParameterRuleRejects(UriBuilderWebController.class, "fromCurrentRequest(");
        assertParameterRuleRejects(ParameterReadingAdvice.class, "getParameterMap(");
    }

    /** wqs.api.cc is out of scope: checked alongside a violator, only the violator is reported. */
    @Test
    void web_parameter_rule_accepts_cc_reading_its_userId_parameter() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(CcParameterReader.class, ParameterReadingWebController.class);
        assertThatThrownBy(() -> webParameterRule().check(fixture))
                .hasMessageContaining("ParameterReadingWebController")
                .hasMessageNotContaining("CcParameterReader");
    }

    private static void assertParameterRuleRejects(Class<?> fixtureClass, String... members) {
        JavaClasses fixture = new ClassFileImporter().importClasses(fixtureClass);
        var thrown = assertThatThrownBy(() -> webParameterRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(fixtureClass.getSimpleName());
        for (String member : members) {
            thrown.hasMessageContaining(member);
        }
    }

    private static final java.util.Set<String> RAW_PARAMETER_METHODS = java.util.Set.of(
            "getParameter", "getParameterMap", "getParameterValues", "getParameterNames", "getQueryString");

    private static ArchRule webParameterRule() {
        return noClasses()
                .that().resideInAPackage("wqs.api..")
                .and().resideOutsideOfPackage("wqs.api.cc..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                        "the target is a ServletRequest/WebRequest parameter or query-string accessor") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return RAW_PARAMETER_METHODS.contains(call.getTarget().getName())
                                && (call.getTargetOwner().isAssignableTo(jakarta.servlet.ServletRequest.class)
                                        || call.getTargetOwner().isAssignableTo(
                                                org.springframework.web.context.request.WebRequest.class));
                    }
                })
                .orShould().dependOnClassesThat().areAssignableTo(
                        org.springframework.web.servlet.support.ServletUriComponentsBuilder.class);
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

    /**
     * Spec 10 "datasource routing is explicit" (ruling D11, refactor D5b): reads never land on the
     * writer. Every JdbcClient injected into {@code wqs.read..} carries {@code @ReaderJdbc}.
     */
    @Test
    void read_layer_injects_only_the_reader_jdbc_client() {
        readerJdbcRule().check(classes);
    }

    /** Proves the rule above can fail, and that each branch of it does. */
    @Test
    void read_layer_rule_rejects_each_kind_of_unqualified_jdbc_client() {
        assertRejected(readerJdbcRule(), UnqualifiedCtorParamReader.class, "UnqualifiedCtorParamReader");
        assertRejected(readerJdbcRule(), UnqualifiedMethodParamReader.class, "run");
        assertRejected(readerJdbcRule(), UnqualifiedInjectedFieldReader.class, "injectedJdbc");
    }

    @Test
    void read_layer_rule_accepts_a_plain_field_assigned_from_a_qualified_constructor() {
        readerJdbcRule().check(new ClassFileImporter().importClasses(QualifiedReader.class));
    }

    @Test
    void read_layer_rule_accepts_a_plain_method_taking_the_transaction_client() {
        readerJdbcRule().check(new ClassFileImporter().importClasses(TransactionClientParamReader.class));
    }

    /**
     * Spec 10 / P-3 (one snapshot per request): a {@code @ReaderJdbc} statement outside a
     * {@code @ReaderTx} transaction autocommits in its own snapshot. So every {@code wqs.read..}
     * class that injects the reader client also injects the reader template. This cannot prove
     * each statement runs inside {@code readerTx.execute(..)}; it rules out a reader with no way to
     * open the transaction at all.
     */
    @Test
    void read_layer_injects_the_reader_tx_wherever_it_injects_the_reader_jdbc_client() {
        readerTxRule().check(classes);
    }

    /** Proves the rule above can fail: no template at all, or only the writer's. */
    @Test
    void reader_tx_rule_rejects_a_reader_client_without_the_reader_tx() {
        assertRejected(readerTxRule(), ReaderJdbcWithoutTxReader.class, "ReaderJdbcWithoutTxReader");
        assertRejected(readerTxRule(), ReaderJdbcFieldWithoutTxReader.class, "ReaderJdbcFieldWithoutTxReader");
        readerTxRule().check(new ClassFileImporter().importClasses(QualifiedReader.class));
    }

    private static void assertRejected(ArchRule rule, Class<?> fixtureClass, String memberName) {
        JavaClasses fixture = new ClassFileImporter().importClasses(fixtureClass);
        assertThatThrownBy(() -> rule.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(memberName)
                .hasMessageContaining(fixtureClass.getSimpleName());
    }

    private static ArchRule readerJdbcRule() {
        // classes().should(...), NOT noClasses(): noClasses() negates the condition, which would
        // turn this condition's violation events into passes and make the rule unable to fail.
        return classes()
                .that().resideInAPackage("wqs.read..")
                .should(injectOnlyQualifiedJdbcClient());
    }

    private static ArchRule readerTxRule() {
        return classes()
                .that().resideInAPackage("wqs.read..")
                .should(injectReaderTxWithReaderJdbc());
    }

    private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";
    private static final String TRANSACTION_TEMPLATE = "org.springframework.transaction.support.TransactionTemplate";

    /** An injected field: {@code @Autowired} or {@code @Inject}. */
    private static boolean injected(com.tngtech.archunit.core.domain.JavaField f) {
        return f.isAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                || f.isAnnotatedWith("jakarta.inject.Inject");
    }

    /**
     * Injection points only: constructors and @Autowired/@Inject/@Bean methods. A plain method
     * taking the caller's transaction client ("tx") is not an injection point.
     */
    private static boolean injectionPoint(com.tngtech.archunit.core.domain.JavaCodeUnit u) {
        return u instanceof com.tngtech.archunit.core.domain.JavaConstructor
                || u.isAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                || u.isAnnotatedWith("jakarta.inject.Inject")
                || u.isAnnotatedWith("org.springframework.context.annotation.Bean");
    }

    /** True when the class has an injection point of {@code type} carrying {@code qualifier}. */
    private static boolean injects(JavaClass transaction, String type, Class<? extends java.lang.annotation.Annotation> qualifier) {
        boolean field = transaction.getFields().stream()
                .filter(ArchitectureTest::injected)
                .anyMatch(f -> f.getRawType().isAssignableTo(type) && f.isAnnotatedWith(qualifier));
        boolean parameter = transaction.getCodeUnits().stream()
                .filter(ArchitectureTest::injectionPoint)
                .flatMap(u -> u.getParameters().stream())
                .anyMatch(p -> p.getRawType().isAssignableTo(type) && p.isAnnotatedWith(qualifier));
        return field || parameter;
    }

    private static ArchCondition<JavaClass> injectOnlyQualifiedJdbcClient() {
        return new ArchCondition<>("take JdbcClient only as @ReaderJdbc injection parameters, "
                + "and never as an @Autowired/@Inject field without @ReaderJdbc") {
            @Override
            public void check(JavaClass transaction, ConditionEvents events) {
                // A plain field assigned from a @ReaderJdbc constructor parameter is the normal
                // pattern; only injected fields are checked.
                transaction.getFields().stream()
                        .filter(f -> f.getRawType().isAssignableTo(JDBC_CLIENT))
                        .filter(ArchitectureTest::injected)
                        .filter(f -> !f.isAnnotatedWith(ReaderJdbc.class))
                        .forEach(f -> events.add(SimpleConditionEvent.violated(
                                f, f.getDescription() + " is an injected JdbcClient without @ReaderJdbc")));
                transaction.getCodeUnits().stream()
                        .filter(ArchitectureTest::injectionPoint)
                        .forEach(u -> {
                            for (int i = 0; i < u.getRawParameterTypes().size(); i++) {
                                if (u.getRawParameterTypes().get(i).isAssignableTo(JDBC_CLIENT)
                                        && !u.getParameters().get(i).isAnnotatedWith(ReaderJdbc.class)) {
                                    events.add(SimpleConditionEvent.violated(
                                            u, u.getDescription() + " takes a JdbcClient parameter without @ReaderJdbc"));
                                }
                            }
                        });
            }
        };
    }

    private static ArchCondition<JavaClass> injectReaderTxWithReaderJdbc() {
        return new ArchCondition<>("inject a @ReaderTx TransactionTemplate wherever they inject a @ReaderJdbc JdbcClient") {
            @Override
            public void check(JavaClass transaction, ConditionEvents events) {
                if (injects(transaction, JDBC_CLIENT, ReaderJdbc.class)
                        && !injects(transaction, TRANSACTION_TEMPLATE, ReaderTx.class)) {
                    events.add(SimpleConditionEvent.violated(transaction, transaction.getName()
                            + " injects @ReaderJdbc JdbcClient but no @ReaderTx TransactionTemplate:"
                            + " its reads would autocommit, one snapshot per statement"));
                }
            }
        };
    }
}
