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

sourceSets {
    main {
        kotlin.srcDirs(
            "multi-agent-budget",
            "email-vendor-leases",
            "stream-resume",
            "mcp-skill",
        )
    }
}

application {
    mainClass.set("com.arcp.recipes.multiagentbudget.MainKt")
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
}

val recipeClasses =
    mapOf(
        "runMultiAgentBudget" to "com.arcp.recipes.multiagentbudget.MainKt",
        "runEmailVendorLeases" to "com.arcp.recipes.emailvendorleases.MainKt",
        "runStreamResume" to "com.arcp.recipes.streamresume.MainKt",
        "runMcpSkill" to "com.arcp.recipes.mcpskill.MainKt",
    )

recipeClasses.forEach { (name, mainClassFqn) ->
    tasks.register<JavaExec>(name) {
        group = "recipes"
        description = "Run recipe $name"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassFqn)
        // Inherit environment variables so API keys set in the shell are visible.
        environment = System.getenv() as Map<String, Any>
    }
}
