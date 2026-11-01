package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile

// `class Foo {}` is `class Foo`; an object literal and a companion keep their braces.
object NoEmptyClassBody : Rule {
    override val id = "no-empty-class-body"

    override fun edits(file: KtFile): List<Edit> {
        val out = mutableListOf<Edit>()
        var lastEnd = -1
        for (body in PsiTreeUtil.findChildrenOfType(file, KtClassBody::class.java)) {
            val start = body.textRange.startOffset
            if (start < lastEnd || !body.isEmptyBlock() || body.ancestors().any { it.type == KtNodeTypes.OBJECT_LITERAL }) continue
            // Looks for `companion` among the children of the declaration's first child, so a KDoc in front hides it
            if (generateSequence(body.parent.firstChild.firstChild) { it.nextSibling }.any { it.text == "companion" }) continue
            val from = body.prevSibling?.takeIf { it.isWhiteSpace }?.textRange?.startOffset ?: start
            out += Edit(from, body.textRange.endOffset, "")
            lastEnd = body.textRange.endOffset
        }
        return out
    }

    private fun KtClassBody.isEmptyBlock() =
        firstChild?.type == KtTokens.LBRACE && firstChild.nextLeaf { !it.isWhiteSpace }?.type == KtTokens.RBRACE
}
