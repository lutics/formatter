package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

// A semicolon that only ends a line is dropped; one that separates statements on a line gets a space after it.
object NoSemi : Rule {
    override val id = "no-semi"

    override fun edits(file: KtFile): List<Edit> {
        val out = mutableListOf<Edit>()
        var lastEnd = -1
        for (semi in file.leaves().filter { it.type == KtTokens.SEMICOLON }.toList()) {
            val next = semi.nextLeaf
            val prev = semi.prevLeaf
            val edit = when {
                next.doesNotRequireSemi() && noSemicolonRequiredAfter(semi) -> {
                    val from = if (prev?.isWhiteSpace == true && (next == null || next.isWhiteSpace)) prev.textRange.startOffset else semi.textRange.startOffset
                    Edit(from, semi.textRange.endOffset, "")
                }
                next?.isWhiteSpace == false && prev?.isWhiteSpaceWithNewline != true -> Edit(semi.textRange.endOffset, semi.textRange.endOffset, " ")
                else -> null
            } ?: continue
            if (edit.start <= lastEnd) continue
            out += edit
            lastEnd = edit.end
        }
        return out
    }

    // the semicolon is followed by the end of the file or by a line break
    private fun PsiElement?.doesNotRequireSemi(): Boolean = when {
        this == null -> true
        isWhiteSpace -> {
            val code = nextLeaf { leaf -> leaf.isCode && leaf.ancestors().none { it.type == KDocTokens.KDOC || it.type == KtNodeTypes.ANNOTATION_ENTRY } }
            code == null || (textContains('\n') && code.type != KtTokens.LBRACE)
        }
        else -> false
    }

    private fun noSemicolonRequiredAfter(semi: PsiElement): Boolean {
        val prevCode = semi.prevCodeLeaf
        if (prevCode?.type == KtTokens.OBJECT_KEYWORD) return false
        prevCode?.parent?.let { owner ->
            // `while (x);` and `if (x);` need the semicolon as their empty body
            if ((owner.type == KtNodeTypes.WHILE || owner.type == KtNodeTypes.FOR) && owner.node.findChildByType(KtNodeTypes.BODY)?.firstChildNode == null) return false
            if (owner.type == KtNodeTypes.IF && owner.node.findChildByType(KtNodeTypes.THEN)?.firstChildNode == null) return false
        }
        if (semi.parent?.type == KtNodeTypes.ENUM_ENTRY) return semi.isLastCodeLeafOfClassBody()
        return !semi.isEnumClassWithoutValues()
    }

    private fun PsiElement.classBody() = ancestors().firstOrNull { it.type == KtNodeTypes.CLASS_BODY }

    private fun PsiElement.isLastCodeLeafOfClassBody() = classBody()?.lastLeaf()?.prevCodeLeaf == this

    // the semicolon opening the body of an enum class that has no entries separates its members from nothing
    private fun PsiElement.isEnumClassWithoutValues(): Boolean {
        if (isLastCodeLeafOfClassBody()) return false
        val body = classBody() ?: return false
        if (generateSequence(body.firstChild.nextSibling) { it.nextSibling }.firstOrNull { it.isCode } != this) return false
        val owner = body.ancestors().firstOrNull { it.type == KtNodeTypes.CLASS } as? KtClass ?: return false
        return owner.hasModifier(KtTokens.ENUM_KEYWORD)
    }
}
