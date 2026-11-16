// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlinx.kover")
}

// Static analysis runs the ktlint and detekt command-line jars rather than their Gradle plugins,
// so neither has to keep up with AGP's built-in Kotlin. Both read their rules from the repository:
// .editorconfig for ktlint, config/detekt/ for detekt.
val ktlintCli: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val detektCli: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    ktlintCli(variantOf(libs.ktlint.cli) { classifier("all") })
    detektCli(variantOf(libs.detekt.cli) { classifier("all") })
    // The merged coverage report covers these modules.
    kover(project(":app"))
    kover(project(":camera"))
    kover(project(":media"))
    kover(project(":core:model"))
}

val ktlintPatterns = listOf("**/src/**/*.kt", "**/*.kts", "!**/build/**", "!.gradle/**")
val ktlintInputs = fileTree(rootDir) {
    include("**/src/**/*.kt", "**/*.kts", ".editorconfig")
    exclude("**/build/**", ".gradle/**")
}

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Kotlin sources against the ktlint rules in .editorconfig."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    val report = layout.buildDirectory.file("reports/ktlint/ktlint.xml")
    inputs.files(ktlintInputs).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--relative", "--reporter=plain", "--reporter=checkstyle,output=${report.get().asFile}") + ktlintPatterns
    })
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Fixes what ktlint can fix in Kotlin sources."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(listOf("--format", "--relative") + ktlintPatterns)
}

val detektInputs = listOf(
    "app/src/main/java",
    "camera/src/main/java",
    "media/src/main/java",
    "core/model/src/main/kotlin",
)

fun JavaExec.detektArgs(vararg extra: String) {
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    workingDir = rootDir
    args(
        "--input", detektInputs.joinToString(","),
        "--config", "config/detekt/detekt.yml",
        "--build-upon-default-config",
        "--baseline", "config/detekt/baseline.xml",
        "--jvm-target", "17",
        *extra,
    )
}

val detekt by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs detekt; findings in config/detekt/baseline.xml are tolerated, new ones fail."
    val reports = layout.buildDirectory.dir("reports/detekt")
    inputs.files(detektInputs.map { fileTree(it) }, "config/detekt/detekt.yml", "config/detekt/baseline.xml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(reports)
    detektArgs(
        "--report", "html:${reports.get().file("detekt.html").asFile}",
        "--report", "txt:${reports.get().file("detekt.txt").asFile}",
    )
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Rewrites config/detekt/baseline.xml with the current findings."
    detektArgs("--create-baseline")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs ktlint and detekt."
    dependsOn(ktlintCheck, detekt)
}

// Coverage of the JVM unit tests (debug variant for the Android modules): ./gradlew koverHtmlReportUnit
// writes build/reports/kover/htmlUnit/index.html; koverXmlReportUnit writes the XML CI keeps.
kover {
    currentProject {
        createVariant("unit") {}
    }
    reports {
        filters {
            excludes {
                classes("*.R", "*.R$*", "*.BuildConfig", "*.ComposableSingletons*", "*.driver.*")
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}
