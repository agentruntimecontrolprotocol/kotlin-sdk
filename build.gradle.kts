plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.nmcp)
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
// Sonatype Central Portal — publishes all maven-publish publications from
// subprojects to central.sonatype.com using the OSSRH_USERNAME / OSSRH_PASSWORD
// secrets (Central Portal user-token credentials).
// ---------------------------------------------------------------------------
nmcp {
    publishAllPublicationsToCentralPortal {
        username = providers.environmentVariable("OSSRH_USERNAME")
        password = providers.environmentVariable("OSSRH_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}
