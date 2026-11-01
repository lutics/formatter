package io.github.lutics.formatter.ide

import io.github.lutics.formatter.ide.Wrap.Type.CHOP_IF_NEEDED
import io.github.lutics.formatter.ide.Wrap.Type.DO_NOT_WRAP
import io.github.lutics.formatter.ide.Wrap.Type.WRAP_ALWAYS
import io.github.lutics.formatter.ide.Wrap.Type.WRAP_AS_NEEDED
import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION
import org.jetbrains.kotlin.KtNodeTypes.ANNOTATION_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.BINARY_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.BINARY_WITH_TYPE
import org.jetbrains.kotlin.KtNodeTypes.BLOCK
import org.jetbrains.kotlin.KtNodeTypes.BODY
import org.jetbrains.kotlin.KtNodeTypes.CALL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.CLASS
import org.jetbrains.kotlin.KtNodeTypes.CLASS_BODY
import org.jetbrains.kotlin.KtNodeTypes.COLLECTION_LITERAL_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.CONDITION
import org.jetbrains.kotlin.KtNodeTypes.CONTEXT_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.DESTRUCTURING_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.DOT_QUALIFIED_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.DO_WHILE
import org.jetbrains.kotlin.KtNodeTypes.ELSE
import org.jetbrains.kotlin.KtNodeTypes.ENUM_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.FOR
import org.jetbrains.kotlin.KtNodeTypes.FUN
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_LITERAL
import org.jetbrains.kotlin.KtNodeTypes.FUNCTION_TYPE
import org.jetbrains.kotlin.KtNodeTypes.IF
import org.jetbrains.kotlin.KtNodeTypes.IMPORT_DIRECTIVE
import org.jetbrains.kotlin.KtNodeTypes.INDICES
import org.jetbrains.kotlin.KtNodeTypes.IS_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.MODIFIER_LIST
import org.jetbrains.kotlin.KtNodeTypes.OBJECT_DECLARATION
import org.jetbrains.kotlin.KtNodeTypes.OPERATION_REFERENCE
import org.jetbrains.kotlin.KtNodeTypes.PACKAGE_DIRECTIVE
import org.jetbrains.kotlin.KtNodeTypes.PARENTHESIZED
import org.jetbrains.kotlin.KtNodeTypes.POSTFIX_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.PRIMARY_CONSTRUCTOR
import org.jetbrains.kotlin.KtNodeTypes.PROPERTY
import org.jetbrains.kotlin.KtNodeTypes.PROPERTY_ACCESSOR
import org.jetbrains.kotlin.KtNodeTypes.SAFE_ACCESS_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.SCRIPT
import org.jetbrains.kotlin.KtNodeTypes.SECONDARY_CONSTRUCTOR
import org.jetbrains.kotlin.KtNodeTypes.SUPER_TYPE_LIST
import org.jetbrains.kotlin.KtNodeTypes.THEN
import org.jetbrains.kotlin.KtNodeTypes.TYPEALIAS
import org.jetbrains.kotlin.KtNodeTypes.TYPE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.TYPE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_LIST
import org.jetbrains.kotlin.KtNodeTypes.VALUE_ARGUMENT_NAME
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER
import org.jetbrains.kotlin.KtNodeTypes.VALUE_PARAMETER_LIST
import org.jetbrains.kotlin.KtNodeTypes.WHEN
import org.jetbrains.kotlin.KtNodeTypes.WHEN_CONDITION_EXPRESSION
import org.jetbrains.kotlin.KtNodeTypes.WHEN_CONDITION_IN_RANGE
import org.jetbrains.kotlin.KtNodeTypes.WHEN_CONDITION_IS_PATTERN
import org.jetbrains.kotlin.KtNodeTypes.WHEN_ENTRY
import org.jetbrains.kotlin.KtNodeTypes.WHILE
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.TokenType
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.kdoc.parser.KDocElementTypes
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.lexer.KtTokens.ALL_ASSIGNMENTS
import org.jetbrains.kotlin.lexer.KtTokens.ARROW
import org.jetbrains.kotlin.lexer.KtTokens.COMMA
import org.jetbrains.kotlin.lexer.KtTokens.COMMENTS
import org.jetbrains.kotlin.lexer.KtTokens.DOT
import org.jetbrains.kotlin.lexer.KtTokens.ELVIS
import org.jetbrains.kotlin.lexer.KtTokens.EOL_COMMENT
import org.jetbrains.kotlin.lexer.KtTokens.EQ
import org.jetbrains.kotlin.lexer.KtTokens.EXCLEXCL
import org.jetbrains.kotlin.lexer.KtTokens.GT
import org.jetbrains.kotlin.lexer.KtTokens.LBRACE
import org.jetbrains.kotlin.lexer.KtTokens.LBRACKET
import org.jetbrains.kotlin.lexer.KtTokens.LPAR
import org.jetbrains.kotlin.lexer.KtTokens.LT
import org.jetbrains.kotlin.lexer.KtTokens.RBRACE
import org.jetbrains.kotlin.lexer.KtTokens.RBRACKET
import org.jetbrains.kotlin.lexer.KtTokens.RPAR
import org.jetbrains.kotlin.lexer.KtTokens.SAFE_ACCESS
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

