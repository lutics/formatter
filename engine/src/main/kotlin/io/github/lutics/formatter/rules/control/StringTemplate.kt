package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile

// `${name}` is `$name` where the next character cannot continue an identifier, and `${x.toString()}` is `${x}`.
object StringTemplate : Rule {
    override val id = "string-template"

    override fun edits(file: KtFile): List<Edit> {
        val out = mutableListOf<Edit>()
        var lastEnd = -1
        for (entry in PsiTreeUtil.findChildrenOfType(file, KtBlockStringTemplateEntry::class.java)) {
            if (entry.textRange.startOffset < lastEnd) continue
            val text = entry.withoutToString() ?: entry.text.takeIf { entry.hasRedundantBraces() }?.let { shortForm(it) } ?: continue
            out += Edit(entry.textRange.startOffset, entry.textRange.endOffset, text)
            lastEnd = entry.textRange.endOffset
        }
        return out
    }

    // the entry text after dropping a trailing .toString(), and after the braces if that leaves a bare name
    private fun PsiElement.withoutToString(): String? {
        val dot = generateSequence(firstChild) { it.nextSibling }.firstOrNull { it.type == KtNodeTypes.DOT_QUALIFIED_EXPRESSION } ?: return null
        val parts = generateSequence(dot.firstChild) { it.nextSibling }.filter { it.isCode }.toList()
        if (parts.size != 3) return null
        val (receiver, _, selector) = parts
        if (receiver.type == KtNodeTypes.SUPER_EXPRESSION || selector.type != KtNodeTypes.CALL_EXPRESSION || selector.text != "toString()") return null
        val start = textRange.startOffset
        val without = text.substring(0, dot.textRange.startOffset - start) + receiver.text + text.substring(dot.textRange.endOffset - start)
        // The braces are dropped here even when a name character follows, which would change the string; this checks
        return if (isBare(without) && endsIdentifier()) shortForm(without) else without
    }

    private fun PsiElement.hasRedundantBraces(): Boolean {
        val inner = generateSequence(firstChild) { it.nextSibling }.firstOrNull { it.type != KtTokens.LONG_TEMPLATE_ENTRY_START } ?: return false
        if (inner.type != KtNodeTypes.REFERENCE_EXPRESSION && inner.type != KtNodeTypes.THIS_EXPRESSION) return false
        return isBare(text) && endsIdentifier()
    }

    // what follows the entry cannot continue the name
    private fun PsiElement.endsIdentifier(): Boolean {
        val next = nextSibling ?: return false
        return next.type == KtTokens.CLOSING_QUOTE ||
            (next.type == KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY && !isIdentifierPart(next.text.substring(0, 1)))
    }

    private fun isBare(entry: String) = entry.startsWith("\${") && isIdentifierPart(entry.substring(2, entry.length - 1))

    private fun isIdentifierPart(s: String) = s == "_" || s.all { it.isLetterOrDigit() }

    private fun shortForm(entry: String) = "$" + entry.substring(2, entry.length - 1)
}
