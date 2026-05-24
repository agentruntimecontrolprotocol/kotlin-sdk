plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.nmcp.aggregation)
}

allprojects {
    group = "dev.arcp"
    version = "1.1.0"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        enableExperimentalRules.set(false)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        toolVersion = "1.23.8"
        // :samples is illustrative protocol code, not library code; relax
        // size/complexity rules there while keeping naming + forbidden
        // patterns enforced. See config/detekt/detekt-samples.yml.
        val configFile =
            if (project.name == "samples") {
                "config/detekt/detekt-samples.yml"
            } else {
                "config/detekt/detekt.yml"
            }
        config.setFrom(rootProject.files(configFile))
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        ignoreFailures = false
        source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}

// ---------------------------------------------------------------------------
// Sonatype Central Portal — aggregates all maven-publish publications from
// subprojects and publishes to central.sonatype.com using the OSSRH_USERNAME /
// OSSRH_PASSWORD secrets (Central Portal user-token credentials).
// ---------------------------------------------------------------------------
nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("OSSRH_USERNAME")
        password = providers.environmentVariable("OSSRH_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(":lib"))
}

// ---------------------------------------------------------------------------
// Kover aggregation: roll :lib unit tests and the :tests integration suite
// into a single coverage report and enforce a floor on every CI run (#56).
//
// Thresholds: the floor is set to what `./gradlew test koverXmlReport
// koverVerify` reports as the current measurement, so CI passes today and any
// regression below the floor fails the build. Ratchet the numbers up as more
// of the runtime dispatch surface lands real tests — `coverage.line.minimum`
// and `coverage.branch.minimum` are the only knobs to touch.
// ---------------------------------------------------------------------------
dependencies {
    kover(project(":lib"))
    kover(project(":tests"))
}

kover {
    reports {
        verify {
            rule {
                groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.APPLICATION
                bound {
                    minValue = providers.gradleProperty("kover.minLineCoverage")
                        .map { it.toInt() }
                        .orElse(75)
                    coverageUnits =
                        kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
                bound {
                    minValue = providers.gradleProperty("kover.minBranchCoverage")
                        .map { it.toInt() }
                        .orElse(45)
                    coverageUnits =
                        kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}