// Port of the Kotlin plugin's KotlinBlock, KotlinCommonBlock, SyntheticKotlinBlock and NodeIndentStrategy from the
// formatter the fleet ran (intellij-community branch 261, Apache 2.0), with the fleet scheme's settings: how a node
// splits into blocks (a call chain at its dots, an elvis at its `?:`), and the indent and wrap each child block gets.
// The plugin's CONTEXT_RECEIVER_LIST is the node this parser builds as CONTEXT_PARAMETER_LIST.
// The scheme's wrap settings are written in place: argument and parameter lists, annotations of classes, functions and
// properties, and context parameters always wrap; a call chain chops (WRAP_ON_EVERY_ITEM); supertype lists and elvis
// wrap as needed; assignments, enum constants, expression bodies and annotations of parameters and locals never wrap.

typealias WrappingStrategy = (ASTNode) -> Wrap?

private val QUALIFIED_OPERATION = TokenSet.create(DOT, SAFE_ACCESS)
private val QUALIFIED_EXPRESSIONS = TokenSet.create(DOT_QUALIFIED_EXPRESSION, SAFE_ACCESS_EXPRESSION)
private val ELVIS_SET = TokenSet.create(ELVIS)
private val QUALIFIED_EXPRESSIONS_WITHOUT_WRAP = TokenSet.create(IMPORT_DIRECTIVE, PACKAGE_DIRECTIVE)
private const val KDOC_COMMENT_INDENT = 1
private val BINARY_EXPRESSIONS = TokenSet.create(BINARY_EXPRESSION, BINARY_WITH_TYPE, IS_EXPRESSION)
private val KDOC_CONTENT = TokenSet.create(KDocTokens.KDOC, KDocElementTypes.KDOC_SECTION, KDocElementTypes.KDOC_TAG)
private val ANNOTATIONS = TokenSet.create(ANNOTATION_ENTRY, ANNOTATION)
private val CONTEXT_PARAMETERS = TokenSet.create(CONTEXT_PARAMETER_LIST)

private val NO_WRAPPING: WrappingStrategy = { null }

private fun ASTNode.children(): Sequence<ASTNode> = generateSequence(firstChildNode) { it.treeNext }

private fun ASTNode.siblings(forward: Boolean): Sequence<ASTNode> =
    generateSequence(if (forward) treeNext else treePrev) { if (forward) it.treeNext else it.treePrev }

private val ASTNode.startOffset get() = textRange.startOffset

// PsiElement.isMultiline: the element's text spans lines.
private fun PsiElement.isMultiline() = textContains('\n')

// containsLineBreakInChild: the file text between the offsets has a line break (the document answers it).
private fun PsiElement.containsLineBreakInChild(startOffset: Int, endOffset: Int): Boolean {
    val text = containingFile.text
    if (endOffset > text.length || startOffset >= endOffset) {
        var child: PsiElement? = firstChild
        while (child != null) {
            if (child.textRange.startOffset >= startOffset && child.textRange.endOffset <= endOffset && child.textContains('\n')) return true
            child = child.nextSibling
        }
        return false
    }
    return text.indexOf('\n', startOffset).let { it in startOffset until endOffset }
}

