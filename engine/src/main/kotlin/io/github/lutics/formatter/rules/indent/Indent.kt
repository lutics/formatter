package io.github.lutics.formatter.rules.indent

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import io.github.lutics.formatter.rules.wrapcomma.INDENT
import io.github.lutics.formatter.rules.wrapcomma.childNodes
import io.github.lutics.formatter.rules.wrapcomma.firstChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.isCode
import io.github.lutics.formatter.rules.wrapcomma.isPartOf
import io.github.lutics.formatter.rules.wrapcomma.isPartOfComment
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpace
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpaceWithNewline
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpaceWithoutNewline
import io.github.lutics.formatter.rules.wrapcomma.lastChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.nextCodeLeaf
import io.github.lutics.formatter.rules.wrapcomma.nextCodeSibling
import io.github.lutics.formatter.rules.wrapcomma.nextLeaf
import io.github.lutics.formatter.rules.wrapcomma.nextSibling
import io.github.lutics.formatter.rules.wrapcomma.parent
import io.github.lutics.formatter.rules.wrapcomma.prevCodeLeaf
import io.github.lutics.formatter.rules.wrapcomma.prevCodeSibling
import io.github.lutics.formatter.rules.wrapcomma.prevLeaf
import io.github.lutics.formatter.rules.wrapcomma.prevSibling
import org.jetbrains.kotlin.KtNodeTypes.ANNOTATED_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION
import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.BINARY_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.BINARY_WITH_TYPE
import org.jetbrains.kotlin.KtNodeTypes.BLOCK
import org.jetbrains.kotlin.KtNodeTypes.BODY
import org.jetbrains.kotlin.KtNodeTypes.CALL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.CATCH
import org.jetbrains.kotlin.KtNodeTypes.CLASS
import org.jetbrains.kotlin.KtNodeTypes.CONDITION
import org.jetbrains.kotlin.KtNodeTypes.CONSTRUCTOR_DELEGATION_CALL
import org.jetbrains.kotlin.KtNodeTypes.CONTEXT_RECEIVER_LIST
import org.jetbrains.kotlin.KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.DESTRUCTURING_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.DOT_QUALIFIED_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.ELSE
import org.jetbrains.kotlin.KtNodeTypes.FINALLY
import org.jetbrains.kotlin.KtNodeTypes.FOR
import org.jetbrains.kotlin.KtNodeTypes.FUN
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.IF
import org.jetbrains.kotlin.KtNodeTypes.IS_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.LITERAL_STRING_TEMPLATE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.MODIFIER_LIST
import org.jetbrains.kotlin.KtNodeTypes.NULLABLE_TYPE
import org.jetbrains.kotlin.KtNodeTypes.OBJECT_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.OPERATION_REFERENCE
import org.jetbrains.kotlin.KtNodeTypes.PARENTHESIZED
import org.jetbrains.kotlin.KtNodeTypes.POSTFIX_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.PREFIX_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.PROPERTY
import org.jetbrains.kotlin.KtNodeTypes.PROPERTY_ACCESSOR
import org.jetbrains.kotlin.KtNodeTypes.SAFE_ACCESS_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.SECONDARY_CONSTRUCTOR
import org.jetbrains.kotlin.KtNodeTypes.STRING_TEMPLATE
import org.jetbrains.kotlin.KtNodeTypes.SUPER_TYPE_LIST
import org.jetbrains.kotlin.KtNodeTypes.THEN
import org.jetbrains.kotlin.KtNodeTypes.TRY
import org.jetbrains.kotlin.KtNodeTypes.TYPEALIAS
import org.jetbrains.kotlin.KtNodeTypes.TYPE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_CONSTRAINT_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_REFERENCE
import org.jetbrains.kotlin.KtNodeTypes.USER_TYPE
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.WHEN
import org.jetbrains.kotlin.KtNodeTypes.WHEN_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.WHILE
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.leaves

