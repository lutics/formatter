// The formatting engine: parses each Kotlin file with the compiler's PSI and applies the rules in rules/; the other files take the text rules in text/.
plugins {
    kotlin("jvm")
    application
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    // before/after cases for the rules; test scope only, so none of it reaches engineRuntime (the plugin's worker classpath)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin {
    // Gradle loads this code into the consuming build's own JVM (the plugin's worker), so the bytecode level is the
    // oldest JDK a consuming build can run Gradle on to use the plugin. 21 is this project's choice, not a Gradle or AGP
    // limit: both run on 17.
    jvmToolchain(21)
    // the standalone PSI parser: KotlinCoreEnvironment is the compiler's own entry to it
    compilerOptions { optIn.addAll("org.jetbrains.kotlin.CoreEnvironmentDeprecation", "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi", "org.jetbrains.kotlin.config.CompilerConfiguration.Internals") }
}
// `build` runs them through `check`
tasks.test { useJUnitPlatform() }
application {
    applicationName = "formatter"
    mainClass.set("io.github.lutics.formatter.MainKt")
}
