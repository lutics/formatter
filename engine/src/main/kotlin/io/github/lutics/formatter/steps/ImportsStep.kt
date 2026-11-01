package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Engine
import io.github.lutics.formatter.Step
import io.github.lutics.formatter.imports.ImportRecord
import io.github.lutics.formatter.imports.ImportSettings
import io.github.lutics.formatter.imports.OptimizedImports
import org.jetbrains.kotlin.com.intellij.psi.PsiComment

// IntelliJ's Kotlin Optimize Imports (K2, IDE defaults), from the imports record of the recording compile (ImportRecorder).
// IntelliJ re-resolves the file with the new imports and locks the imports of every reference that changed target; here that
// re-resolution is a compile of this step's output with the recorder: run the step again with that record appended, and repeat
// until a run changes nothing.
object ImportsStep : Step {
    override val id = "imports"

    // the recording compile's record, then the record of each compile of this step's output, in order; empty runs nothing
    var rounds: List<ImportRecord> = emptyList()

    var settings = ImportSettings()

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val record = rounds.firstOrNull() ?: return text
        val file = record.files[ImportRecord.canonical(path)] ?: return text
        val imports = OptimizedImports(file, record, settings).build(rounds.drop(1))
        val ktFile = Engine.parse(path, text, allowErrors = true)
        val list = ktFile.importList
        val directives = ktFile.importDirectives
        // already optimal (tryBuildOptimizedImports returns null): the import list as the recording compile saw it
        val block = imports?.joinToString("\n") { "import $it" } ?: file.importText
        if (list == null || directives.isEmpty()) {
            if (block.isEmpty()) return text
            val anchor = ktFile.packageDirective?.takeIf { it.textLength > 0 }?.textRange?.endOffset ?: return block + "\n\n" + text
            return text.substring(0, anchor) + "\n\n" + block + text.substring(anchor)
        }
        val start = directives.first().textRange.startOffset
        val end = directives.last().textRange.endOffset
        // replaceImports adds the new imports before the last old one and deletes the old ones, so comments among them stay, above
        val comments = generateSequence(list.firstChild) { it.nextSibling }.filterIsInstance<PsiComment>().map { it.text }.toList()
        if (block.isNotEmpty()) {
            val replacement = if (imports == null) block else (comments + block).joinToString("\n")
            return if (text.substring(start, end) == replacement) text else text.replaceRange(start, end, replacement)
        }
        // no imports left: the blank lines around the list collapse into one gap, and none at the start of the file
        var before = start
        while (before > 0 && text[before - 1].isWhitespace()) before--
        var after = end
        while (after < text.length && text[after].isWhitespace()) after++
        val leading = text.substring(before, start)
        val trailing = text.substring(end, after)
        val replacement = when {
            comments.isNotEmpty() -> (if (before == 0) "" else leading) + comments.joinToString("\n") + trailing
            before == 0 -> ""
            leading.count { it == '\n' } > trailing.count { it == '\n' } -> leading
            else -> trailing
        }
        return text.replaceRange(before, after, replacement)
    }
}