// Every line's indent from the nesting of the code around it, in the Android Studio code style with a 4-space indent.
// One walk over the tree keeps a stack of indent contexts, each a range of
// leaves with the indent of its first, middle and last lines; the whitespace before a line takes the indent of the
// context on top of the stack. The stack logic's quirks are deliberate: the fleet's code is formatted to them.
object Indent : Rule {
    override val id = "indent"

    override fun edits(file: KtFile): List<Edit> = Walk(file.node).run().first

    override fun findings(file: KtFile): List<Finding> = Walk(file.node).run().second
}

private const val KDOC_CONTINUATION_INDENT = " "
private const val TYPE_CONSTRAINT_CONTINUATION_INDENT = "      " // "where" plus a space
private val CHAINABLE_EXPRESSION = setOf(DOT_QUALIFIED_EXPRESSION, SAFE_ACCESS_EXPRESSION)
private val LAST_CHILD_UNINDENTED = setOf(CONTEXT_RECEIVER_LIST, LONG_STRING_TEMPLATE_ENTRY, STRING_TEMPLATE, VALUE_ARGUMENT_LIST, VALUE_ARGUMENT)
private val INDENTED = setOf(TYPE_ARGUMENT_LIST, TYPE_PARAMETER_LIST, BINARY_WITH_TYPE, USER_TYPE, IS_EXPRESSION, PREFIX_EXPRESSION, POSTFIX_EXPRESSION)

private class IndentContext(
    val fromASTNode: ASTNode,
    val toASTNode: ASTNode = fromASTNode.lastChildLeafOrSelf,
    val nodeIndent: String,
    val firstChildIndent: String,
    val childIndent: String,
    val lastChildIndent: String,
    // a context adds its child indent to nested contexts only once a line break inside it has been seen
    var activated: Boolean = false,
) {
    fun indent() = if (activated) nodeIndent + childIndent else nodeIndent

    fun prevCodeLeaf() = fromASTNode.prevCodeLeaf!!
}

// One pass over a file, as a tree visitor runs it: before children, children, after children.
private class Walk(private val root: ASTNode) {
    private val stack = ArrayDeque<IndentContext>()

    // Whitespace is rewritten in place as the walk goes, and a few look-backs (column, indent of a line) read the result
    private val rewritten = HashMap<ASTNode, String>()
    private val inserted = mutableListOf<Edit>()
    private val findings = mutableListOf<Finding>()

    fun run(): Pair<List<Edit>, List<Finding>> {
        visit(root)
        check(stack.isEmpty()) { "indent context stack not empty: ${stack.size}" }
        val edits = rewritten.map { (node, text) -> Edit(node.startOffset, node.startOffset + node.textLength, text) } + inserted
        return edits to findings
    }

    private fun visit(node: ASTNode) {
        before(node)
        var child = node.firstChildNode
        while (child != null) {
            visit(child)
            child = child.treeNext
        }
        after(node)
    }

    private val ASTNode.txt: String get() = rewritten[this] ?: text

