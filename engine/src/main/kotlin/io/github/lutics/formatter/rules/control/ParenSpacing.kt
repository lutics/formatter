package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

// No whitespace inside a parenthesis pair, none between a callee and its argument list, and no line break
// before a `)` whose `(` opens on the same line as the first argument.
object ParenSpacing : Rule {
    override val id = "paren-spacing"

    private val lists = setOf(KtNodeTypes.VALUE_PARAMETER_LIST, KtNodeTypes.VALUE_ARGUMENT_LIST)

    override fun edits(file: KtFile): List<Edit> {
        val out = mutableListOf<Edit>()
        val removed = mutableListOf<PsiElement>()
        val parens = file.leaves().filter { it.type == KtTokens.LPAR || it.type == KtTokens.RPAR }.toList()
        for (paren in parens) {
            // The tree is read as earlier removals left it; a paren they touch is judged in the next pass
            if (removed.any { paren.dependsOn(it) }) break
            val before = paren.spacingBefore()
            val after = paren.spacingAfter()
            val gone = listOfNotNull(
                paren.prevLeaf.takeIf { before },
                paren.nextLeaf.takeIf { after },
            )
            for (leaf in gone) out += Edit(leaf.textRange.startOffset, leaf.textRange.endOffset, "")
            removed += gone
        }
        return out
    }

    private fun PsiElement.dependsOn(leaf: PsiElement): Boolean {
        val range = parent.textRange
        return leaf === prevLeaf || leaf === nextLeaf || leaf === nextLeaf?.nextLeaf ||
            (leaf.textRange.startOffset >= range.startOffset && leaf.textRange.endOffset <= range.endOffset)
    }

    private fun PsiElement.spacingBefore(): Boolean {
        val prev = prevLeaf
        return when {
            prev?.isWhiteSpaceWithNewline == true && hasNoNewlineAfterLpar() -> true
            prev?.isWhiteSpaceWithoutNewline != true -> false
            // `fn("foo" )` and `fn( )`; the second is for the `(` to judge
            type == KtTokens.RPAR -> prev.prevSibling?.type != KtTokens.LPAR
            else -> parent.type in lists && (afterIdentifier(prev) || afterSuper(prev) || afterConstructor(prev) || afterSuperType(prev))
        }
    }

    // `fun foo ()` and `@Ann ("x")`, but not the `@Composable () -> Unit` function type
    private fun PsiElement.afterIdentifier(space: PsiElement) =
        space.prevLeaf?.type == KtTokens.IDENTIFIER && parent.parent?.type != KtNodeTypes.FUNCTION_TYPE

    private fun afterSuper(space: PsiElement) = space.prevLeaf?.type == KtTokens.SUPER_KEYWORD

    private fun afterConstructor(space: PsiElement) = space.prevLeaf?.parent?.type == KtNodeTypes.PRIMARY_CONSTRUCTOR

    private fun PsiElement.afterSuperType(space: PsiElement) =
        parent.parent?.type == KtNodeTypes.SUPER_TYPE_CALL_ENTRY && space.prevSibling?.type == KtNodeTypes.CONSTRUCTOR_CALLEE

    private fun PsiElement.spacingAfter(): Boolean {
        if (type != KtTokens.LPAR) return false
        val next = nextSibling
        if (next?.isWhiteSpaceWithNewline == true && next.siblingsAfter().takeWhile { it.type != KtTokens.RPAR }.none { it.isWhiteSpaceWithNewline }) return true
        val leaf = nextLeaf ?: return false
        if (leaf.nextLeaf?.type in comments) return false
        return leaf.isWhiteSpaceWithoutNewline || (leaf.isWhiteSpaceWithNewline && leaf.nextLeaf?.type == KtTokens.RPAR)
    }

    private val comments = setOf(KtTokens.EOL_COMMENT, KtTokens.BLOCK_COMMENT, KDocTokens.START)

    // a `)` on its own line although the `(` line holds the first argument
    private fun PsiElement.hasNoNewlineAfterLpar(): Boolean {
        val space = prevSibling?.takeIf { it.isWhiteSpaceWithNewline } ?: return false
        if (space.prevSibling?.type == KtTokens.LPAR) return false
        return space.siblingsBefore().takeWhile { it.type != KtTokens.LPAR }.none { it.textContains('\n') }
    }
}
