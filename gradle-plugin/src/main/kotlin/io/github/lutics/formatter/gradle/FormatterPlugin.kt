package io.github.lutics.formatter.gradle

import io.github.lutics.formatter.text.XmlLayout
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import java.io.File

// One task, format (prepare, record, FINISH, then verifying compiles of the imports step's output; rewrites sources in
// place), and the findings check as an action of the lifecycle check task. prepare and record are steps of format, not
// tasks, so a build can run only format and check.
//
// The CallRecorder reaches kotlinc only through this KotlinCompilerPluginSupportPlugin, and isApplicable is true only
// in format's nested record builds, which set RECORD_PROPERTY to its own path: in any other build KGP never adds the recorder to a
// compiler plugin classpath or its option to a compile task, so those tasks keep their inputs and cache keys.
class FormatterPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        val project = target
        val extension = project.extensions.create("formatter", FormatterExtension::class.java)
        extension.recordCompileTasks.convention(project.provider { if (isAndroid(project)) ANDROID_RECORD else JVM_RECORD })

        val engine = engineConfiguration(project)
        val sources = sourceFiles(project)
        // The root build's own files (settings script, root build script, version catalog) belong to one project: the
        // root project when it applies this plugin, otherwise the first project in path order that does (":" sorts
        // first). Known only once every project has applied its plugins, so decided once, after evaluation.
        val ownsRootFiles = project.objects.property(Boolean::class.java)
        project.gradle.projectsEvaluated {
            val extra = project.gradle.extensions.extraProperties
            if (!extra.has(ROOT_FILES_OWNER)) {
                extra.set(ROOT_FILES_OWNER, project.rootProject.allprojects.sortedBy { it.path }.first { it.pluginManager.hasPlugin(ID) }.path)
            }
            ownsRootFiles.set(extra.get(ROOT_FILES_OWNER) == project.path)
        }
        val others = otherFiles(project, ownsRootFiles)
        val records = project.layout.buildDirectory.dir("formatter")

        // KGP adds getPluginArtifact() as a Maven dependency wherever the recorder applies; the jar ships in this
        // plugin, so swap that coordinate for the bundled file
        project.configurations.configureEach {
            withDependencies {
                if (removeIf { it is ExternalModuleDependency && it.group == GROUP && it.name == RECORDER_ARTIFACT }) {
                    add(project.dependencies.create(project.files(project.provider { Bundled.extract("call-recorder.jar", project.layout.buildDirectory.dir("formatter/lib").get().asFile) })))
                }
            }
        }

        val format = project.tasks.register("format", FormatTask::class.java) {
            group = GROUP_NAME
            description = "Formats the module's Kotlin sources, build scripts, Android manifests and resources, version catalog and SQL in place."
            this.sources.from(sources)
            otherFiles.from(others)
            engineClasspath.from(engine)
            gradleLauncher.set(gradleLauncher(project))
            rootDir.set(project.rootDir)
            buildFiles.from(project.buildFile, project.rootProject.buildFile, BUILD_FILES.map { project.rootProject.file(it) })
            classpath.from(project.provider { project.tasks.withType(AbstractKotlinCompile::class.java).filter { it.name in extension.recordCompileTasks.get() }.map { it.libraries } })
            projectPath.set(project.path)
            buildArguments.set(buildArguments(project))
            compileTasks.set(extension.recordCompileTasks)
            recordDir.set(records)
        }
        project.plugins.withType(LifecycleBasePlugin::class.java) {
            val action = project.objects.newInstance(CheckAction::class.java)
            action.sources.from(sources, others)
            action.engineClasspath.from(engine)
            action.report.set(project.layout.buildDirectory.file("reports/formatter/findings.txt"))
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                mustRunAfter(format)
                doLast(action)
            }
        }

        // prepare moves lines, so everything that reads the sources (KSP, the compile tasks) runs after format
        if (requested(project, "format")) {
            project.tasks.configureEach { if (name != format.name) mustRunAfter(format) }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.project
        // the property names the formatted project: its upstream projects compile in the same nested build, unrecorded
        if (project.providers.gradleProperty(RECORD_PROPERTY).orNull != project.path) return false
        val extension = project.extensions.findByType(FormatterExtension::class.java) ?: return false
        return kotlinCompilation.compileKotlinTaskName in extension.recordCompileTasks.get()
    }

    // Called only when isApplicable: the recording compile is full, never from cache, and appends to a fresh file.
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val task = kotlinCompilation.compileKotlinTaskName
        val out = project.layout.buildDirectory.file("formatter/${Records.CALLS}/$task.jsonl")
        val imports = project.layout.buildDirectory.file("formatter/${Records.IMPORTS}/$task.jsonl")
        kotlinCompilation.compileTaskProvider.configure {
            // an incremental compile would record only the files LAYOUT touched
            (this as? AbstractKotlinCompile<*>)?.incremental = false
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("the CallRecorder writes its records outside the task outputs") { true }
            doFirst { listOf(out, imports).forEach { it.get().asFile.run { parentFile.mkdirs(); delete() } } }
        }
        return out.zip(imports) { o, i ->
            listOf(SubpluginOption(key = OPTION_OUT, value = o.asFile.absolutePath), SubpluginOption(key = OPTION_IMPORTS, value = i.asFile.absolutePath))
        }
    }

    // compiler-plugin's KotlinCommandLineProcessor.pluginId and its options
    override fun getCompilerPluginId(): String = "codes.stable.formatter.kotlin"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(groupId = GROUP, artifactId = RECORDER_ARTIFACT, version = VERSION)

    // This Gradle's own launcher, so the nested build of the verifying compiles runs the same Gradle version.
    private fun gradleLauncher(project: Project): String {
        val home = checkNotNull(project.gradle.gradleHomeDir) { "format needs a Gradle distribution to run its verifying compiles" }
        val windows = System.getProperty("os.name").startsWith("Windows")
        return File(home, if (windows) "bin/gradle.bat" else "bin/gradle").path
    }

    private fun buildArguments(project: Project): List<String> {
        val start = project.gradle.startParameter
        return buildList {
            if (start.isOffline) add("--offline")
            start.initScripts.forEach { addAll(listOf("--init-script", it.path)) }
            start.projectProperties.forEach { (k, v) -> add("-P$k=$v") }
            start.systemPropertiesArgs.forEach { (k, v) -> add("-D$k=$v") }
        }
    }

    private fun isAndroid(project: Project) = project.pluginManager.hasPlugin("com.android.application") || project.pluginManager.hasPlugin("com.android.library")

    // The module's Kotlin source sets: AGP's on android (built-in Kotlin has no Kotlin source sets of its own), KGP's
    // otherwise; directories under build/ (generated code) are left out.
    private fun sourceFiles(project: Project): FileCollection {
        val roots = project.provider {
            val dirs = if (isAndroid(project)) {
                AndroidSourceRoots.of(project)
            } else {
                (project.extensions.findByName("kotlin") as? KotlinSourceSetContainer)?.sourceSets?.flatMap { it.kotlin.srcDirs }.orEmpty()
            }
            val build = project.layout.buildDirectory.get().asFile
            dirs.distinct().filter { it.isDirectory && !it.startsWith(build) }
        }
        return project.files(roots).asFileTree.matching { include("**/*.kt") }
    }

    // The module's other files the engine formats (only its FORMAT and CHECK phases take them; the steps around the
    // record compile are Kotlin-source-only): the project's build script; for the owner of the root build's files the
    // settings script, the root build script (*.kts only: the engine has no Groovy rules) and the version catalog; on
    // android every source set's manifest and the res XML aapt compiles (XmlLayout.RES_TYPES; never res/raw, which the
    // app reads as bytes); *.sql under src/.
    private fun otherFiles(project: Project, ownsRootFiles: Provider<Boolean>): FileCollection {
        val root = project.rootProject
        val scripts = project.provider {
            buildList {
                add(project.buildFile)
                if (ownsRootFiles.get()) {
                    add(root.buildFile)
                    add(root.file("settings.gradle.kts"))
                    add(root.file("gradle/libs.versions.toml"))
                }
            }.filter { it.isFile && (it.name.endsWith(".kts") || it.name.endsWith(".toml")) }.distinct()
        }
        val build = project.layout.buildDirectory
        val manifests = project.provider { if (isAndroid(project)) AndroidSourceRoots.manifests(project) else emptyList() }
        val resRoots = project.provider {
            if (isAndroid(project)) AndroidSourceRoots.res(project).distinct().filter { it.isDirectory && !it.startsWith(build.get().asFile) } else emptyList()
        }
        val res = project.files(resRoots).asFileTree.matching { include(XmlLayout.RES_TYPES.split(' ').flatMap { listOf("$it/*.xml", "$it-*/*.xml") }) }
        val sql = project.fileTree("src").matching { include("**/*.sql") }
        return project.files(scripts, manifests, res, sql)
    }

    // The engine jar from this plugin and its external runtime classpath, resolved from the build's own repositories.
    private fun engineConfiguration(project: Project): FileCollection {
        val configuration = project.configurations.create("formatterEngine") {
            isCanBeConsumed = false
            isTransitive = false
            description = "The formatter engine's runtime classpath, for the isolated worker."
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
            }
            defaultDependencies { Bundled.engineClasspath().forEach { add(project.dependencies.create(it)) } }
        }
        val jar = project.provider { Bundled.extract("engine.jar", project.layout.buildDirectory.dir("formatter/lib").get().asFile) }
        return project.files(jar, configuration)
    }

    companion object {
        const val GROUP = "io.github.lutics"
        const val ID = "io.github.lutics.formatter"
        const val VERSION = "0.0.3"
        const val RECORDER_ARTIFACT = "formatter-call-recorder"
        const val OPTION_OUT = "out"
        const val OPTION_IMPORTS = "imports"
        const val GROUP_NAME = "formatting"

        // the path of the project that formats the root build's files, on the build's Gradle extra properties
        const val ROOT_FILES_OWNER = "io.github.lutics.formatter.rootFilesOwner"

        // the root build's files besides the build scripts that can change what a call resolves to
        val BUILD_FILES = listOf("settings.gradle.kts", "settings.gradle", "gradle.properties", "gradle/libs.versions.toml")

        // set only on format's nested record builds, to the formatted project's path; it turns the CallRecorder on for
        // that project's record compile tasks
        const val RECORD_PROPERTY = "io.github.lutics.formatter.record"

        // The compile tasks the recording compile runs: one build type on android, so each source file is recorded
        // once (the recorder appends), and its device tests; main on JVM. The same set the shell pipeline records.
        val ANDROID_RECORD = listOf("compileDebugKotlin", "compileDebugAndroidTestKotlin")
        val JVM_RECORD = listOf("compileKotlin")

        // Whether one of names was requested on the command line for this project: a bare name runs in every project,
        // a path only in its own.
        fun requested(project: Project, vararg names: String): Boolean = project.gradle.startParameter.taskNames.any { arg ->
            arg.substringAfterLast(':') in names && (':' !in arg || ":" + arg.substringBeforeLast(':').removePrefix(":") == project.path)
        }
    }
}

internal object Bundled {
    // Copies a jar bundled in this plugin to dir, rewriting it only when the bytes differ.
    fun extract(name: String, dir: File): File {
        val bytes = checkNotNull(Bundled::class.java.getResourceAsStream(name)) { "$name is not bundled in the formatter plugin" }.use { it.readBytes() }
        val file = File(dir, name)
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
            dir.mkdirs()
            file.writeBytes(bytes)
        }
        return file
    }

    fun engineClasspath(): List<String> = checkNotNull(Bundled::class.java.getResourceAsStream("engine-classpath.txt")) { "engine-classpath.txt is not bundled" }
        .use { it.reader().readLines() }
        .filter { it.isNotBlank() }
}