    private fun before(node: ASTNode) {
        if (node == root) {
            // a file does not start with whitespace
            node.nextLeaf?.takeIf { it.isWhiteSpaceWithoutNewline }?.let { rewritten[it] = "" }
            stack.addLast(IndentContext(node, nodeIndent = "", firstChildIndent = "", childIndent = "", lastChildIndent = "", activated = true))
        }
        val type = node.elementType
        when {
            node.isWhiteSpaceWithNewline -> {
                stack.lastOrNull()?.activated = true
                visitNewLineIndentation(node)
            }

            // android_studio deviates from IntelliJ for VALUE_ARGUMENT too, so a wrapped argument value is indented below its name
            type in LAST_CHILD_UNINDENTED || (type == VALUE_PARAMETER_LIST && node.treeParent?.elementType != FUNCTION_LITERAL) ->
                startIndentContext(node, lastChildIndent = "")

            // the super type list only gets its own context in the strictest code style
            type == SUPER_TYPE_LIST || (node.isPartOfComment && node.nextCodeSibling?.elementType == SUPER_TYPE_LIST) -> Unit

            type == SECONDARY_CONSTRUCTOR -> visitSecondaryConstructor(node)

            type in INDENTED ||
                (type == PARENTHESIZED && node.treeParent?.treeParent?.elementType != IF) ||
                // several qualified expressions on one line add a single level
                (type in CHAINABLE_EXPRESSION && (node.prevCodeSibling.isElvisOperator() || node.treeParent?.elementType !in CHAINABLE_EXPRESSION)) ->
                startIndentContext(node)

            type == DELEGATED_SUPER_TYPE_ENTRY || type == ANNOTATED_EXPRESSION || type == TYPE_REFERENCE ->
                startIndentContext(node, childIndent = "")

            type == IF -> visitIf(node)

            type == KtTokens.LBRACE -> visitLbrace(node)

            type == KtTokens.LPAR && node.nextCodeSibling?.elementType == CONDITION -> visitLparBeforeCondition(node)

            type == VALUE_PARAMETER -> visitValueParameter(node)

            type == FUN -> visitFun(node)

            type == CLASS || type == OBJECT_DECLARATION -> visitClass(node)

            type == BINARY_EXPRESSION -> visitBinaryExpression(node)

            type == KtTokens.IDENTIFIER && node.treeParent?.elementType == PROPERTY ->
                startIndentContext(node, toAstNode = node.treeParent.lastChildLeafOrSelf)

            type == LITERAL_STRING_TEMPLATE_ENTRY && node.nextCodeSibling?.elementType == KtTokens.CLOSING_QUOTE ->
                visitClosingQuotes(currentIndent(), node.treeParent)

            type == WHEN -> visitWhen(node)

            type == WHEN_ENTRY -> visitWhenEntry(node)

            type == KtTokens.WHERE_KEYWORD && node.nextCodeSibling?.elementType == TYPE_CONSTRAINT_LIST ->
                visitWhereKeywordBeforeTypeConstraintList(node)

            type == KDocTokens.KDOC -> visitKdoc(node)

            type == PROPERTY_ACCESSOR || type == TYPEALIAS -> visitPropertyAccessor(node)

            type == FOR || type == WHILE -> visitConditionalLoop(node)

            type == KtTokens.LBRACKET -> visitLBracket(node)

            type == NULLABLE_TYPE -> visitNullableType(node)

            type == DESTRUCTURING_DECLARATION -> visitDestructuringDeclaration(node)

            type == TRY -> visitTryCatchFinally(node)
        }
    }

    private fun after(node: ASTNode) {
        while (true) {
            val top = stack.lastOrNull() ?: break
            if (top.toASTNode != node && top.toASTNode.parent { it == node } == null) break
            stack.removeLast()
        }
    }

    private fun visitSecondaryConstructor(node: ASTNode) {
        node.findChildByType(CONSTRUCTOR_DELEGATION_CALL)?.let { constructorDelegationCall ->
            val fromAstNode = node.skipLeadingWhitespaceCommentsAndAnnotations()
            val nextToAstNode = startIndentContext(constructorDelegationCall).prevCodeLeaf()
            // leading annotations and comments are indented at the level of the constructor itself
            if (fromAstNode != node.nextLeaf) {
                startIndentContext(node, toAstNode = nextToAstNode, childIndent = "")
            }
        }
    }

