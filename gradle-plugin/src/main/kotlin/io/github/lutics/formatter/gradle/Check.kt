package io.github.lutics.formatter.gradle

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

// The findings check, run as an action of the lifecycle check task: the plugin adds no task of its own for it.
abstract class CheckAction : Action<Task> {
    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    abstract val engineClasspath: ConfigurableFileCollection

    abstract val sources: ConfigurableFileCollection

    abstract val report: RegularFileProperty

    override fun execute(task: Task) {
        val out = report.get().asFile
        val findings = Phases(workerExecutor, engineClasspath, task.temporaryDir).run("CHECK", sources.files)
        out.parentFile.mkdirs()
        out.writeText(findings.joinToString(separator = "") { it + "\n" })
        if (findings.isEmpty()) return
        findings.forEach { task.logger.error(it) }
        throw GradleException("check: ${findings.size} formatter findings in ${findings.map { it.substringBefore(':') }.distinct().size} files (run format; the list is in $out)")
    }
}
