plugins {
    java
}

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            includeEngines("junit-jupiter", "jqwik")
        }
        // Testcontainers needs a reachable Docker daemon; fail loudly if absent.
        systemProperty("testcontainers.reuse.enable", "true")
    }
}
