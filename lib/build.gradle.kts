plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.nmcp)
    `java-library`
    `maven-publish`
    signing
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-jvm-default=enable",
        )
    }
}

dependencies {
    // Dokka GFM plugin — emit GitHub-flavored Markdown for the www site,
    // which ingests <lang>-sdk/docs/**/*.md at build time. Output lands in
    // kotlin-sdk/docs/api/ (see the dokka {} block below).
    dokkaPlugin("org.jetbrains.dokka:gfm-plugin:${libs.versions.dokka.get()}")

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.kotlin.logging)

    implementation(libs.slf4j.api)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jose.jwt)
    implementation(libs.json.schema.validator)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Dokka GFM configuration. The www site at /Users/nficano/code/arpc/www
// reads <lang>-sdk/docs/**/*.md at build time, so we redirect the Markdown
// output to ../docs/api/ (kotlin-sdk/docs/api/). The HTML publication is
// disabled because dokka-gfm-plugin and Dokka's default HTML plugin both
// register a `locationProvider` extension, which produces a
// "Conflicting overrides" error if both run in the same generate task.
// Regenerate with: `./gradlew :lib:dokkaGenerate` (or `make docs-api`).
// ---------------------------------------------------------------------------
dokka {
    moduleName.set("arcp")
    dokkaPublications.configureEach {
        outputDirectory.set(rootDir.resolve("docs/api"))
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "arcp"
            from(components["java"])
            pom {
                name.set("ARCP Kotlin SDK")
                description.set(
                    "Reference Kotlin implementation of the Agent Runtime Control Protocol (ARCP) v1.1.",
                )
                url.set("https://github.com/agentruntimecontrolprotocol/kotlin-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("arcp-project")
                        name.set("ARCP project")
                    }
                }
                scm {
                    connection.set(
                        "scm:git:git://github.com/agentruntimecontrolprotocol/kotlin-sdk.git",
                    )
                    developerConnection.set(
                        "scm:git:ssh://github.com/agentruntimecontrolprotocol/kotlin-sdk.git",
                    )
                    url.set("https://github.com/agentruntimecontrolprotocol/kotlin-sdk")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// GPG signing — required by Maven Central.
// Keys are injected via environment variables in CI; local builds skip signing
// when SIGNING_KEY is absent or empty.
// NOTE: providers.environmentVariable("X").isPresent returns true even for
// empty strings (GitHub Actions injects "" for unconfigured secrets), so we
// use isNullOrBlank() instead.
// ---------------------------------------------------------------------------
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val signingKeyId = providers.environmentVariable("SIGNING_KEY_ID")
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
    if (!signingKey.orNull.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKeyId.orNull, signingKey.orNull, signingPassword.orNull)
        sign(publishing.publications["maven"])
    }
}