class KotlinBlock(
    override val node: ASTNode,
    override val indent: Indent?,
    override val wrap: Wrap?,
    private val spacingBuilder: KotlinSpacing,
) : Block {
    override val textRange: TextRange get() = node.textRange
    override val subBlocks: List<Block> by lazy { buildChildren() }

    override fun spacing(child1: Block?, child2: Block): Spacing? = spacingBuilder.getSpacing(this, child1, child2)

    private fun createBlock(node: ASTNode, indent: Indent?, wrap: Wrap?): Block = KotlinBlock(node, indent, wrap, spacingBuilder)

    private fun buildChildren(): List<Block> {
        val nodeSubBlocks = buildSubBlocks()
        val psi = node.psi
        return when {
            node.elementType in QUALIFIED_EXPRESSIONS -> splitSubBlocksOnDot(nodeSubBlocks)
            psi is KtBinaryExpression && psi.operationToken == ELVIS -> splitSubBlocksOnElvis(nodeSubBlocks)
            else -> nodeSubBlocks
        }
    }

    private fun splitSubBlocksOnDot(nodeSubBlocks: List<Block>): List<Block> {
        if (node.treeParent?.isQualifier == true || node.isCallChainWithoutWrap) return nodeSubBlocks
        val operationBlockIndex = nodeSubBlocks.indexOfBlockWithType(QUALIFIED_OPERATION)
        if (operationBlockIndex == -1) return nodeSubBlocks
        val block = nodeSubBlocks.first()
        val wrap = createWrapForQualifierExpression(node)
        val enforceIndentToChildren = anyCallInCallChainIsWrapped(node)
        val indent = createIndentForQualifierExpression(enforceIndentToChildren)
        val newBlock = block.processBlock(wrap, enforceIndentToChildren)
        return nodeSubBlocks.replaceFirst(newBlock).splitAtIndex(operationBlockIndex, indent, wrap)
    }

    private fun Block.processBlock(wrap: Wrap?, enforceIndentToChildren: Boolean): Block {
        if (node.elementType != POSTFIX_EXPRESSION && node.elementType !in QUALIFIED_EXPRESSIONS) return this
        val enforceIndent = enforceIndentToChildren && anyCallInCallChainIsWrapped(node)
        val resultWrap = if (node.wrapForFirstCallInChainIsAllowed) wrap ?: Wrap(CHOP_IF_NEEDED, true) else null
        val newBlock = subBlocks.first().processBlock(resultWrap, enforceIndent)
        val operationIndex = subBlocks.indexOfBlockWithType(QUALIFIED_OPERATION)
        val blocks = subBlocks.replaceFirst(newBlock).let {
            if (operationIndex != -1) it.splitAtIndex(operationIndex, createIndentForQualifierExpression(enforceIndent), resultWrap) else it
        }
        // the rebuilt blocks under this block's node, spaced as this block
        return SyntheticBlock(node, blocks, null, null, spacingBuilder) { this }
    }

    private fun List<Block>.replaceFirst(block: Block): List<Block> = toMutableList().apply { this[0] = block }

    // WRAP_FIRST_METHOD_IN_CALL_CHAIN is off
    private val ASTNode.wrapForFirstCallInChainIsAllowed: Boolean
        get() = unwrapQualifier()?.isCall == true && receiverIsCall()

    private fun createWrapForQualifierExpression(node: ASTNode): Wrap? = if (node.wrapForFirstCallInChainIsAllowed) Wrap(CHOP_IF_NEEDED, true) else null

    // CONTINUATION_INDENT_FOR_CHAINED_CALLS is off
    private fun createIndentForQualifierExpression(enforceIndentToChildren: Boolean): Indent =
        if (enforceIndentToChildren) Indent.NORMAL_ENFORCED else Indent.NORMAL

    // the blocks from the operation on grouped under it, spaced as in the operation's parent
    private fun List<Block>.splitAtIndex(index: Int, indent: Indent?, wrap: Wrap?): List<Block> {
        val operationSyntheticBlock = SyntheticBlock(this[index].node, subList(index, size), indent, wrap, spacingBuilder) {
            val parent = it.treeParent ?: node
            createBlock(if (parent.elementType === OPERATION_REFERENCE) parent.treeParent ?: parent else parent, null, null)
        }
        return subList(0, index) + operationSyntheticBlock
    }

    private fun splitSubBlocksOnElvis(nodeSubBlocks: List<Block>): List<Block> {
        val elvisIndex = nodeSubBlocks.indexOfBlockWithType(ELVIS_SET)
        // CONTINUATION_INDENT_IN_ELVIS is off
        return if (elvisIndex >= 0) nodeSubBlocks.splitAtIndex(elvisIndex, Indent.NORMAL, null) else nodeSubBlocks
    }

    private fun createChildIndent(child: ASTNode): Indent {
        val childParent = child.treeParent
        val childType = child.elementType
        if (childParent != null && isInCodeChunk(childParent)) return Indent.NONE

        // do not indent child after heading comments inside declaration
        if (childParent != null && childParent.psi is KtDeclaration) {
            val prev = getPrevWithoutWhitespace(child)
            if (prev != null && prev.elementType in COMMENTS && getSiblingWithoutWhitespaceAndComments(prev) == null) return Indent.NONE
        }

        for (strategy in INDENT_RULES) {
            val indent = strategy.indent(child)
            if (indent != null) return indent
        }

        if (childParent != null) {
            val parentType = childParent.elementType
            if (parentType === VALUE_PARAMETER_LIST || parentType === VALUE_ARGUMENT_LIST) {
                val prev = getPrevWithoutWhitespace(child)
                // a `)` after a trailing comma and a blank line is indented like the items
                if (childType === RPAR && (prev == null || prev.elementType !== COMMA || !hasDoubleLineBreakBefore(child))) return Indent.NONE
                // CONTINUATION_INDENT_IN_ARGUMENT_LISTS is off
                return Indent.NORMAL
            }
            if (parentType === TYPE_PARAMETER_LIST || parentType === TYPE_ARGUMENT_LIST) return Indent.CONTINUATION_WITHOUT_FIRST
        }
        return Indent.NONE
    }

    // a file parses no code fragments, so a script's block is the only code chunk
    private fun isInCodeChunk(node: ASTNode) = node.elementType == BLOCK && node.treeParent?.elementType == SCRIPT

    private fun buildSubBlock(child: ASTNode, wrappingStrategy: WrappingStrategy): Block {
        val childWrap = wrappingStrategy(child)
        // Skip one sub-level for operators, so type of block node is an element type of operator
        if (child.elementType === OPERATION_REFERENCE) {
            val operationNode = child.firstChildNode
            if (operationNode != null) return createBlock(operationNode, createChildIndent(child), childWrap)
        }
        return createBlock(child, createChildIndent(child), childWrap)
    }

    private fun buildSubBlocks(): List<Block> {
        val wrappingStrategy = wrappingStrategy()
        val childNodes = if (node.elementType == BINARY_EXPRESSION) {
            val binaryExpression = node.psi as? KtBinaryExpression
            if (binaryExpression != null && binaryExpression.operationToken in ALL_ASSIGNMENTS) {
                node.children()
            } else {
                val result = mutableListOf<ASTNode>()
                collectBinaryExpressionChildren(node, result)
                result.asSequence()
            }
        } else {
            node.children()
        }
        return childNodes
            .filter { it.textRange.length > 0 && it.elementType != TokenType.WHITE_SPACE }
            .map { buildSubBlock(it, wrappingStrategy) }
            .toList()
    }

    private fun collectBinaryExpressionChildren(node: ASTNode, result: MutableList<ASTNode>) {
        for (child in node.children()) {
            if (child.elementType == BINARY_EXPRESSION) collectBinaryExpressionChildren(child, result) else result.add(child)
        }
    }

    private fun wrappingStrategy(): WrappingStrategy {
        val elementType = node.elementType
        val parentElementType = node.treeParent?.elementType
        val nodePsi = node.psi
        when {
            elementType === VALUE_ARGUMENT_LIST ->
                return getWrappingStrategyForItemList(
                    WRAP_ALWAYS,
                    VALUE_ARGUMENT,
                    node.addTrailingComma,
                    additionalWrap = trailingCommaWrappingStrategyWithMultiLineCheck(LPAR, RPAR),
                )

            elementType === VALUE_PARAMETER_LIST -> {
                when (parentElementType) {
                    FUN, PRIMARY_CONSTRUCTOR, SECONDARY_CONSTRUCTOR -> return getWrappingStrategyForItemList(
                        WRAP_ALWAYS,
                        VALUE_PARAMETER,
                        node.addTrailingComma,
                        additionalWrap = trailingCommaWrappingStrategyWithMultiLineCheck(LPAR, RPAR),
                    )

                    FUNCTION_TYPE -> return trailingCommaWrappingStrategyWithMultiLineCheck(LPAR, RPAR)
                    FUNCTION_LITERAL -> if (trailingCommaExistsOrCanExist(nodePsi.parent)) {
                        val check = thisOrPrevIsMultiLineElement(LBRACE, ARROW)
                        return { childElement -> createWrapAlwaysIf(getSiblingWithoutWhitespaceAndComments(childElement) == null || check(childElement)) }
                    }
                }
            }

            elementType === FUNCTION_LITERAL -> if (trailingCommaExistsOrCanExist(nodePsi)) {
                return trailingCommaWrappingStrategy(leftAnchor = LBRACE, rightAnchor = ARROW)
            }

            elementType === WHEN_ENTRY -> if (trailingCommaExistsOrCanExist(nodePsi)) {
                val check = thisOrPrevIsMultiLineElement(LBRACE, ARROW)
                return trailingCommaWrappingStrategy(rightAnchor = ARROW) {
                    getSiblingWithoutWhitespaceAndComments(it, true) != null && check(it)
                }
            }

            elementType === DESTRUCTURING_DECLARATION -> {
                nodePsi as KtDestructuringDeclaration
                if (nodePsi.valOrVarKeyword == null) return trailingCommaWrappingStrategyWithMultiLineCheck(LPAR, RPAR)
                if (trailingCommaExistsOrCanExist(nodePsi)) {
                    val check = thisOrPrevIsMultiLineElement(LPAR, RPAR)
                    return trailingCommaWrappingStrategy(leftAnchor = LPAR, rightAnchor = RPAR, filter = { it.elementType !== EQ }) {
                        getSiblingWithoutWhitespaceAndComments(it, true) != null && check(it)
                    }
                }
            }

            elementType === INDICES || elementType === COLLECTION_LITERAL_EXPRESSION -> return trailingCommaWrappingStrategyWithMultiLineCheck(LBRACKET, RBRACKET)

            elementType === TYPE_PARAMETER_LIST || elementType === TYPE_ARGUMENT_LIST -> return trailingCommaWrappingStrategyWithMultiLineCheck(LT, GT)

            elementType === SUPER_TYPE_LIST -> {
                val wrap = Wrap(WRAP_AS_NEEDED, false)
                return { childElement -> if (childElement.psi is KtSuperTypeListEntry) wrap else null }
            }

            elementType === CLASS_BODY -> return getWrappingStrategyForItemList(DO_NOT_WRAP, ENUM_ENTRY)

            elementType === MODIFIER_LIST -> {
                when (val parent = node.treeParent.psi) {
                    is KtParameter -> return getWrappingStrategyForItemList(DO_NOT_WRAP, ANNOTATIONS, !node.treeParent.isFirstParameter())
                    is KtClassOrObject, is KtTypeAlias -> return getWrappingStrategyForItemList(WRAP_ALWAYS, ANNOTATIONS)
                    is KtNamedFunction, is KtSecondaryConstructor -> return getWrappingStrategyForModifierList(WRAP_ALWAYS)
                    is KtProperty -> return getWrappingStrategyForModifierList(if (parent.isLocal) DO_NOT_WRAP else WRAP_ALWAYS)
                }
            }

            elementType === VALUE_PARAMETER -> return wrapAfterAnnotation(DO_NOT_WRAP)

            nodePsi is KtClassOrObject || nodePsi is KtTypeAlias -> return wrapAfterAnnotation(WRAP_ALWAYS)

            nodePsi is KtNamedFunction || nodePsi is KtSecondaryConstructor -> return wrap@{ childElement ->
                getWrapAfterAnnotation(childElement, WRAP_ALWAYS)?.let { return@wrap it }
                getWrapAfterContextParameters(childElement)?.let { return@wrap it }
                if (getSiblingWithoutWhitespaceAndComments(childElement)?.elementType == EQ) Wrap(DO_NOT_WRAP, true) else null
            }

            nodePsi is KtProperty -> return wrap@{ childElement ->
                getWrapAfterAnnotation(childElement, if (nodePsi.isLocal) DO_NOT_WRAP else WRAP_ALWAYS)?.let { return@wrap it }
                getWrapAfterContextParameters(childElement)?.let { return@wrap it }
                if (getSiblingWithoutWhitespaceAndComments(childElement)?.elementType == EQ) Wrap(DO_NOT_WRAP, true) else null
            }

            nodePsi is KtBinaryExpression && nodePsi.operationToken == EQ -> return { childElement ->
                if (getSiblingWithoutWhitespaceAndComments(childElement)?.elementType == OPERATION_REFERENCE) Wrap(DO_NOT_WRAP, true) else null
            }

            nodePsi is KtBinaryExpression && nodePsi.operationToken == ELVIS && nodePsi.getStrictParentOfType<KtStringTemplateExpression>() == null -> return { childElement ->
                if (childElement.elementType == OPERATION_REFERENCE && (childElement.psi as? KtOperationReferenceExpression)?.operationSignTokenType == ELVIS) {
                    Wrap(WRAP_AS_NEEDED, true)
                } else {
                    null
                }
            }
        }
        return NO_WRAPPING
    }

    // ALLOW_TRAILING_COMMA and ALLOW_TRAILING_COMMA_ON_CALL_SITE are on and every list asked about may take a trailing
    // comma, so one that spans lines gets it
    private val ASTNode.addTrailingComma: Boolean
        get() = psi.isMultiline()

    private fun ASTNode.notDelimiterSiblingNodeInSequence(forward: Boolean, delimiterType: IElementType, typeOfLastElement: IElementType): ASTNode? {
        var sibling: ASTNode? = null
        for (element in siblings(forward).filter { it.elementType != TokenType.WHITE_SPACE }.takeWhile { it.elementType != typeOfLastElement }) {
            val elementType = element.elementType
            if (!forward) {
                sibling = element
                if (elementType != delimiterType && elementType != EOL_COMMENT) break
            } else {
                if (elementType != EOL_COMMENT) break
                sibling = element
            }
        }
        return sibling
    }

    private fun thisOrPrevIsMultiLineElement(typeOfFirstElement: IElementType, typeOfLastElement: IElementType) = fun(childElement: ASTNode): Boolean {
        val delimiterType = COMMA
        when (childElement.elementType) {
            typeOfFirstElement, typeOfLastElement, delimiterType, EOL_COMMENT, TokenType.WHITE_SPACE -> return false
        }
        val psi = childElement.psi ?: return false
        if (psi.isMultiline()) return true
        val startOffset = childElement.notDelimiterSiblingNodeInSequence(false, delimiterType, typeOfFirstElement)?.startOffset ?: psi.textRange.startOffset
        val endOffset = childElement.notDelimiterSiblingNodeInSequence(true, delimiterType, typeOfLastElement)?.psi?.textRange?.endOffset ?: psi.textRange.endOffset
        return psi.parent.containsLineBreakInChild(startOffset, endOffset)
    }

    private fun trailingCommaWrappingStrategyWithMultiLineCheck(leftAnchor: IElementType, rightAnchor: IElementType) = trailingCommaWrappingStrategy(
        leftAnchor = leftAnchor,
        rightAnchor = rightAnchor,
        checkTrailingComma = true,
        additionalCheck = thisOrPrevIsMultiLineElement(leftAnchor, rightAnchor),
    )

    private fun trailingCommaWrappingStrategy(
        leftAnchor: IElementType? = null,
        rightAnchor: IElementType? = null,
        checkTrailingComma: Boolean = false,
        filter: (ASTNode) -> Boolean = { true },
        additionalCheck: (ASTNode) -> Boolean = { false },
    ): WrappingStrategy = fun(childElement: ASTNode): Wrap? {
        if (!filter(childElement)) return null
        val childElementType = childElement.elementType
        return createWrapAlwaysIf(
            (!checkTrailingComma || childElement.treeParent.addTrailingComma) &&
                (
                    rightAnchor != null && rightAnchor === childElementType ||
                        leftAnchor != null && leftAnchor === getPrevWithoutWhitespace(childElement)?.elementType ||
                        additionalCheck(childElement)
                    ),
        )
    }

    // TrailingCommaState EXISTS or MISSING: a comma owner that spans lines and may take a trailing comma
    private fun trailingCommaExistsOrCanExist(element: PsiElement): Boolean = when (element) {
        is KtFunctionLiteral -> element.arrow != null && multiline(element, element.valueParameterList?.textRange?.startOffset, element.arrow?.textRange?.endOffset)
        is KtWhenEntry -> !element.isElse && (element.parent as KtWhenExpression).leftParenthesis != null && element.guard == null &&
            multiline(element, element.textRange.startOffset, element.arrow?.textRange?.endOffset)
        is KtDestructuringDeclaration -> multiline(element, element.lPar?.textRange?.startOffset, element.rPar?.textRange?.endOffset)
        else -> false
    }

    private fun multiline(element: PsiElement, start: Int?, end: Int?): Boolean =
        element.containsLineBreakInChild(start ?: element.textRange.startOffset, end ?: element.textRange.endOffset)
}

