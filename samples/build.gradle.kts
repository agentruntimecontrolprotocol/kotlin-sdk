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
    mainClass.set("com.arcp.samples.subscriptions.MainKt")
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
}

val sampleClasses =
    mapOf(
        "runSubscriptions" to "com.arcp.samples.subscriptions.MainKt",
        "runLeases" to "com.arcp.samples.leases.MainKt",
        "runLeaseRevocation" to "com.arcp.samples.lease_revocation.MainKt",
        "runPermissionChallenge" to "com.arcp.samples.permission_challenge.MainKt",
        "runDelegation" to "com.arcp.samples.delegation.MainKt",
        "runHandoff" to "com.arcp.samples.handoff.MainKt",
        "runHeartbeats" to "com.arcp.samples.heartbeats.MainKt",
        "runCapabilityNegotiation" to "com.arcp.samples.capability_negotiation.MainKt",
        "runResumability" to "com.arcp.samples.resumability.MainKt",
        "runReasoningStreams" to "com.arcp.samples.reasoning_streams.MainKt",
        "runExtensions" to "com.arcp.samples.extensions.MainKt",
        "runHumanInput" to "com.arcp.samples.human_input.MainKt",
        "runCancellation" to "com.arcp.samples.cancellation.MainKt",
        "runMcp" to "com.arcp.samples.mcp.MainKt",
    )

sampleClasses.forEach { (name, mainClassFqn) ->
    tasks.register<JavaExec>(name) {
        group = "samples"
        description = "Run sample $name"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassFqn)
    }
}
