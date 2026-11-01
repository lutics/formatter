// The call-recording compiler plugin. Its Kotlin version must equal the formatted modules' Kotlin version: the FIR
// checker API it uses is not stable across compiler releases.
plugins { kotlin("jvm") }
dependencies { compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21") }
kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.addAll("-Xcontext-parameters", "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi") }
}