// SyntheticKotlinBlock: blocks grouped under one anchor node, with spacing from a stand-in parent.
class SyntheticBlock(
    override val node: ASTNode,
    override val subBlocks: List<Block>,
    override val indent: Indent?,
    override val wrap: Wrap?,
    private val spacingBuilder: KotlinSpacing,
    private val createParentSyntheticSpacingBlock: (ASTNode) -> Block,
) : Block {
    override val textRange = TextRange(subBlocks.first().textRange.startOffset, subBlocks.last().textRange.endOffset)

    override fun spacing(child1: Block?, child2: Block): Spacing? = spacingBuilder.getSpacing(createParentSyntheticSpacingBlock(node), child1, child2)
}

private fun ASTNode.qualifierReceiver(): ASTNode? =
    (unwrapQualifier()?.psi as? KtQualifiedExpression)?.receiverExpression?.node?.unwrapQualifier()

private tailrec fun ASTNode.unwrapQualifier(): ASTNode? {
    if (elementType in QUALIFIED_EXPRESSIONS) return this
    val psi = psi as? KtPostfixExpression ?: return null
    if (psi.operationToken != EXCLEXCL) return null
    return psi.baseExpression?.node?.unwrapQualifier()
}

private fun ASTNode.receiverIsCall(): Boolean = qualifierReceiver()?.isCall == true

