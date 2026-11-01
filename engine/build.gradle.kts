// The formatting engine: parses each Kotlin file with the compiler's PSI and applies the rules in rules/; the other files take the text rules in text/.
plugins {
    kotlin("jvm")
    application
}
dependencies { implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21") }
kotlin {
    // Gradle runs android builds on JDK 21, and the plugin loads this code there
    jvmToolchain(21)
    // the standalone PSI parser: KotlinCoreEnvironment is the compiler's own entry to it
    compilerOptions { optIn.addAll("org.jetbrains.kotlin.CoreEnvironmentDeprecation", "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi", "org.jetbrains.kotlin.config.CompilerConfiguration.Internals") }
}
application {
    applicationName = "formatter"
    mainClass.set("io.github.lutics.formatter.MainKt")
}
