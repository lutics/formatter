package io.github.lutics.formatter.gradle

import io.github.lutics.formatter.Files
import io.github.lutics.formatter.Pipeline
import io.github.lutics.formatter.Step
import io.github.lutics.formatter.imports.ImportRecord
import io.github.lutics.formatter.rules.layout.UiStateOrder
import io.github.lutics.formatter.steps.AsFormatStep
import io.github.lutics.formatter.steps.ImportsStep
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File

// One engine phase in the isolated worker classloader. Engine types appear only in EngineRunner, which loads there:
// the plugin classloader sees this class (Gradle inspects it) but has no engine on its classpath.
abstract class EngineAction : WorkAction<EngineAction.Parameters> {

    interface Parameters : WorkParameters {
        // PREPARE, LAYOUT, AS_FORMAT, FINISH, IMPORTS or FORMAT (rewrite in place, report the changed files) or CHECK
        // (report the findings)
        val phase: Property<String>
        val files: ConfigurableFileCollection
        val calls: RegularFileProperty

        // the imports records in compile order: the recording compile's, then each verifying compile's
        val imports: ListProperty<File>
        val report: RegularFileProperty
    }

    override fun execute() {
        val p = parameters
        EngineRunner.run(p.phase.get(), p.files.files.sortedBy { it.path }, p.calls.orNull?.asFile, p.imports.getOrElse(emptyList()), p.report.get().asFile)
    }
}

internal object EngineRunner {
    fun run(phase: String, files: List<File>, calls: File?, imports: List<File>, report: File) {
        report.parentFile.mkdirs()
        when (phase) {
            "CHECK" -> withUiStateIndex(files) { check(files, report) }
            "FORMAT" -> withUiStateIndex(files) { format(files, report) }
            else -> transform(phase, files, calls, imports, report)
        }
    }

    // ui-state-order rewrites a when only over a type it found declared among all the files; the worker classloader is
    // reused across actions, so the index never outlives the phase
    private fun withUiStateIndex(files: List<File>, block: () -> Unit) {
        UiStateOrder.index = UiStateOrder.buildIndex(files)
        try {
            block()
        } finally {
            UiStateOrder.index = null
        }
    }

    private fun transform(phase: String, files: List<File>, calls: File?, imports: List<File>, report: File) {
        val steps: List<Step> = when (phase) {
            "PREPARE" -> Pipeline.PREPARE
            "LAYOUT" -> Pipeline.LAYOUT
            "AS_FORMAT" -> listOf(AsFormatStep)
            "FINISH" -> Pipeline.FINISH
            "IMPORTS" -> listOf(ImportsStep)
            else -> error("unknown phase: $phase")
        }
        // the worker classloader is reused across actions, so the records of an earlier phase must not leak into this one
        ImportsStep.rounds = imports.map { ImportRecord.load(it) }
        // loaded before the first step, while the files still have the layout the compile recorded
        val records = calls?.let {
            val load = checkNotNull(Pipeline.loadCalls) { "Pipeline.loadCalls is not set: the engine has no call-record loader yet, so FINISH cannot run" }
            load(it)
        }
        val before = files.associateWith { it.readText() }
        try {
            Pipeline.run(steps, files, records)
        } finally {
            ImportsStep.rounds = emptyList()
        }
        report.writeText(files.filter { it.readText() != before[it] }.joinToString(separator = "") { it.path + "\n" })
    }

    // Main's --format, per file (Kotlin to the PSI rules, the rest to the text rules for its path): a file the rules
    // fail on is left as it is, and listed after the changed files as `ERR <file>: <error>`
    private fun format(files: List<File>, report: File) {
        val changed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (f in files) {
            try {
                val text = f.readText()
                val formatted = Files.format(f.path, text)
                if (formatted != text) {
                    f.writeText(formatted)
                    changed += f.path
                }
            } catch (e: Exception) {
                errors += "ERR $f: ${e::class.simpleName}: ${e.message}"
            }
        }
        report.writeText((changed + errors).joinToString(separator = "") { it + "\n" })
    }

    // Main's check, per file: <file>:<line>:<col> <rule> <detail>
    private fun check(files: List<File>, report: File) {
        val lines = files.flatMap { f ->
            try {
                val text = f.readText()
                Files.check(f.path, text).map { (id, offset, detail) ->
                    val line = text.substring(0, offset).count { it == '\n' } + 1
                    val col = offset - text.lastIndexOf('\n', offset - 1)
                    "$f:$line:$col $id $detail"
                }
            } catch (e: Exception) {
                listOf("$f: ERR ${e::class.simpleName}: ${e.message}")
            }
        }
        report.writeText(lines.joinToString(separator = "") { it + "\n" })
    }
}

// Runs engine phases in a worker whose classloader holds the engine and kotlin-compiler-embeddable only, apart from the
// Kotlin Gradle plugin's compiler classes; each phase's report goes to reportDir/<phase>.txt.
internal class Phases(
    private val workerExecutor: WorkerExecutor,
    private val engineClasspath: FileCollection,
    private val reportDir: File,
) {

    // Returns the report's lines.
    fun run(phase: String, files: Set<File>, calls: File? = null, imports: List<File> = emptyList()): List<String> {
        val report = File(reportDir, "$phase.txt")
        workerExecutor.classLoaderIsolation { classpath.from(engineClasspath) }.submit(EngineAction::class.java) {
            this.phase.set(phase)
            this.files.from(files)
            calls?.let { this.calls.set(it) }
            this.imports.set(imports)
            this.report.set(report)
        }
        workerExecutor.await()
        return report.readLines().filter { it.isNotEmpty() }
    }
}
