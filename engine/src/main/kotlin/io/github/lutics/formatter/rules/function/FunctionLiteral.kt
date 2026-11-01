package io.github.lutics.formatter.rules.function

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import io.github.lutics.formatter.rules.wrapcomma.INDENT
import io.github.lutics.formatter.rules.wrapcomma.childNodes
import io.github.lutics.formatter.rules.wrapcomma.isCode
import io.github.lutics.formatter.rules.wrapcomma.isPartOfComment
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpace
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpaceWithNewline
import io.github.lutics.formatter.rules.wrapcomma.nextLeaf
import io.github.lutics.formatter.rules.wrapcomma.nextSibling
import io.github.lutics.formatter.rules.wrapcomma.prevLeaf
import io.github.lutics.formatter.rules.wrapcomma.prevSibling
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

// Lambda parameters stay on the `{` line unless the parameter list
// already spans lines (then one per line, with the arrow on its own line); a redundant `->` goes; a lambda whose body
// spans lines gets that body on its own lines, between the `{` or `->` line and a `}` line.
object FunctionLiteral : Rule {
    override val id = "function-literal"

    override fun edits(file: KtFile): List<Edit> {
        val text = file.text
        val pass = Pass()
        for (literal in file.node.preOrder()) {
            if (pass.stopped) break
            if (literal.elementType != KtNodeTypes.FUNCTION_LITERAL) continue
            // every indent here is taken from the line the lambda starts on
            val indent = indentAt(file, text, literal.start)
            val changes = WsChanges()
            val own = "\n" + indent.text
            val child = own + INDENT
            literal.findChildByType(KtNodeTypes.VALUE_PARAMETER_LIST)?.let { visitParameterList(it, own, child, changes) }
            literal.findChildByType(KtTokens.ARROW)?.let { visitArrow(it, changes) }
            literal.findChildByType(KtNodeTypes.BLOCK)?.let { visitBlock(it, own, child, changes) }
            pass.offer(indent.from, literal.end, changes.edits())
        }
        return pass.edits
    }

    private fun visitParameterList(list: ASTNode, own: String, child: String, changes: WsChanges) {
        val parameters = list.childNodes.filter { it.elementType == KtNodeTypes.VALUE_PARAMETER }.toList()
        val multiline = list.textContains('\n')
        when {
            parameters.size > 1 && multiline -> {
                for (parameter in parameters) {
                    parameter.prevLeaf?.takeIf { it.isWhiteSpace && !changes.textOf(it).contains('\n') }?.let { changes.replace(it, child) }
                }
                list.treeParent.findChildByType(KtTokens.ARROW)?.let { arrow ->
                    arrow.prevLeaf?.takeIf { it.isWhiteSpace && !changes.textOf(it).contains('\n') }?.let { changes.replace(it, child) }
                    wrapAfterArrow(arrow, child, changes)
                }
                list.treeParent.findChildByType(KtTokens.RBRACE)?.let { wrapBeforeRbrace(it, own, changes) }
            }
            // a single parameter spanning lines (an annotated type, say) is left as written
            multiline -> Unit
            else -> {
                val precededByComment = generateSequence(list.treePrev) { it.treePrev }.any { it.isPartOfComment }
                if (!precededByComment) list.prevSibling { it.isWhiteSpaceWithNewline }?.let { changes.replace(it, " ") }
                list.nextSibling { it.isWhiteSpace }?.takeIf { it.isWhiteSpaceWithNewline }?.let { changes.replace(it, " ") }
            }
        }
    }

    // `{ -> body }` is `{ body }`, except where the braces would then read as a block: a when branch or if/else.
    private fun visitArrow(arrow: ASTNode, changes: WsChanges) {
        val list = arrow.prevSibling { it.elementType == KtNodeTypes.VALUE_PARAMETER_LIST } ?: return
        if (list.findChildByType(KtNodeTypes.VALUE_PARAMETER) != null) return
        val lambdaParent = generateSequence(arrow.treeParent) { it.treeParent }
            .firstOrNull { it.elementType == KtNodeTypes.LAMBDA_EXPRESSION }?.treeParent?.elementType
        if (lambdaParent == KtNodeTypes.WHEN_ENTRY || lambdaParent == KtNodeTypes.THEN || lambdaParent == KtNodeTypes.ELSE) return
        if (arrow.nextSibling { it.elementType == KtNodeTypes.BLOCK }?.firstChildNode == null) return
        arrow.treeNext?.takeIf { it.isWhiteSpace }?.let { changes.replace(it, "") }
        changes.replace(arrow, "")
    }

    private fun visitBlock(block: ASTNode, own: String, child: String, changes: WsChanges) {
        if (!block.textContains('\n')) return
        // a removed arrow is gone: the empty parameter list before it is then the previous code, and nothing is wrapped
        val prevCode = block.prevSibling { it.isCode && !(it.elementType == KtTokens.ARROW && changes.textOf(it).isEmpty()) }
        when (prevCode?.elementType) {
            KtTokens.ARROW -> wrapAfterArrow(prevCode, child, changes)
            KtTokens.LBRACE -> {
                val next = prevCode.nextLeaf
                if (!next.isWhiteSpace || !changes.textOf(next!!).contains('\n')) changes.upsertAfter(prevCode, child)
            }
        }
        block.nextSibling { it.isCode }?.takeIf { it.elementType == KtTokens.RBRACE }?.let { wrapBeforeRbrace(it, own, changes) }
    }

    private fun wrapAfterArrow(arrow: ASTNode, child: String, changes: WsChanges) {
        val next = arrow.nextLeaf
        if (next == null || next.isWhiteSpace && !changes.textOf(next).contains('\n')) changes.upsertAfter(arrow, child)
    }

    private fun wrapBeforeRbrace(rbrace: ASTNode, own: String, changes: WsChanges) {
        val prev = rbrace.prevLeaf ?: return
        if (prev.isWhiteSpace && !changes.textOf(prev).contains('\n')) changes.replace(prev, own)
    }
}