private val ASTNode.isCallChainWithoutWrap: Boolean
    get() {
        val callChainParent = generateSequence(treeParent) { it.treeParent }.firstOrNull { !it.isQualifier } ?: return true
        return callChainParent.elementType in QUALIFIED_EXPRESSIONS_WITHOUT_WRAP
    }

private val ASTNode.isQualifier: Boolean
    get() {
        var currentNode: ASTNode? = this
        while (currentNode != null) {
            if (currentNode.elementType in QUALIFIED_EXPRESSIONS) return true
            if ((currentNode.psi as? KtPostfixExpression)?.operationToken != EXCLEXCL) return false
            currentNode = currentNode.treeParent
        }
        return false
    }

private val ASTNode.isCall: Boolean
    get() = unwrapQualifier()?.lastChildNode?.elementType == CALL_EXPRESSION

private fun anyCallInCallChainIsWrapped(node: ASTNode): Boolean {
    val sequentialNodes = generateSequence(node) {
        when (it.elementType) {
            POSTFIX_EXPRESSION, in QUALIFIED_EXPRESSIONS -> it.firstChildNode
            PARENTHESIZED -> getSiblingWithoutWhitespaceAndComments(it.firstChildNode, true)
            else -> null
        }
    }
    return sequentialNodes.any {
        val checkedElement = when (it.elementType) {
            in QUALIFIED_EXPRESSIONS -> it.findChildByType(QUALIFIED_OPERATION)
            PARENTHESIZED -> it.lastChildNode
            else -> null
        }
        checkedElement != null && hasLineBreakBefore(checkedElement)
    }
}

