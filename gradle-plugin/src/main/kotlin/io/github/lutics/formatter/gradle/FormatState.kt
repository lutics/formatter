package io.github.lutics.formatter.gradle

import java.io.File
import java.security.MessageDigest

// What a format run's result depends on, hashed in two parts. kotlin: this plugin's own jar (the engine, the recorder,
// the worker's classpath and the step order all ship in it, so any plugin change reruns format without a version bump),
// the build files and the record compile classpath (what a call resolves to, upstream projects included), and every
// Kotlin source's path and content. others: the other files' paths and contents, which only the FORMAT phase reads.
// format writes it after a run that converged with no source edited behind its back, skips the next run while both are
// unchanged, and runs only the FORMAT phase over the other files while kotlin is.
internal class FormatState(private val file: File) {

    data class Hashes(val kotlin: String, val others: String)

    fun current(sources: Set<File>, others: Set<File>, buildFiles: Set<File>, classpath: Set<File>) = Hashes(
        kotlin = kotlin(sources, buildFiles, classpath),
        others = hash(others),
    )

    private fun kotlin(sources: Set<File>, buildFiles: Set<File>, classpath: Set<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) {
            digest.update(text.toByteArray())
            digest.update(0)
        }
        add(PLUGIN)
        for (f in buildFiles.filter { it.isFile }.sortedBy { it.path }) add("${f.path} ${sha256(f.readBytes())}")
        // dependency jars are immutable in Gradle's caches and an upstream project's outputs are rewritten when they
        // change, so path, size and modification time stand for content without reading them
        for (f in classpath.flatMap { walk(it) }.sortedBy { it.path }) add("${f.path} ${f.length()} ${f.lastModified()}")
        for ((f, sha) in contents(sources).toSortedMap(compareBy { it.path })) add("${f.path} $sha")
        return hex(digest.digest())
    }

    private fun hash(files: Set<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((f, sha) in contents(files).toSortedMap(compareBy { it.path })) {
            digest.update("${f.path} $sha".toByteArray())
            digest.update(0)
        }
        return hex(digest.digest())
    }

    // The last saved state; null when there is none or it predates the two parts.
    fun saved(): Hashes? = file.takeIf { it.isFile }?.readLines()?.takeIf { it.size == 2 }?.let { Hashes(kotlin = it[0], others = it[1]) }

    fun save(state: Hashes) {
        file.parentFile.mkdirs()
        file.writeText("${state.kotlin}\n${state.others}")
    }

    companion object {

        // Each source's content hash, to tell format's own rewrites from edits made while it runs.
        fun contents(sources: Set<File>): Map<File, String> = sources.associateWith { sha256(it.readBytes()) }

        private val PLUGIN: String by lazy {
            val location = File(FormatState::class.java.protectionDomain.codeSource.location.toURI())
            val digest = MessageDigest.getInstance("SHA-256")
            walk(location).sortedBy { it.path }.forEach { digest.update(it.readBytes()) }
            hex(digest.digest())
        }

        private fun walk(f: File): List<File> = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f).filter { it.isFile }

        private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun hex(bytes: ByteArray) = bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
