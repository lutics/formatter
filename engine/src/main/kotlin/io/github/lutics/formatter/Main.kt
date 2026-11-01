package io.github.lutics.formatter

import io.github.lutics.formatter.imports.ImportRecord
import io.github.lutics.formatter.rules.layout.UiStateOrder
import io.github.lutics.formatter.steps.ImportsStep
import java.io.File
import kotlin.system.exitProcess

// args: [--format] <dir>...   (RULES=id,id limits the rules, for measuring one)
// prints: dir<TAB>files<TAB>filesWithFindings<TAB>findings<TAB>errors<TAB>rule=count,...
// and each finding to stderr as <file>:<line>:<col> <rule> <detail>, the list a person fixes by hand
fun main(args: Array<String>) {
    // --step <id>[,<id>...] [--calls <file>] [--imports <file>[,<file>...]] <dir>...: run pipeline steps in order on every file in
    // place (measuring steps alone); the calls are loaded before the first step runs, while the files still have their recorded
    // layout; --imports takes the recording compile's imports record, then the record of each compile of the imports step's output
    val stepAt = args.indexOf("--step")
    if (stepAt >= 0) {
        val steps = args[stepAt + 1].split(',').map { id ->
            Pipeline.ALL.firstOrNull { it.id == id } ?: run {
                System.err.println("unknown step: $id (have ${Pipeline.ALL.map { it.id }})")
                exitProcess(2)
            }
        }
        val callsAt = args.indexOf("--calls")
        val calls = if (callsAt >= 0) checkNotNull(Pipeline.loadCalls) { "no call-record loader" }(File(args[callsAt + 1])) else null
        val importsAt = args.indexOf("--imports")
        if (importsAt >= 0) ImportsStep.rounds = args[importsAt + 1].split(',').map { ImportRecord.load(File(it)) }
        val skip = setOf(stepAt, stepAt + 1) + listOf(callsAt, importsAt).filter { it >= 0 }.flatMap { listOf(it, it + 1) }
        val files = args.filterIndexed { i, _ -> i !in skip }.flatMap { dir ->
            File(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        // the formatter step's ui-state-order rewrites a when only over a type declared among these files
        UiStateOrder.index = UiStateOrder.buildIndex(files)
        Pipeline.run(steps, files, calls)
        return
    }
    val format = args.contains("--format")
    val only = System.getenv("RULES")?.split(',')?.toSet()
    val rules = RULES.filter { only == null || it.id in only }
    val textRules = TEXT_RULES.filter { only == null || it.id in only }
    if (only != null && rules.size + textRules.size != only.size) {
        System.err.println("unknown rule in RULES: ${only - (rules.map { it.id } + textRules.map { it.id }).toSet()}")
        exitProcess(2)
    }
    for (dir in args.filter { it != "--format" }) {
        // Kotlin, and every other file a text rule takes (Android XML, *.toml, *.sql)
        val files = File(dir).walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", ".idea") }
            .filter { it.isFile && Files.supported(it.path, textRules) }
            .toList()
        // per dir, as the plugin indexes one module's files: ui-state-order rewrites a when only over a type declared there
        UiStateOrder.index = UiStateOrder.buildIndex(files)
        val perRule = sortedMapOf<String, Int>()
        var hit = 0
        var total = 0
        var errors = 0
        for (f in files) {
            try {
                var text = f.readText()
                if (format) {
                    val formatted = Files.format(f.path, text, rules, textRules)
                    if (formatted != text) f.writeText(formatted)
                    text = formatted
                }
                val found = Files.check(f.path, text, rules, textRules)
                for ((id, offset, detail) in found) {
                    perRule.merge(id, 1, Int::plus)
                    val line = text.substring(0, offset).count { it == '\n' } + 1
                    val col = offset - text.lastIndexOf('\n', offset - 1)
                    System.err.println("$f:$line:$col $id $detail")
                }
                if (found.isNotEmpty()) hit++
                total += found.size
            } catch (e: Exception) {
                errors++
                System.err.println("ERR $f: ${e::class.simpleName}: ${e.message}"); if (System.getenv("TRACE") != null) e.printStackTrace()
            }
        }
        val top = perRule.entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}=${it.value}" }
        println("$dir\t${files.size}\t$hit\t$total\t$errors\t$top")
    }
}