private fun ASTNode.isFirstParameter(): Boolean = treePrev?.elementType == LPAR

private fun hasDoubleLineBreakBefore(node: ASTNode): Boolean {
    val prevSibling = previousLeaf(node) ?: return false
    return prevSibling.text.count { it == '\n' } >= 2
}

private fun wrapAfterAnnotation(wrapType: Wrap.Type): WrappingStrategy = { childElement -> getWrapAfterAnnotation(childElement, wrapType) }

private fun getWrapAfterAnnotation(childElement: ASTNode, wrapType: Wrap.Type): Wrap? =
    getWrapAfterElementInModifierList(childElement, wrapType) { it?.elementType in ANNOTATIONS }

private fun getWrapAfterContextParameters(childElement: ASTNode): Wrap? =
    getWrapAfterElementInModifierList(childElement, WRAP_ALWAYS) { it?.elementType == CONTEXT_PARAMETER_LIST }

private fun getWrapAfterElementInModifierList(childElement: ASTNode, wrapType: Wrap.Type, acceptLastChildNode: (ASTNode?) -> Boolean): Wrap? {
    if (childElement.elementType in COMMENTS) return null
    var prevLeaf = childElement.treePrev
    while (prevLeaf?.elementType == TokenType.WHITE_SPACE) prevLeaf = prevLeaf.treePrev
    if (prevLeaf?.elementType == MODIFIER_LIST && acceptLastChildNode(prevLeaf.lastChildNode)) return Wrap(wrapType, true)
    return null
}

fun needWrapArgumentList(psi: PsiElement): Boolean {
    val args = (psi as? KtValueArgumentList)?.arguments
    return args?.singleOrNull()?.getArgumentExpression() !is KtObjectLiteralExpression
}

private fun hasLineBreakBefore(node: ASTNode): Boolean {
    var prev = previousLeaf(node)
    while (prev != null && prev.psi is PsiComment) prev = previousLeaf(prev)
    return prev?.elementType == TokenType.WHITE_SPACE && prev.textContains('\n')
}

// ASTNode.leaves(forward = false): the leaves before this node in the whole tree.
private fun previousLeaf(node: ASTNode): ASTNode? {
    var current: ASTNode? = node
    while (current != null && current.treePrev == null) current = current.treeParent
    var prev = current?.treePrev ?: return null
    while (prev.lastChildNode != null) prev = prev.lastChildNode
    return prev
}

private fun getPrevWithoutWhitespace(pNode: ASTNode): ASTNode? = pNode.siblings(forward = false).firstOrNull { it.elementType != TokenType.WHITE_SPACE }

private fun getSiblingWithoutWhitespaceAndComments(pNode: ASTNode, forward: Boolean = false): ASTNode? =
    pNode.siblings(forward = forward).firstOrNull { it.elementType != TokenType.WHITE_SPACE && it.elementType !in COMMENTS }

private fun getWrappingStrategyForItemList(wrapType: Wrap.Type, itemType: IElementType, wrapFirstElement: Boolean = false, additionalWrap: WrappingStrategy? = null): WrappingStrategy {
    val itemWrap = Wrap(wrapType, wrapFirstElement)
    return { childElement -> additionalWrap?.invoke(childElement) ?: if (childElement.elementType === itemType) itemWrap else null }
}

// an item, or the element after one unless either is an end-of-line comment
private fun ASTNode.isOrFollowsItem(itemTypes: TokenSet): Boolean {
    val prevType = getPrevWithoutWhitespace(this)?.elementType
    return elementType in itemTypes || prevType in itemTypes && elementType != EOL_COMMENT && prevType != EOL_COMMENT
}

private fun getWrappingStrategyForItemList(wrapType: Wrap.Type, itemTypes: TokenSet, wrapFirstElement: Boolean = false): WrappingStrategy {
    val itemWrap = Wrap(wrapType, wrapFirstElement)
    return { childElement -> if (childElement.isOrFollowsItem(itemTypes)) itemWrap else null }
}

private fun getWrappingStrategyForModifierList(annotationsWrapType: Wrap.Type): WrappingStrategy {
    val annotationWrap = Wrap(annotationsWrapType, false)
    val contextParametersWrap = Wrap(WRAP_ALWAYS, false)
    return { childElement ->
        when {
            childElement.isOrFollowsItem(ANNOTATIONS) -> annotationWrap
            childElement.isOrFollowsItem(CONTEXT_PARAMETERS) -> contextParametersWrap
            else -> null
        }
    }
}

