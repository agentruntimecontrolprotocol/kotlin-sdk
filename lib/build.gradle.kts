plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xjvm-default=all",
        )
    }
}

dependencies {
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

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("ARCP Kotlin SDK")
                description.set("Reference Kotlin implementation of the Agent Runtime Control Protocol (ARCP) v1.0.")
                url.set("https://github.com/fizzpop/arcp")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("fizzpop")
                        name.set("Fizzpop Engineering")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/fizzpop/arcp.git")
                    developerConnection.set("scm:git:ssh://github.com/fizzpop/arcp.git")
                    url.set("https://github.com/fizzpop/arcp")
                }
            }
        }
    }
}
