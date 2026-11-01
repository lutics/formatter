package io.github.lutics.formatter.rules.function

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import io.github.lutics.formatter.rules.wrapcomma.INDENT
import io.github.lutics.formatter.rules.wrapcomma.childNodes
import io.github.lutics.formatter.rules.wrapcomma.firstChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.isCode
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpace
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpaceWithNewline
import io.github.lutics.formatter.rules.wrapcomma.lastChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.nextCodeLeaf
import io.github.lutics.formatter.rules.wrapcomma.nextLeaf
import io.github.lutics.formatter.rules.wrapcomma.nextSibling
import io.github.lutics.formatter.rules.wrapcomma.prevLeaf
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile

// A signature that is already on one line stays there unless it
// has two or more parameters (or a parameter spanning lines); any other gets one parameter per line. An expression
// body is pulled up behind its `=`.
object FunctionSignature : Rule {
    override val id = "function-signature"

    // a signature with this many parameters or more puts each on its own line
    private const val FORCE_MULTILINE_PARAMETERS = 2
    private val CLOSING_PARENTHESIS_WITH_EQ = Regex("\\s*\\) =")

    override fun edits(file: KtFile): List<Edit> {
        val text = file.text
        val pass = Pass()
        for (node in file.node.preOrder()) {
            if (pass.stopped) break
            if (node.elementType != KtNodeTypes.FUN) continue
            val signature = node.signatureLeaves()
            // A signature with a comment in it is left alone
            if (signature.any { it.elementType == KtTokens.EOL_COMMENT || it.elementType == KtTokens.BLOCK_COMMENT }) continue
            val parameterList = node.findChildByType(KtNodeTypes.VALUE_PARAMETER_LIST) ?: continue
            val indent = indentAt(file, text, node.start)
            val changes = WsChanges()
            val parameters = parameterList.childNodes.filter { it.elementType == KtNodeTypes.VALUE_PARAMETER }.toList()
            val multiline = parameters.size >= FORCE_MULTILINE_PARAMETERS ||
                parameters.any { it.textContains('\n') } ||
                signature.any { it.textContains('\n') }
            if (parameters.isEmpty()) {
                val inside = parameterList.childNodes.filter { it.elementType != KtTokens.LPAR && it.elementType != KtTokens.RPAR }.toList()
                if (inside.size > 1 || inside.any { !it.isWhiteSpace }) continue
                inside.firstOrNull()?.let { changes.replace(it, "") }
            } else {
                fixParameters(parameterList, parameters, multiline, "\n" + indent.text, changes)
            }
            fixBodyExpression(node, signature, changes)
            pass.offer(indent.from, node.end, changes.edits())
        }
        return pass.edits
    }

    // From the first code after the annotations up to the `{` of a block body or the `=` of an expression body.
    private fun ASTNode.signatureLeaves(): List<ASTNode> {
        val first = firstSignatureNode()?.firstChildLeafOrSelf
        val bodyStart = findChildByType(KtNodeTypes.BLOCK)?.firstChildNode
        val eq = findChildByType(KtTokens.EQ)
        val all = leaves
        val from = all.indexOf(first)
        if (from < 0) return emptyList()
        val result = mutableListOf<ASTNode>()
        for (leaf in all.subList(from, all.size)) {
            result += leaf
            if (leaf != first && (leaf == bodyStart || leaf == eq)) break
        }
        return result
    }

    private fun ASTNode.firstSignatureNode(): ASTNode? {
        val modifiers = findChildByType(KtNodeTypes.MODIFIER_LIST) ?: return firstChildLeafOrSelf.let { if (it.isCode) it else it.nextCodeLeaf }
        for (child in modifiers.childNodes) {
            if (child.elementType == KtNodeTypes.CONTEXT_RECEIVER_LIST || child.elementType == KtNodeTypes.CONTEXT_PARAMETER_LIST) {
                // a context on its own line is not part of the signature
                val after = child.lastChildLeafOrSelf.nextLeaf
                return if (after.isWhiteSpaceWithNewline) after!!.nextLeaf else child
            }
            if (child.elementType != KtNodeTypes.ANNOTATION && child.elementType != KtNodeTypes.ANNOTATION_ENTRY && !child.isWhiteSpace) return child
        }
        return modifiers.nextSibling { it.isCode }
    }

    private fun fixParameters(
        parameterList: ASTNode,
        parameters: List<ASTNode>,
        multiline: Boolean,
        newlineIndent: String,
        changes: WsChanges,
    ) {
        val parameterIndent = newlineIndent + INDENT
        val lpar = parameterList.firstChildNode
        parameters.forEachIndexed { i, parameter ->
            val first = parameter.firstChildNode
            val ws = first.prevLeaf?.takeIf { it.isWhiteSpace }
            when {
                multiline -> if (ws == null || ws.text != parameterIndent) {
                    if (i == 0) changes.upsertAfter(lpar, parameterIndent) else changes.upsertBefore(parameter, parameterIndent)
                }
                i == 0 -> if (ws != null) changes.replace(ws, "")
                ws == null || ws.text != " " -> if (ws != null) changes.replace(ws, " ") else changes.insert(parameter.start, " ")
            }
        }
        val rpar = parameterList.findChildByType(KtTokens.RPAR) ?: return
        val ws = rpar.treePrev?.takeIf { it.isWhiteSpace }
        if (multiline) {
            if (ws == null || ws.text != newlineIndent) changes.upsertBefore(rpar, newlineIndent)
        } else if (ws != null) {
            changes.replace(ws, "")
        }
    }

    // With no line length limit, the first line of an expression body always fits behind the `=`.
    private fun fixBodyExpression(node: ASTNode, signature: List<ASTNode>, changes: WsChanges) {
        val eq = node.findChildByType(KtTokens.EQ) ?: return
        val ws = eq.nextLeaf ?: return
        val signatureEndsWithParenthesis = signature.joinToString("") { it.text }.split("\n").last().matches(CLOSING_PARENTHESIS_WITH_EQ)
        if (!ws.isWhiteSpaceWithNewline) {
            // behind a `) =` line the body gets exactly one space; other single-line bodies are left as written
            if (signatureEndsWithParenthesis && (!ws.isWhiteSpace || ws.text != " ")) {
                if (ws.isWhiteSpace) changes.replace(ws, " ") else changes.insert(ws.start, " ")
            }
            return
        }
        // an annotated body stays on its own line, as the annotation rule wants
        if (ws.nextSibling { it.isCode }?.elementType == KtNodeTypes.ANNOTATED_EXPRESSION) return
        val bodyLeaves = node.leaves.dropWhile { it != ws }.drop(1).dropWhile { it.isWhiteSpace }
        val firstCode = bodyLeaves.firstOrNull { it.isCode } ?: return
        val rawStringOnNextLine = firstCode.elementType == KtTokens.OPEN_QUOTE && firstCode.nextLeaf?.text.orEmpty().startsWith("\n")
        if (!rawStringOnNextLine || signatureEndsWithParenthesis) changes.replace(ws, " ")
    }
}
