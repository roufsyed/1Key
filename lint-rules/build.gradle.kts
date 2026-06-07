plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

// JVM-only module hosting custom Lint detectors. Loaded into the app's lint
// classpath via `lintPublish(project(":lint-rules"))` in app/build.gradle.kts.
//
// Why a separate module: Lint registries (`IssueRegistry`) must be packaged in
// a JAR with a `Lint-Registry-v2` manifest attribute that the AGP lint runner
// discovers at lint time. A regular Android library module doesn't produce
// that JAR shape. The recommended pattern is a `kotlin("jvm")` module that
// also applies `com.android.lint` to wire AGP's lint plumbing for tests.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.lint.api)
    // Test deps for the Detector contract - lint-tests provides the
    // `lint().files(...).run().expect(...)` DSL so we can lock the
    // detector to specific input → output mappings.
    testImplementation(libs.junit)
    testImplementation(libs.lint.tests)
    testImplementation(libs.lint.checks)
}

tasks.withType<Jar>().configureEach {
    // Tells AGP's lint runner where to find the IssueRegistry. Without this
    // attribute the lint module loads but no issues register, so the rule
    // would silently do nothing in CI.
    manifest {
        attributes["Lint-Registry-v2"] = "com.onekey.lint.LockAwareIssueRegistry"
    }
}
