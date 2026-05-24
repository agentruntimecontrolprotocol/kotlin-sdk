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

// Each entry maps a Gradle task name to the sample's main class. The list is
// the source of truth; the README is regenerated from these entries by hand.
//
// Invariant: every mainClass below must point to a source file under
// `src/main/kotlin/...`. The init check enforces this so a sample cannot be
// announced (and then fail at run time) when its source has been deleted —
// see #55.
val sampleClasses =
    mapOf(
        "runSubscriptions" to "com.arcp.samples.subscriptions.MainKt",
        "runLeases" to "com.arcp.samples.leases.MainKt",
        "runLeaseRevocation" to "com.arcp.samples.leaserevocation.MainKt",
        "runPermissionChallenge" to "com.arcp.samples.permissionchallenge.MainKt",
        "runDelegation" to "com.arcp.samples.delegation.MainKt",
        "runHandoff" to "com.arcp.samples.handoff.MainKt",
        "runHeartbeats" to "com.arcp.samples.heartbeats.MainKt",
        "runCapabilityNegotiation" to "com.arcp.samples.capabilitynegotiation.MainKt",
        "runResumability" to "com.arcp.samples.resumability.MainKt",
        "runReasoningStreams" to "com.arcp.samples.reasoningstreams.MainKt",
        "runExtensions" to "com.arcp.samples.extensions.MainKt",
        "runCancellation" to "com.arcp.samples.cancellation.MainKt",
        "runMcp" to "com.arcp.samples.mcp.MainKt",
        "runListJobs" to "com.arcp.samples.listjobs.MainKt",
        "runAgentVersions" to "com.arcp.samples.agentversions.MainKt",
        "runResultChunk" to "com.arcp.samples.resultchunk.MainKt",
        "runCostBudget" to "com.arcp.samples.costbudget.MainKt",
        "runProvisionedCredentials" to "com.arcp.samples.provisionedcredentials.MainKt",
        "runLitellmRecipe" to "com.arcp.samples.recipes.litellm.MainKt",
    )

private fun classpathRelative(mainClassFqn: String): String {
    val pkg = mainClassFqn.substringBeforeLast('.')
    val cls = mainClassFqn.substringAfterLast('.').removeSuffix("Kt")
    val pkgPath = pkg.replace('.', '/')
    return "src/main/kotlin/$pkgPath/$cls.kt"
}

sampleClasses.forEach { (name, mainClassFqn) ->
    val source = file(classpathRelative(mainClassFqn))
    check(source.exists()) {
        "sample task '$name' points to $mainClassFqn but ${source.relativeTo(rootDir)} is missing"
    }
    tasks.register<JavaExec>(name) {
        group = "samples"
        description = "Run sample $name"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassFqn)
    }
}