private fun createWrapAlwaysIf(option: Boolean): Wrap? = if (option) Wrap(WRAP_ALWAYS, true) else null

private fun List<Block>.indexOfBlockWithType(tokenSet: TokenSet): Int = indexOfFirst { block -> block.node.elementType in tokenSet }

private fun hasErrorElementBefore(node: ASTNode): Boolean {
    val prevSibling = getPrevWithoutWhitespace(node) ?: return false
    if (prevSibling.elementType == TokenType.ERROR_ELEMENT) return true
    var last: ASTNode = prevSibling
    while (last.lastChildNode != null) last = last.lastChildNode
    return last.elementType == TokenType.ERROR_ELEMENT
}

private fun ASTNode.prevNonWhitespace(): ASTNode? {
    var prev = treePrev
    while (prev != null && prev.elementType == TokenType.WHITE_SPACE) prev = prev.treePrev
    if (prev != null) return prev
    return treeParent?.prevNonWhitespace()
}

// Suppress indent for binary expressions when there is a block higher in the tree that forces its indent to children
// ('if' condition or elvis).
private fun ASTNode.suppressBinaryExpressionIndent(): Boolean {
    var psi = psi.parent as? KtBinaryExpression ?: return false
    while (psi.parent is KtBinaryExpression) psi = psi.parent as KtBinaryExpression
    return psi.parent?.node?.elementType == CONDITION || psi.operationToken == ELVIS
}

// NodeIndentStrategy.PositionStrategy
private class IndentRule(
    val within: TokenSet? = null,
    val withinCallback: ((ASTNode) -> Boolean)? = null,
    val forType: TokenSet? = null,
    val notForType: TokenSet? = null,
    val forElement: ((ASTNode) -> Boolean)? = null,
    val indent: Indent,
) {
    fun indent(node: ASTNode): Indent? {
        if (forType != null && node.elementType !in forType) return null
        if (notForType != null && node.elementType in notForType) return null
        if (forElement?.invoke(node) == false) return null
        val parent = node.treeParent
        if (parent != null) {
            if (within != null && parent.elementType !in within) return null
            if (withinCallback?.invoke(parent) == false) return null
        } else if (within != null) {
            return null
        }
        return indent
    }
}

private fun types(vararg types: IElementType) = TokenSet.create(*types)