    private fun visitIf(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(ELSE)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode).prevCodeLeaf()
        }
        node.findChildByType(THEN)?.lastChildLeafOrSelf?.nextLeaf?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode, childIndent = "").prevCodeLeaf()
        }
        node.findChildByType(KtTokens.RPAR)?.nextCodeLeaf?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode).prevCodeLeaf()
        }
        // no indent for the closing parenthesis
        startIndentContext(node, toAstNode = nextToAstNode, lastChildIndent = "")
    }

    private fun visitLbrace(node: ASTNode) {
        val rbrace = node.nextSibling { it.elementType == KtTokens.RBRACE }!!
        startIndentContext(node, toAstNode = rbrace, firstChildIndent = "", lastChildIndent = "")
        // inner contexts in reverse order
        node.treeParent?.takeIf { it.elementType == FUNCTION_LITERAL }?.findChildByType(KtTokens.ARROW)?.let { arrow ->
            startIndentContext(arrow, toAstNode = rbrace, lastChildIndent = "")
            startIndentContext(node, toAstNode = arrow.prevCodeLeaf!!, childIndent = arrow.calculateIndentOfFunctionLiteralParameters())
        }
    }

    private fun ASTNode.calculateIndentOfFunctionLiteralParameters(): String =
        if (isFirstParameterOfFunctionLiteralPrecededByNewLine()) {
            // IntelliJ IDEA's default, not the Kotlin coding conventions
            INDENT.repeat(2)
        } else {
            // parameters after "{ " on the line of the call are aligned with the first one
            takeIf { it.isPartOf(CALL_EXPRESSION) }
                ?.treeParent
                ?.leaves(false)
                ?.takeWhile { !it.isWhiteSpaceWithNewline }
                ?.sumOf { it.txt.length }
                ?.plus(2)
                ?.let { " ".repeat(it) }
                ?: INDENT.repeat(2)
        }

    private fun ASTNode.isFirstParameterOfFunctionLiteralPrecededByNewLine() =
        parent { it.elementType == FUNCTION_LITERAL }
            ?.findChildByType(VALUE_PARAMETER_LIST)
            ?.prevSibling { it.textContains('\n') } != null

    private fun visitLparBeforeCondition(node: ASTNode) {
        startIndentContext(
            // picks up the whitespace before the condition, not the one after it
            node.nextLeaf!!,
            toAstNode = node.nextCodeSibling!!.lastChildLeafOrSelf,
            nodeIndent = currentIndent() + INDENT,
            childIndent = "",
        )
    }

    private fun visitValueParameter(node: ASTNode) {
        var nextToAstNode: ASTNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.EQ)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode).prevCodeLeaf()
        }
        // leading annotations and comments are indented at the level of the parameter itself
        val fromAstNode = node.skipLeadingWhitespaceCommentsAndAnnotations()
        if (fromAstNode != node.firstChildNode &&
            node.prevSibling { it.isWhiteSpaceWithNewline } == null &&
            node == node.treeParent?.findChildByType(VALUE_PARAMETER)
        ) {
            startIndentContext(fromAstNode, toAstNode = nextToAstNode)
        } else {
            startIndentContext(node, toAstNode = nextToAstNode, childIndent = "")
        }
    }

    private fun visitFun(node: ASTNode) {
        var nextToAstNode: ASTNode = node.lastChildLeafOrSelf
        (node.findChildByType(KtTokens.EQ) ?: node.findChildByType(BLOCK))?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode).prevCodeLeaf()
        }
        node.findChildByType(KtTokens.WHERE_KEYWORD)?.let { where ->
            nextToAstNode = startWhereContext(where, childIndent = " ".repeat(maxOf(0, where.column - 1 - node.indentWithoutNewlinePrefix.length)))
        }
        node.findChildByType(TYPE_REFERENCE)?.let { typeReference ->
            nextToAstNode = startIndentContext(typeReference.getPrecedingLeadingCommentsAndWhitespaces(), toAstNode = nextToAstNode).prevCodeLeaf()
        }
        // leading annotations and comments are indented at the level of the function itself
        startIndentContext(node, toAstNode = nextToAstNode, childIndent = "")
    }

    // also an object, which has no `where`
    private fun visitClass(node: ASTNode) {
        var nextToAstNode: ASTNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.WHERE_KEYWORD)?.let { nextToAstNode = startWhereContext(it) }
        node.findChildByType(SUPER_TYPE_LIST)?.let { superTypeList ->
            nextToAstNode = startIndentContext(
                superTypeList.getPrecedingLeadingCommentsAndWhitespaces(),
                toAstNode = superTypeList.lastChildLeafOrSelf,
            ).prevCodeLeaf()
        }
        // leading annotations and comments are indented at the level of the class itself
        startIndentContext(node, toAstNode = nextToAstNode, childIndent = "")
    }

    // `where` and its type constraint list, with the comments in front
    private fun startWhereContext(where: ASTNode, childIndent: String = INDENT): ASTNode = startIndentContext(
        where.getPrecedingLeadingCommentsAndWhitespaces(),
        toAstNode = where.nextCodeSibling!!.lastChildLeafOrSelf,
        childIndent = childIndent,
    ).prevCodeLeaf()

    private fun visitBinaryExpression(node: ASTNode) {
        if (isPartOfBinaryExpressionWrappedInCondition(node)) {
            // the condition's context is not on top of the stack when binary expressions nest
            val conditionIndentContext = stack.last { it.fromASTNode.elementType != BINARY_EXPRESSION }
            // a new context for the operator and the right-hand side
            startIndentContext(
                node.findChildByType(OPERATION_REFERENCE)!!,
                toAstNode = node.lastChildLeafOrSelf,
                nodeIndent = conditionIndentContext.nodeIndent,
                childIndent = conditionIndentContext.childIndent,
            )
            startIndentContext(node.firstChildNode)
        } else if (node.treeParent?.elementType != BINARY_EXPRESSION ||
            node.findChildByType(OPERATION_REFERENCE)?.firstChildNode?.elementType == KtTokens.ELVIS
        ) {
            startIndentContext(node)
        }
    }

    private fun visitWhen(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.LPAR)?.let { lpar ->
            nextToAstNode = startIndentContext(lpar, toAstNode = node.findChildByType(KtTokens.RPAR)!!, lastChildIndent = "").prevCodeLeaf()
        }
        startIndentContext(node, toAstNode = nextToAstNode)
    }

    // ij_kotlin_indent_before_arrow_on_new_line is off: an arrow on its own line is not indented
    private fun visitWhenEntry(node: ASTNode) {
        node.findChildByType(KtTokens.ARROW)?.let { arrow ->
            startIndentContext(arrow.nextLeaf!!, toAstNode = node.lastChildLeafOrSelf)
            startIndentContext(node, toAstNode = arrow, childIndent = "")
        }
    }

    private fun visitWhereKeywordBeforeTypeConstraintList(node: ASTNode) {
        if (node.prevLeaf != null && !node.prevLeaf.isWhiteSpaceWithNewline) {
            stack.last().activated = true
        }
        startIndentContext(node, toAstNode = node.nextCodeSibling!!.lastChildLeafOrSelf, childIndent = TYPE_CONSTRAINT_CONTINUATION_INDENT)
    }

    private fun visitKdoc(node: ASTNode) {
        node.findChildByType(KDocTokens.START)?.nextLeaf?.let {
            startIndentContext(it, toAstNode = node.lastChildLeafOrSelf, childIndent = KDOC_CONTINUATION_INDENT)
        }
    }

    private fun visitPropertyAccessor(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.EQ)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = node.lastChildLeafOrSelf).prevCodeLeaf()
        }
        // no indent on preceding annotations and comments
        startIndentContext(node, toAstNode = nextToAstNode, childIndent = "")
    }

    private fun visitConditionalLoop(node: ASTNode) {
        node.findChildByType(BODY)?.takeIf { it.nextCodeLeaf?.elementType != KtTokens.LBRACE }?.let {
            startIndentContext(it, toAstNode = node.lastChildLeafOrSelf)
        }
        node.findChildByType(CONDITION)?.let { startIndentContext(it) }
    }

    private fun visitLBracket(node: ASTNode) {
        // IntelliJ IDEA does not indent inside the brackets of a multi-annotation (KTIJ-14859)
        node.treeParent
            ?.takeUnless { it.elementType == ANNOTATION }
            ?.findChildByType(KtTokens.RBRACKET)
            ?.let { startIndentContext(node, toAstNode = it, firstChildIndent = "", lastChildIndent = "") }
    }

    private fun visitNullableType(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.RPAR)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode, childIndent = "").prevCodeLeaf()
        }
        startIndentContext(node, toAstNode = nextToAstNode)
    }

    private fun visitDestructuringDeclaration(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(KtTokens.EQ)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode).prevCodeLeaf()
        }
        node.findChildByType(KtTokens.RPAR)?.let {
            startIndentContext(node, toAstNode = nextToAstNode, lastChildIndent = "")
        }
    }

    private fun visitTryCatchFinally(node: ASTNode) {
        var nextToAstNode = node.lastChildLeafOrSelf
        node.findChildByType(FINALLY)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode, childIndent = "").prevCodeLeaf()
        }
        node.findChildByType(CATCH)?.let {
            nextToAstNode = startIndentContext(it, toAstNode = nextToAstNode, childIndent = "").prevCodeLeaf()
        }
        startIndentContext(node, toAstNode = nextToAstNode, lastChildIndent = "")
    }

    private fun ASTNode.skipLeadingWhitespaceCommentsAndAnnotations(): ASTNode =
        findChildByType(MODIFIER_LIST)?.let { modifierList ->
            modifierList.childNodes.firstOrNull { it.isCode && it.elementType != ANNOTATION_ENTRY } ?: modifierList.nextCodeSibling
        }
            ?: childNodes.firstOrNull { it.isCode }
            ?: this

    private fun ASTNode.getPrecedingLeadingCommentsAndWhitespaces(): ASTNode =
        generateSequence(this) { node -> node.prevLeaf?.takeIf { it.isWhiteSpace || it.isPartOfComment } }.last()

    private fun isPartOfBinaryExpressionWrappedInCondition(node: ASTNode) =
        generateSequence(node.treeParent) { it.treeParent }
            .takeWhile { it.elementType == BINARY_EXPRESSION || it.elementType == CONDITION }
            .lastOrNull()
            ?.elementType == CONDITION

    private fun currentIndent() = stack.last().indent()

    private fun startIndentContext(
        fromAstNode: ASTNode,
        toAstNode: ASTNode = fromAstNode.lastChildLeafOrSelf,
        nodeIndent: String = currentIndent(),
        childIndent: String = INDENT,
        firstChildIndent: String = childIndent,
        lastChildIndent: String = childIndent,
    ): IndentContext =
        IndentContext(fromAstNode, toAstNode, nodeIndent, firstChildIndent, childIndent, lastChildIndent).also { stack.addLast(it) }

    private fun visitNewLineIndentation(node: ASTNode) {
        if (node.ignoreIndent()) return
        val text = node.text
        val expected = node.expectedIndent()
        // only the indent after the last line break changes; tabs are replaced along with it
        if (text.substringAfterLast("\n") != expected) {
            rewritten[node] = text.substringBeforeLast("\n") + "\n" + expected
        }
    }

    private fun ASTNode.ignoreIndent(): Boolean {
        val next = nextLeaf
        // raw strings may start at column 0
        if (text.endsWith("\n") && next != null && next.elementType == KtTokens.OPEN_QUOTE && next.text == "\"\"\"") return true
        if (next != null && next.isPartOfComment) {
            // comments may start at column 0
            if (text.endsWith("\n")) return true
            // the inside of a multiline block comment can not be laid out
            if (next.textContains('\n') && next.elementType == KtTokens.BLOCK_COMMENT) return true
        }
        return false
    }

    private fun ASTNode.expectedIndent(): String {
        val context = stack.last()
        val next = nextLeaf
        val first = context.fromASTNode.firstChildLeafOrSelf
        val adjusted = when {
            this == first || next == first -> context.firstChildIndent
            this == context.toASTNode || next == context.toASTNode -> context.lastChildIndent
            else -> context.childIndent
        }
        return context.nodeIndent + adjusted
    }

    // The line holding the closing quotes of a multiline trimIndent/trimMargin string gets the indent of the code.
    private fun visitClosingQuotes(expectedIndent: String, node: ASTNode) {
        if (!(node.isFollowedBy("trimIndent()") || node.isFollowedBy("trimMargin()")) || !node.isMultiLine()) return
        if (node.containsMixedIndentationCharacters()) {
            findings += Finding(node.startOffset, "Indentation of multiline string should not contain both tab(s) and space(s)")
            return
        }
        // opening quotes at the start of a line put the closing quotes there too
        val corrected = if (node.prevLeaf?.txt == "\n") "" else expectedIndent
        node.childNodes.filter { it.isIndentBeforeClosingQuote() }.forEach {
            if (it.prevLeaf?.text != "\n") return@forEach
            val (actualIndent, actualContent) = it.text.splitIndentAt(it.text.length)
            if (actualIndent == corrected) return@forEach
            // only the closing-quote line is fixed; the string's content belongs to string-template-indent
            if (it.firstChildNode == null) {
                inserted += Edit(it.startOffset, it.startOffset, corrected)
            } else {
                rewritten[it.firstChildNode] = corrected + actualContent
            }
        }
    }

    private fun ASTNode.isFollowedBy(call: String) =
        nextSibling { it.elementType != KtTokens.DOT }.let { it?.elementType == CALL_EXPRESSION && it.text == call }

    private fun ASTNode.isMultiLine() = childNodes.any { it.elementType == LITERAL_STRING_TEMPLATE_ENTRY && it.text == "\n" }

    private fun ASTNode.containsMixedIndentationCharacters(): Boolean {
        val lines = text.split("\n")
            .filterNot { it.startsWith("\"\"\"") }
            .filterNot { it.endsWith("\"\"\"") }
            .filterNot { it.isBlank() }
        val prefixLength = lines.minOfOrNull { it.indentLength() } ?: 0
        return lines.joinToString("") { it.splitIndentAt(prefixLength).first }.toSet().size > 1
    }

    private fun ASTNode.isIndentBeforeClosingQuote() =
        elementType == KtTokens.CLOSING_QUOTE || (text.isBlank() && nextCodeSibling?.elementType == KtTokens.CLOSING_QUOTE)

    private fun String.indentLength() = indexOfFirst { !it.isWhitespace() }.let { if (it == -1) length else it }

    // The indent before index, or before the first non-whitespace character when that comes first, and the rest.
    private fun String.splitIndentAt(index: Int): Pair<String, String> {
        if (this == "\n") return "" to ""
        val safe = minOf(indentLength(), index)
        return take(safe) to substring(safe)
    }

    private fun ASTNode?.isElvisOperator() =
        this != null && elementType == OPERATION_REFERENCE && firstChildNode?.elementType == KtTokens.ELVIS

    // The column of this node, 1-based, on its line as rewritten so far.
    private val ASTNode.column: Int
        get() {
            var leaf = prevLeaf
            var offset = 0
            while (leaf != null) {
                val t = leaf.txt
                if (leaf.isWhiteSpace && t.contains('\n')) {
                    offset += t.length - 1 - t.lastIndexOf('\n')
                    break
                }
                offset += t.length
                leaf = leaf.prevLeaf
            }
            return offset + 1
        }

    private val ASTNode.indentWithoutNewlinePrefix: String
        get() = leaves(false).firstOrNull { it.isWhiteSpaceWithNewline }?.txt?.substringAfterLast('\n').orEmpty()
}
