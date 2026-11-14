// The Gradle plugin: runs the engine over a module's Kotlin sources and the CallRecorder on its recording compile.
plugins {
    kotlin("jvm")
    kotlin("plugin.sam.with.receiver") version "2.4.21"
    id("com.gradle.plugin-publish") version "2.2.1"
}

version = "0.0.5"

// AGP's DSL API, compileOnly: android source roots are read from AGP's source sets
repositories { google() }

// The engine and the CallRecorder ship inside this jar as resources, never on the plugin classpath: the engine needs
// kotlin-compiler-embeddable, which clashes with the Kotlin Gradle plugin's own compiler classes, so it loads only in an
// isolated worker classloader; the recorder is a compiler plugin jar handed to kotlinc. Bundling them keeps the plugin
// one published artifact, so neither module needs a repository of its own.
val runtimeJars: Action<AttributeContainer> = Action {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
}
val bundledJars = configurations.dependencyScope("bundledJars")
val engineJar = configurations.dependencyScope("engineJar")
val bundled = configurations.resolvable("bundled") {
    extendsFrom(bundledJars.get())
    isTransitive = false
    attributes(runtimeJars)
}
val engineRuntime = configurations.resolvable("engineRuntime") {
    extendsFrom(engineJar.get())
    attributes(runtimeJars)
}

dependencies {
    compileOnly(project(":engine"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    compileOnly("com.android.tools.build:gradle-api:9.5.1")
    add("bundledJars", project(":engine"))
    add("bundledJars", project(":compiler-plugin"))
    add("engineJar", project(":engine"))
}

// Gradle's Action<T> lambdas take T as receiver, as in build scripts
samWithReceiver { annotation("org.gradle.api.HasImplicitReceiver") }

kotlin {
    // Gradle loads this plugin into the consuming build's own JVM, so the bytecode level is the oldest JDK a
    // consuming build can run Gradle on to use the plugin. 21 is this project's choice, not a Gradle or AGP limit: both
    // run on 17.
    jvmToolchain(21)
    // lambdas as classes, as kotlin-dsl compiles them: the configuration cache cannot store an invokedynamic lambda
    compilerOptions { freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class") }
}

// The engine's external runtime classpath as exact coordinates; the plugin resolves them from the consuming build's
// repositories for the worker.
abstract class WriteEngineClasspath : DefaultTask() {
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() = output.get().asFile.writeText(coordinates.get().joinToString(separator = "") { "$it\n" })
}

val engineClasspath = tasks.register<WriteEngineClasspath>("engineClasspath") {
    coordinates.set(
        engineRuntime.map { c ->
            c.incoming.artifacts.artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { m -> "${m.group}:${m.module}:${m.version}" } }.sorted()
        },
    )
    output.set(layout.buildDirectory.file("engine-classpath.txt"))
}

val bundle = tasks.register<Sync>("bundle") {
    into(layout.buildDirectory.dir("bundled"))
    into("io/github/lutics/formatter/gradle") {
        from(bundled) { rename { if (it.startsWith("engine")) "engine.jar" else "call-recorder.jar" } }
        from(engineClasspath)
    }
}

sourceSets.main { resources.srcDir(bundle) }

gradlePlugin {
    website = "https://github.com/lutics/formatter"
    vcsUrl = "https://github.com/lutics/formatter"
    plugins {
        create("formatter") {
            id = "io.github.lutics.formatter"
            implementationClass = "io.github.lutics.formatter.gradle.FormatterPlugin"
            displayName = "Kotlin formatter"
            description = "Formats a module's Kotlin sources, build scripts, Android manifest and resources, version catalog and SQL in place (format) " +
                "and checks them as part of check, using the compiler's PSI and a call-recording compile to name call arguments."
            tags = listOf("kotlin", "android", "formatter", "code-style", "ktlint")
        }
    }
}