// Every continuationIf option is off in the official style: the normal indent.
private val INDENT_RULES = arrayOf(
    // No indent for braces in blocks
    IndentRule(within = types(BLOCK, CLASS_BODY, FUNCTION_LITERAL), forType = types(RBRACE, LBRACE), indent = Indent.NONE),
    // Indent for block content
    IndentRule(within = types(BLOCK, CLASS_BODY, FUNCTION_LITERAL), notForType = types(RBRACE, LBRACE, BLOCK), indent = Indent.NORMAL),
    // Indent for template content
    IndentRule(
        within = types(LONG_STRING_TEMPLATE_ENTRY),
        notForType = types(KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.LONG_TEMPLATE_ENTRY_END),
        indent = Indent.NORMAL,
    ),
    // No indent for braces in template
    IndentRule(
        within = types(LONG_STRING_TEMPLATE_ENTRY),
        forType = types(KtTokens.LONG_TEMPLATE_ENTRY_START, KtTokens.LONG_TEMPLATE_ENTRY_END),
        indent = Indent.NONE,
    ),
    // Indent for property accessors
    IndentRule(within = types(PROPERTY), forType = types(PROPERTY_ACCESSOR), indent = Indent.NORMAL),
    // For a single statement in 'for'
    IndentRule(within = types(BODY), notForType = types(BLOCK), indent = Indent.NORMAL),
    // For single statement in THEN and ELSE
    IndentRule(within = types(THEN, ELSE), notForType = types(BLOCK), indent = Indent.NORMAL),
    // Expression body
    IndentRule(within = types(FUN), forElement = { it.psi is KtExpression && it.psi !is KtBlockExpression }, indent = Indent.NORMAL),
    // Line comment at expression body position
    IndentRule(
        forElement = { node ->
            val psi = node.psi
            val parent = psi.parent
            if (psi is PsiComment && parent is KtDeclarationWithInitializer) nextSiblingIgnoringWhitespace(psi) == parent.initializer else false
        },
        indent = Indent.NORMAL,
    ),
    // If condition: CONTINUATION_INDENT_IN_IF_CONDITIONS is off
    IndentRule(within = types(CONDITION), indent = Indent.NORMAL_ENFORCED),
    // Property accessor expression body
    IndentRule(within = types(PROPERTY_ACCESSOR), forElement = { it.psi is KtExpression && it.psi !is KtBlockExpression }, indent = Indent.NORMAL),
    // Property initializer
    IndentRule(within = types(PROPERTY), forElement = { it.psi is KtExpression }, indent = Indent.NORMAL),
    // Destructuring declaration
    IndentRule(within = types(DESTRUCTURING_DECLARATION), forElement = { it.psi is KtExpression }, indent = Indent.NORMAL),
    // Assignment expressions
    IndentRule(
        within = types(BINARY_EXPRESSION),
        withinCallback = { (it.psi as? KtBinaryExpression)?.operationToken in ALL_ASSIGNMENTS },
        forElement = { (it.psi?.parent as? KtBinaryExpression)?.right == it.psi },
        indent = Indent.NORMAL,
    ),
    // Indent for parts
    IndentRule(
        within = types(PROPERTY, FUN, DESTRUCTURING_DECLARATION, SECONDARY_CONSTRUCTOR),
        notForType = types(
            BLOCK, KtTokens.FUN_KEYWORD, KtTokens.VAL_KEYWORD, KtTokens.VAR_KEYWORD, KtTokens.CONSTRUCTOR_KEYWORD, RPAR, EOL_COMMENT,
            CONTEXT_PARAMETER_LIST, MODIFIER_LIST,
        ),
        indent = Indent.CONTINUATION_WITHOUT_FIRST,
    ),
    // Chained calls
    IndentRule(
        within = QUALIFIED_EXPRESSIONS,
        forType = types(EOL_COMMENT, KtTokens.BLOCK_COMMENT, KtTokens.DOC_COMMENT, KtTokens.SHEBANG_COMMENT),
        indent = Indent.NORMAL,
    ),
    // Colon of delegation list
    IndentRule(within = types(CLASS, OBJECT_DECLARATION), forType = types(KtTokens.COLON), indent = Indent.NORMAL),
    // Delegation list
    IndentRule(within = types(SUPER_TYPE_LIST), indent = Indent.NORMAL),
    // Indices
    IndentRule(within = types(INDICES), notForType = types(RBRACKET), indent = Indent.NORMAL),
    // Binary expressions
    IndentRule(within = BINARY_EXPRESSIONS, forElement = { node -> !node.suppressBinaryExpressionIndent() }, indent = Indent.CONTINUATION_WITHOUT_FIRST),
    // Parenthesized expression
    IndentRule(within = types(PARENTHESIZED), indent = Indent.CONTINUATION_WITHOUT_FIRST),
    // Opening parenthesis for conditions
    IndentRule(forType = types(LPAR), within = types(IF, WHEN_ENTRY, WHILE, DO_WHILE, FOR, WHEN), indent = Indent.CONTINUATION_WITHOUT_FIRST_RELATIVE),
    // Closing parenthesis for conditions
    IndentRule(
        forType = types(RPAR),
        forElement = { node -> !hasErrorElementBefore(node) || node.prevNonWhitespace()?.textLength == 0 },
        within = types(IF, WHEN_ENTRY, WHILE, DO_WHILE, FOR, WHEN),
        indent = Indent.NONE,
    ),
    // Closing parenthesis for incomplete conditions
    IndentRule(
        forType = types(RPAR),
        forElement = { node -> hasErrorElementBefore(node) },
        within = types(IF, WHEN_ENTRY, WHILE, DO_WHILE, FOR, WHEN),
        indent = Indent.CONTINUATION_WITHOUT_FIRST,
    ),
    // For WHEN content
    IndentRule(within = types(WHEN), notForType = types(RBRACE, LBRACE, KtTokens.WHEN_KEYWORD), indent = Indent.NORMAL),
    // KDoc comment indent
    IndentRule(within = KDOC_CONTENT, forType = types(KDocTokens.LEADING_ASTERISK, KDocTokens.END), indent = Indent(Indent.Type.SPACES, spaces = KDOC_COMMENT_INDENT)),
    // Block in when entry
    IndentRule(
        within = types(WHEN_ENTRY),
        notForType = types(BLOCK, WHEN_CONDITION_EXPRESSION, WHEN_CONDITION_IN_RANGE, WHEN_CONDITION_IS_PATTERN, KtTokens.ELSE_KEYWORD, ARROW),
        indent = Indent.NORMAL,
    ),
    // Indent before arrow on new line: INDENT_BEFORE_ARROW_ON_NEW_LINE is on
    IndentRule(within = types(WHEN_ENTRY), forType = types(ARROW), forElement = { it.treeParent.startOffset != it.startOffset }, indent = Indent.NORMAL),
    // Parameter list
    IndentRule(within = types(VALUE_PARAMETER_LIST), forElement = { it.elementType == VALUE_PARAMETER && it.psi.prevSibling != null }, indent = Indent.NORMAL),
    // Where clause
    IndentRule(within = types(CLASS, FUN, PROPERTY), forType = types(KtTokens.WHERE_KEYWORD), indent = Indent.CONTINUATION),
    // Array literals
    IndentRule(within = types(COLLECTION_LITERAL_EXPRESSION), notForType = types(LBRACKET, RBRACKET), indent = Indent.NORMAL),
    // Context Parameters List
    IndentRule(within = types(CONTEXT_PARAMETER_LIST), forType = types(VALUE_PARAMETER), indent = Indent.NORMAL),
    // Type aliases
    IndentRule(
        within = types(TYPEALIAS),
        notForType = types(KtTokens.TYPE_ALIAS_KEYWORD, EOL_COMMENT, MODIFIER_LIST, KtTokens.BLOCK_COMMENT, KtTokens.DOC_COMMENT),
        indent = Indent.CONTINUATION,
    ),
    // Default parameter values
    IndentRule(
        within = types(VALUE_PARAMETER),
        forElement = { node -> node.psi != null && node.psi == (node.psi.parent as? KtParameter)?.defaultValue },
        indent = Indent.NORMAL,
    ),
    // Named arguments
    IndentRule(
        within = types(VALUE_ARGUMENT),
        notForType = types(EQ, VALUE_ARGUMENT_NAME),
        forElement = { node ->
            val parent = node.psi?.parent
            parent is KtValueArgument && parent.isNamed()
        },
        indent = Indent.NORMAL,
    ),
)

private fun nextSiblingIgnoringWhitespace(element: PsiElement): PsiElement? {
    var next = element.nextSibling
    while (next is org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace) next = next.nextSibling
    return next
}
