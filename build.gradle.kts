plugins {
    java
}

allprojects {
    repositories { mavenCentral() }
}

// The generated type-safe `libs` accessor isn't registered on a subproject until that
// subproject's own build script evaluates, so it can't be read from inside `subprojects {}`
// below. Resolve it generically instead, once, in root's own context.
val testcontainersVersion = project.extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("testcontainers")
    .get()
    .requiredVersion

subprojects {
    apply(plugin = "java")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    // Spring Boot's dependency-management plugin pins org.testcontainers:* to the
    // version named by this property in its BOM, overriding whatever gradle/libs.versions.toml
    // requests. Setting it here is the documented override mechanism (see Spring Boot Gradle
    // plugin docs, "Overriding a managed version") and keeps the catalog as the single source
    // of truth for the actual version number.
    extra["testcontainers.version"] = testcontainersVersion

    tasks.withType<Test>().configureEach {
        val excluded = (project.findProperty("excludeTags") as String?)?.split(",") ?: emptyList()
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik")
            if (excluded.isNotEmpty()) excludeTags(*excluded.toTypedArray())
        }
    }
}
