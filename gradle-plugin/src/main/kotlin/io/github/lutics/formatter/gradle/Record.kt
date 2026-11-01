package io.github.lutics.formatter.gradle

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.File

// format's second step, the recording compile of prepare's output: every call site into calls.jsonl, every file's
// imports into imports.jsonl. Unlike a verifying compile it must succeed, since FINISH maps arguments by these records.
internal fun record(compile: NestedCompile, logger: Logger): Pair<File, File> {
    val dir = compile.recordDir
    val log = File(dir, "record.log")
    val exit = compile.run(log)
    val tasks = compile.compileTasks
    val calls = File(dir, "calls.jsonl")
    val imports = File(dir, "imports.jsonl")
    val callCounts = Records.collect(File(dir, Records.CALLS), tasks, calls)
    val importCounts = Records.collect(File(dir, Records.IMPORTS), tasks, imports)
    if (exit != 0) throw GradleException("format: the record compile failed (exit $exit); its log is $log")
    // a task with no sources is skipped and records nothing
    tasks.forEach { logger.lifecycle("format: record: $it recorded ${callCounts[it] ?: 0} calls and the imports of ${importCounts[it] ?: 0} files") }
    return calls to imports
}

// The project's record compile tasks in a Gradle process of its own, with RECORD_PROPERTY set to the project's path,
// since one build runs a compile task once and only that property turns the CallRecorder on. The record step and every
// verifying compile run it.
internal class NestedCompile(
    private val execOperations: ExecOperations,
    private val gradleLauncher: String,
    private val rootDir: File,
    private val projectPath: String,
    // the outer build's options a nested build must share (offline, init scripts, -P and -D)
    private val buildArguments: List<String>,
    val compileTasks: List<String>,
    val recordDir: File,
) {

    // Returns the nested build's exit; its record files are deleted first, so a compile task that does not run leaves
    // none behind.
    fun run(log: File): Int {
        log.parentFile.mkdirs()
        compileTasks.forEach { task -> listOf(Records.CALLS, Records.IMPORTS).forEach { File(recordDir, "$it/$task.jsonl").delete() } }
        val prefix = if (projectPath == ":") ":" else "$projectPath:"
        return log.outputStream().use { out ->
            execOperations.exec {
                commandLine(listOf(gradleLauncher, "--project-dir", rootDir.path, "--console=plain", "-P${FormatterPlugin.RECORD_PROPERTY}=$projectPath") + compileTasks.map { prefix + it } + buildArguments)
                // the nested build runs on this build's JDK
                environment("JAVA_HOME", System.getProperty("java.home"))
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }.exitValue
        }
    }
}

internal object Records {
    // the subdirectories of build/formatter the recorder writes <compile task>.jsonl into, one file per compile task
    const val CALLS = "calls"
    const val IMPORTS = "imports"

    // Concatenates each task's record file, in task order, into out; returns the record count per task that recorded,
    // leaving out the imports record's header line (the platform's default imports).
    fun collect(dir: File, tasks: List<String>, out: File): Map<String, Int> {
        out.parentFile.mkdirs()
        val counts = LinkedHashMap<String, Int>()
        out.bufferedWriter().use { w ->
            for (task in tasks) {
                val records = File(dir, "$task.jsonl").takeIf { it.isFile }?.readLines() ?: continue
                records.forEach { w.write(it + "\n") }
                counts[task] = records.count { !it.startsWith("{\"defaults\"") }
            }
        }
        return counts
    }
}
