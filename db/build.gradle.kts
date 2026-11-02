plugins {
    `java-library`
    alias(libs.plugins.springDependencyManagement)
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}") }
}

val generatedSrc = layout.buildDirectory.dir("generated-src/jooq")

sourceSets {
    // The generated-sources directory is added to `main` in Task 4, once
    // CodegenRunner can actually populate it. See the note above `generateJooq`.
    create("codegen") {
        java.srcDir("src/codegen/java")
        compileClasspath += configurations.runtimeClasspath.get()
    }
}

dependencies {
    api(libs.jooq)

    "codegenImplementation"(libs.jooq.codegen)
    "codegenImplementation"(libs.jooq.meta)
    "codegenImplementation"(libs.flyway.core)
    "codegenImplementation"(libs.flyway.postgresql)
    "codegenImplementation"(libs.postgresql)
    "codegenImplementation"(libs.testcontainers.postgresql)
}

/**
 * Generates jOOQ classes from the Flyway-migrated schema inside a throwaway
 * Postgres container. Docker is therefore a BUILD dependency, not only a test
 * dependency (spec section 8.3).
 *
 * NOT WIRED INTO compileJava YET: `wqs.codegen.CodegenRunner` does not exist
 * until Task 4, which also adds `generatedSrc` to the `main` source set and
 * `compileJava`'s dependency on this task. Until then this task exists but is
 * not part of the build graph — running it directly will fail, by design.
 */
val generateJooq by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate jOOQ classes from Flyway migrations via Testcontainers"

    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass.set("wqs.codegen.CodegenRunner")
    args(generatedSrc.get().asFile.absolutePath)

    inputs.dir("src/main/resources/db/migration")
    inputs.dir("src/codegen/java")
    outputs.dir(generatedSrc)
}
