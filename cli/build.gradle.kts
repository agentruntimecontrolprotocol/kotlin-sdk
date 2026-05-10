plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

application {
    mainClass.set("dev.fizzpop.arcp.cli.MainKt")
    applicationName = "arcp"
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
}
