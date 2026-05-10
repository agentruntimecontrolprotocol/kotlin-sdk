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
    mainClass.set("dev.fizzpop.arcp.samples.Sample01MinimalSessionKt")
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
}

val sampleClasses =
    mapOf(
        "run01" to "dev.fizzpop.arcp.samples.Sample01MinimalSessionKt",
        "run02" to "dev.fizzpop.arcp.samples.Sample02ToolInvokeWithProgressKt",
        "run03" to "dev.fizzpop.arcp.samples.Sample03HumanInputRequestKt",
        "run04" to "dev.fizzpop.arcp.samples.Sample04PermissionChallengeKt",
        "run05" to "dev.fizzpop.arcp.samples.Sample05ObserverSubscriptionKt",
        "run06" to "dev.fizzpop.arcp.samples.Sample06RelayHumanInTheLoopKt",
    )

sampleClasses.forEach { (name, mainClassFqn) ->
    tasks.register<JavaExec>(name) {
        group = "samples"
        description = "Run sample $name"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassFqn)
    }
}
