package io.github.lutics.formatter

import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

// Replace [start, end) of the file text with text. A rule's edits for one file must not overlap.
data class Edit(val start: Int, val end: Int, val text: String)

// Something a rule cannot fix, reported for a person.
data class Finding(val offset: Int, val detail: String)

interface Rule {
    val id: String

    // The edits that bring the file into line, computed on a fresh parse; empty when it already is.
    fun edits(file: KtFile): List<Edit> = emptyList()

    // What the rule reports but never edits.
    fun findings(file: KtFile): List<Finding> = emptyList()
}

class SyntaxError(message: String) : Exception(message)

object Engine {
    private val psi: KtPsiFactory by lazy {
        // no compiler plugins: parsing reads the extension storage even when it is empty
        val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
        val environment = KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable(),
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        KtPsiFactory(environment.project, markGenerated = false)
    }

    // allowErrors: a step that only touches a part the errors cannot be in (the import list) still runs on such a file
    fun parse(name: String, text: String, script: Boolean = name.endsWith(".kts"), allowErrors: Boolean = false): KtFile {
        val file = psi.createFile(if (script) "x.kts" else "x.kt", text)
        if (allowErrors) return file
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)?.let {
            throw SyntaxError("${it.errorDescription} at ${it.textRange.startOffset}")
        }
        return file
    }

    fun apply(text: String, edits: List<Edit>): String {
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.start }) sb.replace(e.start, e.end, e.text)
        return sb.toString()
    }

    // Each rule in order, repeated until it has nothing left, and the whole sequence until a pass changes nothing.
    // A parse is reused until an edit changes the text: no rule changes the file it is given (TreeRule edits a copy).
    fun format(name: String, text: String, rules: List<Rule>): String {
        var s = text
        var parsed: KtFile? = null
        repeat(4) {
            val before = s
            for (rule in rules) {
                repeat(5) {
                    val file = parsed ?: parse(name, s).also { parsed = it }
                    val edits = rule.edits(file)
                    if (edits.isEmpty()) return@repeat
                    s = apply(s, edits)
                    parsed = null
                }
            }
            if (s == before) return s
        }
        return s
    }

    // One finding per remaining edit and per report, as (rule id, offset, detail).
    fun check(name: String, text: String, rules: List<Rule>): List<Triple<String, Int, String>> {
        val file = parse(name, text)
        return rules.flatMap { rule ->
            rule.edits(file).map { Triple(rule.id, it.start, "not formatted") } +
                rule.findings(file).map { Triple(rule.id, it.offset, it.detail) }
        }
    }
}
