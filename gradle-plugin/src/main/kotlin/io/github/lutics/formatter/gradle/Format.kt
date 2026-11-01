package io.github.lutics.formatter.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

// The format task: prepare (Prepare.kt), record (Record.kt), FINISH and the formatter rules, then the verifying
// compiles of the imports step's output; skipped while FormatState is unchanged, and only the formatter rules over the
// other files while only they changed.
@UntrackedTask(because = "rewrites the module's sources in place")
abstract class FormatTask : DefaultTask() {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Classpath
    abstract val engineClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val sources: ConfigurableFileCollection

    // NestedCompile's launcher, root, project and arguments
    @get:Internal
    abstract val gradleLauncher: Property<String>

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:Internal
    abstract val projectPath: Property<String>

    @get:Internal
    abstract val buildArguments: ListProperty<String>

    // FormatState's build files and the record compile tasks' classpath
    @get:Internal
    abstract val buildFiles: ConfigurableFileCollection

    @get:Internal
    abstract val classpath: ConfigurableFileCollection

    @get:Internal
    abstract val compileTasks: ListProperty<String>

    @get:Internal
    abstract val recordDir: DirectoryProperty

    // The other files the formatter rules take (build scripts, manifests, res XML, the version catalog, SQL): only the
    // FORMAT phase sees them, never prepare, record, FINISH or the imports step, which are Kotlin-source steps; a change
    // to them alone runs only that phase, over them.
    @get:Internal
    abstract val otherFiles: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val files = sources.files
        val others = otherFiles.files - files
        val all = files + others
        val state = FormatState(File(recordDir.get().asFile, "format.state"))
        val saved = state.saved()
        val current = state.current(files, others, buildFiles.files, classpath.files)
        if (saved == current) {
            logger.lifecycle("format: unchanged since the last format of these sources with this plugin, skipped")
            return
        }
        val phases = Phases(workerExecutor, engineClasspath, temporaryDir)
        if (saved?.kotlin == current.kotlin) {
            logger.lifecycle("format: only files other than the Kotlin sources changed since the last format; formatter rules over them only")
            val before = FormatState.contents(all)
            val formatted = format(others, phases, "a change to them alone").toSet()
            if (editedWhileRunning(before, formatted, all)) return
            state.save(state.current(files, others, buildFiles.files, classpath.files))
            return
        }
        val compile = NestedCompile(
            execOperations = execOperations,
            gradleLauncher = gradleLauncher.get(),
            rootDir = rootDir.get().asFile,
            projectPath = projectPath.get(),
            buildArguments = buildArguments.get(),
            compileTasks = compileTasks.get(),
            recordDir = recordDir.get().asFile,
        )
        prepare(files, phases, logger)
        val (calls, r0) = record(compile, logger)
        logger.lifecycle("format: FINISH changed ${phases.run("FINISH", files, calls, listOf(r0)).size} of ${files.size} files")
        format(all, phases, "FINISH")
        val finished = FormatState.contents(all)
        val rewritten = verify(files, all, phases, compile, r0) ?: return
        if (editedWhileRunning(finished, rewritten, all)) return
        state.save(state.current(files, others, buildFiles.files, classpath.files))
    }

    // A file format did not rewrite since the snapshot but whose content moved was edited while format ran: no state
    // then, so the next run formats it.
    private fun editedWhileRunning(snapshot: Map<File, String>, rewritten: Set<String>, all: Set<File>): Boolean {
        val edited = FormatState.contents(all).filter { (f, sha) -> f.path !in rewritten && snapshot[f] != sha }.keys
        if (edited.isEmpty()) return false
        logger.warn("format: ${edited.size} files changed while format ran (${edited.first()}…); the next run formats them again")
        return true
    }

    // The formatter rules over every file, as the formatter's --format runs them; returns the changed files' paths.
    private fun format(files: Set<File>, phases: Phases, after: String): List<String> {
        val report = phases.run("FORMAT", files)
        val errors = report.filter { it.startsWith("ERR ") }
        errors.forEach { logger.warn("format: $it") }
        logger.lifecycle("format: formatter rules after $after changed ${report.size - errors.size} of ${files.size} files")
        return report - errors.toSet()
    }

    // IntelliJ's conflict check for the imports step: compile its output with the recorder, rerun the step with every
    // imports record so far, and format again, until the step changes nothing. A failed compile still records what
    // it resolved, and the next round may be the one that fixes it, so only the last compile's exit decides. Returns the
    // paths the rounds rewrote, or null when the step still changed files after the last round. The step runs on the
    // Kotlin sources, the format after it on every file.
    private fun verify(files: Set<File>, all: Set<File>, phases: Phases, compile: NestedCompile, r0: File): Set<String>? {
        val dir = File(compile.recordDir, "verify").apply { deleteRecursively(); mkdirs() }
        val records = mutableListOf(r0.copyTo(File(dir, "imports0.jsonl")))
        var failed: File? = null
        val rewritten = mutableSetOf<String>()
        var converged = false
        for (round in 1..MAX_VERIFY_ROUNDS) {
            val log = File(dir, "compile$round.log")
            val exit = compile.run(log)
            val record = File(dir, "imports$round.jsonl")
            val recorded = Records.collect(File(compile.recordDir, Records.IMPORTS), compile.compileTasks, record)
            if (recorded.isEmpty()) throw GradleException("format: the verifying compile $round recorded nothing (exit $exit); its log is $log")
            records += record
            failed = log.takeIf { exit != 0 }
            val changed = phases.run("IMPORTS", files, imports = records)
            logger.lifecycle("format: verifying compile $round exit=$exit, imports step changed ${changed.size} files")
            if (changed.isEmpty()) {
                converged = true
                break
            }
            rewritten += changed
            rewritten += format(all, phases, "imports round $round")
        }
        failed?.let { throw GradleException("format: the last verifying compile failed; its log is $it") }
        if (!converged) logger.warn("format: the imports step still changed files after $MAX_VERIFY_ROUNDS verifying compiles; the next run continues")
        return rewritten.takeIf { converged }
    }

    private companion object {
        const val MAX_VERIFY_ROUNDS = 5
    }
}
