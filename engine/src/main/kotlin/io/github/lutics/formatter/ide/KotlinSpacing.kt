package io.github.lutics.formatter.ide

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.TokenType
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.psiUtil.textRangeWithoutComments

// Port of the Kotlin plugin's kotlinSpacingRules.kt and KotlinSpacingBuilder from the formatter the fleet ran
// (intellij-community branch 261, Apache 2.0) with the fleet scheme's settings: the scheme's options over the Kotlin
// official style, every other option at its default. The plugin's builders are asked in order and the first spacing
// found wins, so here they are one list of rules in the plugin's order.
class KotlinSpacing {
    private fun interface Rule {
        fun spacing(parent: Block, left: Block, right: Block): Spacing?
    }

    private val rules = ArrayList<Rule>()

    fun getSpacing(parent: Block, child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        for (rule in rules) {
            val spacing = rule.spacing(parent, child1, child2) ?: continue
            // The plugin's hack reads the spacing's toString, which prints the minimum line feeds as they are at that
            // moment: a dependent spacing reads 0 here, before the processor refreshes it.
            if (child1.node.elementType == KtTokens.EOL_COMMENT && spacing.minLineFeeds == 0) {
                val right = child2.node
                val isBeforeBlock = right.elementType == KtNodeTypes.BLOCK || right.firstChildNode?.elementType == KtNodeTypes.BLOCK
                return createSpacing(0, minLineFeeds = 1, keepBlankLines = if (isBeforeBlock) 0 else KEEP_BLANK_LINES_IN_CODE)
            }
            return spacing
        }
        return null
    }

    private class Position(val parent: TokenSet?, val left: TokenSet?, val right: TokenSet?) {
        fun matches(p: Block, l: Block, r: Block) =
            (parent == null || p.node.elementType in parent) && (left == null || l.node.elementType in left) && (right == null || r.node.elementType in right)
    }

    // The positions a rule applies at: SpacingBuilder's RuleBuilder and KotlinSpacingBuilder's CustomSpacingBuilder.
    private inner class At(private val positions: List<Position>) {
        fun customRule(block: (parent: Block, left: Block, right: Block) -> Spacing?) {
            for (position in positions) rules += Rule { p, l, r -> if (position.matches(p, l, r)) block(p, l, r) else null }
        }

        fun spacing(spacing: Spacing) = customRule { _, _, _ -> spacing }

        fun spacing(minSpaces: Int, maxSpaces: Int, minLineFeeds: Int, keepLineBreaks: Boolean, keepBlankLines: Int) =
            spacing(Spacing(minSpaces, maxSpaces, minLineFeeds, keepLineBreaks, keepBlankLines))

        fun spaces(count: Int) = spacing(count, count, 0, KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)

        fun spacesNoLineBreak(count: Int) = spacing(count, count, 0, false, 0)

        fun blankLines(count: Int) = spacing(0, 0, count + 1, KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_DECLARATIONS)

        fun lineBreakInCode() = spacing(1, 0, 1, KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)

        fun lineBreakIfLineBreakInParent(numSpacesOtherwise: Int) = customRule { p, _, _ ->
            DependentSpacing(numSpacesOtherwise, numSpacesOtherwise, listOf(p.textRange), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)
        }

        fun emptyLinesIfLineBreakInLeft(emptyLines: Int, numberOfLineFeedsOtherwise: Int = 1, numSpacesOtherwise: Int = 0) = customRule { _, left, _ ->
            val lastChild = left.node.psi?.lastChild
            val leftEndsWithComment = lastChild is PsiComment && lastChild.tokenType == KtTokens.EOL_COMMENT
            val textRange = left.node.startOfDeclaration()?.startOffset?.let { TextRange.create(it, left.textRange.endOffset) } ?: left.textRange
            DependentSpacing(
                numSpacesOtherwise,
                numSpacesOtherwise,
                listOf(textRange),
                KEEP_LINE_BREAKS,
                KEEP_BLANK_LINES_IN_DECLARATIONS,
                triggeredLineFeeds = emptyLines + 1,
                otherwiseLineFeeds = if (leftEndsWithComment) maxOf(1, numberOfLineFeedsOtherwise) else numberOfLineFeedsOtherwise,
            )
        }
    }

    private fun set(type: IElementType?) = type?.let { TokenSet.create(it) }

    private fun inPosition(parent: IElementType? = null, left: IElementType? = null, right: IElementType? = null) =
        At(listOf(Position(set(parent), set(left), set(right))))

    private fun after(type: IElementType) = inPosition(left = type)

    private fun afterInside(type: IElementType, parent: IElementType) = inPosition(parent, left = type)

    private fun afterInside(type: IElementType, parents: TokenSet) = At(listOf(Position(parents, set(type), null)))

    private fun before(type: IElementType) = inPosition(right = type)

    private fun beforeInside(type: IElementType, parent: IElementType) = inPosition(parent, right = type)

    private fun beforeInside(type: IElementType, parents: TokenSet) = At(listOf(Position(parents, null, set(type))))

    private fun between(left: IElementType, right: IElementType) = inPosition(left = left, right = right)

    private fun between(left: IElementType, right: TokenSet) = At(listOf(Position(null, set(left), right)))

    private fun between(left: TokenSet, right: TokenSet) = At(listOf(Position(null, left, right)))

    private fun betweenInside(left: IElementType, right: IElementType, parent: IElementType) = inPosition(parent, left, right)

    private fun around(type: IElementType) = around(TokenSet.create(type))

    private fun around(types: TokenSet) = At(listOf(Position(null, null, types), Position(null, types, null)))

    private fun aroundInside(types: TokenSet, parent: IElementType) = At(listOf(Position(set(parent), null, types), Position(set(parent), types, null)))

    private fun aroundInside(type: IElementType, parent: IElementType) = aroundInside(TokenSet.create(type), parent)

    private fun createSpacing(
        minSpaces: Int,
        maxSpaces: Int = minSpaces,
        minLineFeeds: Int = 0,
        keepLineBreaks: Boolean = KEEP_LINE_BREAKS,
        keepBlankLines: Int = KEEP_BLANK_LINES_IN_CODE,
    ) = Spacing(minSpaces, maxSpaces, minLineFeeds, keepLineBreaks, keepBlankLines)

    private fun createSpaceBeforeRBrace(numSpacesOtherwise: Int, textRange: TextRange) =
        DependentSpacing(numSpacesOtherwise, numSpacesOtherwise, listOf(textRange), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_BEFORE_RBRACE)

    private fun commentSpacing(minSpaces: Int) =
        Spacing(minSpaces, Int.MAX_VALUE, -1, KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE, keepFirstColumn = true)

    private fun spacingForLeftBrace(block: ASTNode?, blockType: IElementType = KtNodeTypes.BLOCK): Spacing {
        if (block != null && block.elementType == blockType) {
            val leftBrace = block.findChildByType(KtTokens.LBRACE)
            if (leftBrace != null) {
                val previousLeaf = previousNonWhitespaceLeaf(leftBrace)
                val isAfterEolComment = previousLeaf != null && previousLeaf.elementType == KtTokens.EOL_COMMENT
                // LBRACE_ON_NEXT_LINE is off
                return createSpacing(1, keepLineBreaks = isAfterEolComment, keepBlankLines = 0)
            }
        }
        return createSpacing(1)
    }

    private fun leftBraceRule(blockType: IElementType = KtNodeTypes.BLOCK) = { _: Block, _: Block, right: Block -> spacingForLeftBrace(right.node, blockType) }

    // ELSE_ON_NEW_LINE, WHILE_ON_NEW_LINE, CATCH_ON_NEW_LINE and FINALLY_ON_NEW_LINE are off
    private fun ruleForKeywordOnNewLine(keyword: IElementType, parent: IElementType, afterBlockFilter: (ASTNode, ASTNode) -> Boolean = { _, _ -> true }) {
        inPosition(parent = parent, right = keyword).customRule { _, _, right ->
            val previousLeaf = previousNonWhitespaceLeaf(right.node)
            val leftBlock = if (previousLeaf != null && previousLeaf.elementType == KtTokens.RBRACE && previousLeaf.treeParent?.elementType == KtNodeTypes.BLOCK) {
                previousLeaf.treeParent!!
            } else {
                null
            }
            val removeLineBreaks = leftBlock != null && afterBlockFilter(right.node.treeParent!!, leftBlock)
            createSpacing(1, keepLineBreaks = !removeLineBreaks, keepBlankLines = 0)
        }
    }

    init {
        before(KtNodeTypes.FILE_ANNOTATION_LIST).lineBreakInCode()
        between(KtNodeTypes.IMPORT_DIRECTIVE, KtNodeTypes.IMPORT_DIRECTIVE).lineBreakInCode()
        after(KtNodeTypes.IMPORT_LIST).blankLines(1)

        // Several line comments happened to be generated in one line
        inPosition(left = KtTokens.EOL_COMMENT, right = KtTokens.EOL_COMMENT).customRule { _, _, right ->
            val nodeBeforeRight = right.node.treePrev
            if (nodeBeforeRight is PsiWhiteSpace && !nodeBeforeRight.textContains('\n')) createSpacing(0, minLineFeeds = 1) else null
        }
        inPosition(right = KtTokens.BLOCK_COMMENT).spacing(createSpacing(0, Int.MAX_VALUE))
        inPosition(right = KtTokens.EOL_COMMENT).spacing(commentSpacing(1))
        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, right = KtNodeTypes.BLOCK).customRule { _, _, right ->
            when (right.node.firstChildNode?.elementType) {
                KtTokens.BLOCK_COMMENT -> commentSpacing(0)
                KtTokens.EOL_COMMENT -> commentSpacing(1)
                else -> null
            }
        }

        after(KtNodeTypes.FILE_ANNOTATION_LIST).blankLines(1)
        after(KtNodeTypes.PACKAGE_DIRECTIVE).blankLines(1)

        At(listOf(Position(null, DECLARATIONS, DECLARATIONS))).customRule { _, _, right ->
            val node = right.node
            val elementStart = node.startOfDeclaration() ?: return@customRule null
            if (node.text.subSequence(0, elementStart.startOffset - node.startOffset).trimStart().contains('\n')) {
                createSpacing(0, minLineFeeds = 1 + BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE)
            } else {
                null
            }
        }

        inPosition(left = KtNodeTypes.CLASS, right = KtNodeTypes.CLASS).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.CLASS, right = KtNodeTypes.OBJECT_DECLARATION).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.OBJECT_DECLARATION, right = KtNodeTypes.OBJECT_DECLARATION).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.OBJECT_DECLARATION, right = KtNodeTypes.CLASS).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.FUN, right = KtNodeTypes.FUN).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.PROPERTY, right = KtNodeTypes.FUN).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.FUN, right = KtNodeTypes.PROPERTY).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.SECONDARY_CONSTRUCTOR, right = KtNodeTypes.SECONDARY_CONSTRUCTOR).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.TYPEALIAS, right = KtNodeTypes.TYPEALIAS).emptyLinesIfLineBreakInLeft(1)

        // Case left for alternative constructors
        inPosition(left = KtNodeTypes.FUN, right = KtNodeTypes.CLASS).emptyLinesIfLineBreakInLeft(1)
        inPosition(left = KtNodeTypes.ENUM_ENTRY, right = KtNodeTypes.ENUM_ENTRY).emptyLinesIfLineBreakInLeft(
            emptyLines = 0,
            numberOfLineFeedsOtherwise = 0,
            numSpacesOtherwise = 1,
        )

        inPosition(parent = KtNodeTypes.CLASS_BODY, left = KtTokens.SEMICOLON).customRule { parent, _, right ->
            val klass = parent.node.treeParent.psi as? KtClass ?: return@customRule null
            if (klass.isEnum() && right.node.elementType in DECLARATIONS) {
                createSpacing(0, minLineFeeds = 2, keepBlankLines = KEEP_BLANK_LINES_IN_DECLARATIONS)
            } else {
                null
            }
        }

        // BLANK_LINES_AFTER_CLASS_HEADER is 0, so only an empty body has a rule here
        inPosition(parent = KtNodeTypes.CLASS_BODY, left = KtTokens.LBRACE).customRule { _, _, right ->
            if (right.node.elementType == KtTokens.RBRACE) createSpacing(0) else null
        }

        inPosition(parent = KtNodeTypes.VALUE_PARAMETER_LIST, right = KtNodeTypes.VALUE_PARAMETER).customRule { _, _, right ->
            if (right.node.firstChildNode?.elementType == KtTokens.DOC_COMMENT) {
                createSpacing(0, minLineFeeds = 1, keepLineBreaks = true, keepBlankLines = KEEP_BLANK_LINES_IN_DECLARATIONS)
            } else {
                null
            }
        }

        inPosition(parent = KtNodeTypes.PROPERTY, right = KtNodeTypes.PROPERTY_ACCESSOR).customRule { parent, _, _ ->
            val startNode = parent.node.let { it.startOfDeclaration() ?: it }
            DependentSpacing(1, 1, listOf(TextRange(startNode.startOffset, parent.textRange.endOffset)), false, 0)
        }

        // ALLOW_TRAILING_COMMA is on: an argument list that need not wrap has no rule here
        inPosition(parent = KtNodeTypes.VALUE_ARGUMENT_LIST, left = KtTokens.LPAR).customRule { parent, _, _ ->
            if (needWrapArgumentList(parent.node.psi)) {
                DependentSpacing(0, 0, excludeLambdasAndObjects(parent), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)
            } else {
                null
            }
        }

        inPosition(parent = KtNodeTypes.VALUE_ARGUMENT_LIST, right = KtTokens.RPAR).customRule { parent, _, _ ->
            DependentSpacing(0, 0, excludeLambdasAndObjects(parent), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)
        }

        inPosition(left = KtNodeTypes.CONDITION, right = KtTokens.RPAR).customRule { _, left, _ ->
            DependentSpacing(0, 0, excludeLambdasAndObjects(left), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)
        }

        inPosition(left = KtNodeTypes.VALUE_PARAMETER, right = KtTokens.COMMA).customRule { _, left, _ ->
            if (left.node.lastChildNode?.elementType === KtTokens.EOL_COMMENT) createSpacing(0, minLineFeeds = 1) else null
        }

        inPosition(parent = KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY, right = KtTokens.LONG_TEMPLATE_ENTRY_END).lineBreakIfLineBreakInParent(0)
        inPosition(parent = KtNodeTypes.LONG_STRING_TEMPLATE_ENTRY, left = KtTokens.LONG_TEMPLATE_ENTRY_START).lineBreakIfLineBreakInParent(0)

        // These two are written inside the large rule set below, but the plugin registers them before it.
        // For the range operator, we have to require a space after it if the next node starts with a `.`.
        // Otherwise, the `..` operator becomes `...` and the code is changed semantically.
        inPosition(left = KtTokens.RANGE).customRule { _, _, right ->
            createSpacing(if (right.node.text.startsWith(".")) 1 else 0)
        }
        inPosition(right = KtNodeTypes.PRIMARY_CONSTRUCTOR).customRule { _, _, r ->
            val spacesCount = if (r.node.firstLeafType() != KtTokens.LPAR) 1 else 0
            createSpacing(spacesCount, keepLineBreaks = true, keepBlankLines = 0)
        }

        // ============ Line breaks ==============
        before(KtTokens.DOC_COMMENT).lineBreakInCode()
        between(KtNodeTypes.PROPERTY, KtNodeTypes.PROPERTY).lineBreakInCode()

        // CLASS - CLASS, CLASS - OBJECT_DECLARATION are exception
        between(KtNodeTypes.CLASS, DECLARATIONS).blankLines(1)

        // FUN - FUN, FUN - PROPERTY, FUN - CLASS are exceptions
        between(KtNodeTypes.FUN, DECLARATIONS).blankLines(1)

        // PROPERTY - PROPERTY, PROPERTY - FUN are exceptions
        between(KtNodeTypes.PROPERTY, DECLARATIONS).blankLines(1)

        // OBJECT_DECLARATION - OBJECT_DECLARATION, CLASS - OBJECT_DECLARATION are exception
        between(KtNodeTypes.OBJECT_DECLARATION, DECLARATIONS).blankLines(1)
        between(KtNodeTypes.SECONDARY_CONSTRUCTOR, DECLARATIONS).blankLines(1)
        between(KtNodeTypes.CLASS_INITIALIZER, DECLARATIONS).blankLines(1)

        // TYPEALIAS - TYPEALIAS is an exception
        between(KtNodeTypes.TYPEALIAS, DECLARATIONS).blankLines(1)
        before(KtNodeTypes.TYPEALIAS).lineBreakInCode()

        // ENUM_ENTRY - ENUM_ENTRY is exception
        between(KtNodeTypes.ENUM_ENTRY, DECLARATIONS).blankLines(1)

        between(KtNodeTypes.ENUM_ENTRY, KtTokens.SEMICOLON).spaces(0)

        between(KtTokens.COMMA, KtTokens.SEMICOLON).lineBreakInCode()

        val bodies = TokenSet.create(KtNodeTypes.BODY, KtNodeTypes.CLASS_BODY)
        beforeInside(KtNodeTypes.FUN, bodies).lineBreakInCode()
        beforeInside(KtNodeTypes.SECONDARY_CONSTRUCTOR, bodies).lineBreakInCode()
        beforeInside(KtNodeTypes.CLASS, bodies).lineBreakInCode()
        beforeInside(KtNodeTypes.OBJECT_DECLARATION, bodies).lineBreakInCode()
        beforeInside(KtNodeTypes.PROPERTY, KtNodeTypes.WHEN).spaces(0)
        beforeInside(KtNodeTypes.PROPERTY, KtNodeTypes.LABELED_EXPRESSION).spacesNoLineBreak(1)
        before(KtNodeTypes.PROPERTY).lineBreakInCode()

        after(KtTokens.DOC_COMMENT).lineBreakInCode()

        // =============== Spacing ================
        aroundInside(KtTokens.AND, KtNodeTypes.INTERSECTION_TYPE).spacesNoLineBreak(1)

        between(KtTokens.EOL_COMMENT, KtTokens.COMMA).lineBreakInCode()
        before(KtTokens.COMMA).spacesNoLineBreak(0)
        after(KtTokens.COMMA).spaces(1)

        beforeInside(KtTokens.EQ, KtNodeTypes.PROPERTY).spacesNoLineBreak(1)
        beforeInside(KtTokens.EQ, KtNodeTypes.FUN).spacesNoLineBreak(1)

        around(TokenSet.create(KtTokens.EQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.PERCEQ)).spaces(1)
        around(TokenSet.create(KtTokens.ANDAND, KtTokens.OROR)).spaces(1)
        around(TokenSet.create(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)).spaces(1)
        aroundInside(TokenSet.create(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ), KtNodeTypes.BINARY_EXPRESSION).spaces(1)
        aroundInside(TokenSet.create(KtTokens.PLUS, KtTokens.MINUS), KtNodeTypes.BINARY_EXPRESSION).spaces(1)
        aroundInside(TokenSet.create(KtTokens.MUL, KtTokens.DIV, KtTokens.PERC), KtNodeTypes.BINARY_EXPRESSION).spaces(1)
        around(TokenSet.create(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS, KtTokens.EXCLEXCL, KtTokens.MINUS, KtTokens.PLUS, KtTokens.EXCL)).spaces(0)

        before(KtTokens.ELVIS).spaces(1)
        after(KtTokens.ELVIS).spacesNoLineBreak(1)
        before(KtTokens.RANGE).spaces(0)
        around(KtTokens.RANGE_UNTIL).spaces(0)

        after(KtNodeTypes.MODIFIER_LIST).spaces(1)

        beforeInside(KtTokens.IDENTIFIER, KtNodeTypes.CLASS).spaces(1)
        beforeInside(KtTokens.IDENTIFIER, KtNodeTypes.OBJECT_DECLARATION).spaces(1)

        after(KtTokens.TYPE_ALIAS_KEYWORD).spaces(1)

        after(KtTokens.VAL_KEYWORD).spaces(1)
        after(KtTokens.VAR_KEYWORD).spaces(1)
        betweenInside(KtNodeTypes.TYPE_PARAMETER_LIST, KtTokens.IDENTIFIER, KtNodeTypes.PROPERTY).spaces(1)
        betweenInside(KtNodeTypes.TYPE_REFERENCE, KtTokens.DOT, KtNodeTypes.PROPERTY).spacesNoLineBreak(0)
        betweenInside(KtTokens.DOT, KtTokens.IDENTIFIER, KtNodeTypes.PROPERTY).spacesNoLineBreak(0)

        betweenInside(KtTokens.RETURN_KEYWORD, KtNodeTypes.LABEL_QUALIFIER, KtNodeTypes.RETURN).spaces(0)
        afterInside(KtTokens.RETURN_KEYWORD, KtNodeTypes.RETURN).spaces(1)
        afterInside(KtNodeTypes.LABEL_QUALIFIER, KtNodeTypes.RETURN).spaces(1)
        betweenInside(KtNodeTypes.LABEL_QUALIFIER, KtTokens.EOL_COMMENT, KtNodeTypes.LABELED_EXPRESSION).spacing(0, Int.MAX_VALUE, 0, true, KEEP_BLANK_LINES_IN_CODE)
        betweenInside(KtNodeTypes.LABEL_QUALIFIER, KtTokens.BLOCK_COMMENT, KtNodeTypes.LABELED_EXPRESSION).spacing(0, Int.MAX_VALUE, 0, true, KEEP_BLANK_LINES_IN_CODE)
        betweenInside(KtNodeTypes.LABEL_QUALIFIER, KtNodeTypes.LAMBDA_EXPRESSION, KtNodeTypes.LABELED_EXPRESSION).spaces(0)
        afterInside(KtNodeTypes.LABEL_QUALIFIER, KtNodeTypes.LABELED_EXPRESSION).spaces(1)

        betweenInside(KtTokens.FUN_KEYWORD, KtNodeTypes.VALUE_PARAMETER_LIST, KtNodeTypes.FUN).spacesNoLineBreak(0)
        after(KtTokens.FUN_KEYWORD).spaces(1)
        betweenInside(KtNodeTypes.TYPE_PARAMETER_LIST, KtNodeTypes.TYPE_REFERENCE, KtNodeTypes.FUN).spaces(1)
        betweenInside(KtNodeTypes.TYPE_PARAMETER_LIST, KtTokens.IDENTIFIER, KtNodeTypes.FUN).spaces(1)
        betweenInside(KtNodeTypes.TYPE_REFERENCE, KtTokens.DOT, KtNodeTypes.FUN).spacesNoLineBreak(0)
        betweenInside(KtTokens.DOT, KtTokens.IDENTIFIER, KtNodeTypes.FUN).spacesNoLineBreak(0)
        afterInside(KtTokens.IDENTIFIER, KtNodeTypes.FUN).spacesNoLineBreak(0)
        aroundInside(KtTokens.DOT, KtNodeTypes.USER_TYPE).spaces(0)

        around(KtTokens.AS_KEYWORD).spaces(1)
        around(KtTokens.AS_SAFE).spaces(1)
        around(KtTokens.IS_KEYWORD).spaces(1)
        around(KtTokens.NOT_IS).spaces(1)
        around(KtTokens.IN_KEYWORD).spaces(1)
        around(KtTokens.NOT_IN).spaces(1)
        aroundInside(KtTokens.IDENTIFIER, KtNodeTypes.BINARY_EXPRESSION).spaces(1)

        after(KtTokens.THROW_KEYWORD).spacesNoLineBreak(1)

        // before LPAR in constructor(): this() {}
        after(KtNodeTypes.CONSTRUCTOR_DELEGATION_REFERENCE).spacesNoLineBreak(0)

        afterInside(KtTokens.CONSTRUCTOR_KEYWORD, KtNodeTypes.PRIMARY_CONSTRUCTOR).spaces(0)
        betweenInside(KtTokens.IDENTIFIER, KtNodeTypes.TYPE_PARAMETER_LIST, KtNodeTypes.CLASS).spaces(0)

        beforeInside(KtTokens.DOT, KtNodeTypes.DOT_QUALIFIED_EXPRESSION).spaces(0)
        afterInside(KtTokens.DOT, KtNodeTypes.DOT_QUALIFIED_EXPRESSION).spacesNoLineBreak(0)
        beforeInside(KtTokens.SAFE_ACCESS, KtNodeTypes.SAFE_ACCESS_EXPRESSION).spaces(0)
        afterInside(KtTokens.SAFE_ACCESS, KtNodeTypes.SAFE_ACCESS_EXPRESSION).spacesNoLineBreak(0)

        between(MODIFIERS_LIST_ENTRIES, MODIFIERS_LIST_ENTRIES).spaces(1)

        after(KtTokens.LBRACKET).spaces(0)
        before(KtTokens.RBRACKET).spaces(0)

        afterInside(KtTokens.LPAR, KtNodeTypes.VALUE_PARAMETER_LIST).lineBreakIfLineBreakInParent(0)
        beforeInside(KtTokens.RPAR, KtNodeTypes.VALUE_PARAMETER_LIST).lineBreakIfLineBreakInParent(0)
        afterInside(KtTokens.LT, KtNodeTypes.TYPE_PARAMETER_LIST).spaces(0)
        beforeInside(KtTokens.GT, KtNodeTypes.TYPE_PARAMETER_LIST).spaces(0)
        afterInside(KtTokens.LT, KtNodeTypes.TYPE_ARGUMENT_LIST).spaces(0)
        beforeInside(KtTokens.GT, KtNodeTypes.TYPE_ARGUMENT_LIST).spaces(0)
        before(KtNodeTypes.TYPE_ARGUMENT_LIST).spaces(0)

        after(KtTokens.LPAR).spaces(0)
        before(KtTokens.RPAR).spaces(0)

        betweenInside(KtTokens.FOR_KEYWORD, KtTokens.LPAR, KtNodeTypes.FOR).spaces(1)
        betweenInside(KtTokens.IF_KEYWORD, KtTokens.LPAR, KtNodeTypes.IF).spaces(1)
        betweenInside(KtTokens.WHILE_KEYWORD, KtTokens.LPAR, KtNodeTypes.WHILE).spaces(1)
        betweenInside(KtTokens.WHILE_KEYWORD, KtTokens.LPAR, KtNodeTypes.DO_WHILE).spaces(1)
        betweenInside(KtTokens.WHEN_KEYWORD, KtTokens.LPAR, KtNodeTypes.WHEN).spaces(1)
        betweenInside(KtTokens.CATCH_KEYWORD, KtNodeTypes.VALUE_PARAMETER_LIST, KtNodeTypes.CATCH).spaces(1)

        betweenInside(KtTokens.LPAR, KtNodeTypes.VALUE_PARAMETER, KtNodeTypes.FOR).spaces(0)
        betweenInside(KtTokens.LPAR, KtNodeTypes.DESTRUCTURING_DECLARATION, KtNodeTypes.FOR).spaces(0)
        betweenInside(KtNodeTypes.LOOP_RANGE, KtTokens.RPAR, KtNodeTypes.FOR).spaces(0)

        afterInside(KtNodeTypes.ANNOTATION_ENTRY, KtNodeTypes.ANNOTATED_EXPRESSION).spaces(1)
        afterInside(KtTokens.COLON, KtNodeTypes.ANNOTATION_ENTRY).spaces(0)
        beforeInside(KtTokens.COLON, KtNodeTypes.ANNOTATION_ENTRY).spaces(0)

        before(KtTokens.SEMICOLON).spaces(0)

        beforeInside(KtNodeTypes.INITIALIZER_LIST, KtNodeTypes.ENUM_ENTRY).spaces(0)

        beforeInside(KtTokens.QUEST, KtNodeTypes.NULLABLE_TYPE).spaces(0)

        beforeInside(KtTokens.COLON, TYPE_COLON_ELEMENTS).spaces(0)
        afterInside(KtTokens.COLON, TYPE_COLON_ELEMENTS).spaces(1)

        afterInside(KtTokens.COLON, EXTEND_COLON_ELEMENTS).spaces(1)

        beforeInside(KtTokens.ARROW, KtNodeTypes.FUNCTION_LITERAL).spaces(1)

        aroundInside(KtTokens.ARROW, KtNodeTypes.FUNCTION_TYPE).spaces(1)

        before(KtNodeTypes.VALUE_ARGUMENT_LIST).spaces(0)
        between(KtNodeTypes.VALUE_ARGUMENT_LIST, KtNodeTypes.LAMBDA_ARGUMENT).spaces(1)
        betweenInside(KtNodeTypes.REFERENCE_EXPRESSION, KtNodeTypes.LAMBDA_ARGUMENT, KtNodeTypes.CALL_EXPRESSION).spaces(1)
        betweenInside(KtNodeTypes.TYPE_ARGUMENT_LIST, KtNodeTypes.LAMBDA_ARGUMENT, KtNodeTypes.CALL_EXPRESSION).spaces(1)
        betweenInside(KtNodeTypes.STRING_TEMPLATE, KtNodeTypes.LAMBDA_ARGUMENT, KtNodeTypes.CALL_EXPRESSION).spaces(1)

        around(KtTokens.COLONCOLON).spaces(0)

        around(KtTokens.BY_KEYWORD).spaces(1)
        betweenInside(KtTokens.IDENTIFIER, KtNodeTypes.PROPERTY_DELEGATE, KtNodeTypes.PROPERTY).spaces(1)
        betweenInside(KtNodeTypes.TYPE_REFERENCE, KtNodeTypes.PROPERTY_DELEGATE, KtNodeTypes.PROPERTY).spaces(1)

        before(KtNodeTypes.INDICES).spaces(0)
        before(KtTokens.WHERE_KEYWORD).spaces(1)

        afterInside(KtTokens.GET_KEYWORD, KtNodeTypes.PROPERTY_ACCESSOR).spaces(0)
        afterInside(KtTokens.SET_KEYWORD, KtNodeTypes.PROPERTY_ACCESSOR).spaces(0)

        afterInside(KtTokens.IF_KEYWORD, KtNodeTypes.WHEN_ENTRY_GUARD).spaces(1)
        before(KtNodeTypes.WHEN_ENTRY_GUARD).spaces(1)

        ruleForKeywordOnNewLine(KtTokens.ELSE_KEYWORD, KtNodeTypes.IF) { keywordParent, block ->
            block.treeParent?.elementType == KtNodeTypes.THEN && block.treeParent?.treeParent == keywordParent
        }
        ruleForKeywordOnNewLine(KtTokens.WHILE_KEYWORD, KtNodeTypes.DO_WHILE) { keywordParent, block ->
            block.treeParent?.elementType == KtNodeTypes.BODY && block.treeParent?.treeParent == keywordParent
        }
        ruleForKeywordOnNewLine(KtNodeTypes.CATCH, KtNodeTypes.TRY)
        ruleForKeywordOnNewLine(KtNodeTypes.FINALLY, KtNodeTypes.TRY)

        // Add space after a semicolon if there is another child at the same line
        inPosition(left = KtTokens.SEMICOLON).customRule { _, left, _ ->
            val nodeAfterLeft = left.node.treeNext
            if (nodeAfterLeft !is PsiWhiteSpace || !nodeAfterLeft.textContains('\n')) createSpacing(1) else null
        }

        val leftBraceRuleIfBlockIsWrapped = { _: Block, _: Block, right: Block -> spacingForLeftBrace(right.node.firstChildNode) }
        inPosition(parent = KtNodeTypes.IF, right = KtNodeTypes.THEN).customRule(leftBraceRuleIfBlockIsWrapped)
        inPosition(parent = KtNodeTypes.IF, right = KtNodeTypes.ELSE).customRule(leftBraceRuleIfBlockIsWrapped)

        inPosition(parent = KtNodeTypes.FOR, right = KtNodeTypes.BODY).customRule(leftBraceRuleIfBlockIsWrapped)
        inPosition(parent = KtNodeTypes.WHILE, right = KtNodeTypes.BODY).customRule(leftBraceRuleIfBlockIsWrapped)
        inPosition(parent = KtNodeTypes.DO_WHILE, right = KtNodeTypes.BODY).customRule(leftBraceRuleIfBlockIsWrapped)

        inPosition(parent = KtNodeTypes.TRY, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.CATCH, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.FINALLY, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())

        inPosition(parent = KtNodeTypes.FUN, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.SECONDARY_CONSTRUCTOR, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.CLASS_INITIALIZER, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.PROPERTY_ACCESSOR, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())

        // No space before the spread operator.
        inPosition(parent = KtNodeTypes.VALUE_ARGUMENT, left = KtTokens.MUL).spacing(createSpacing(0))

        inPosition(right = KtNodeTypes.CLASS_BODY).customRule(leftBraceRule(blockType = KtNodeTypes.CLASS_BODY))
        // BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES is 0 and LINE_BREAK_AFTER_MULTILINE_WHEN_ENTRY is off
        inPosition(left = KtNodeTypes.WHEN_ENTRY, right = KtNodeTypes.WHEN_ENTRY).customRule { _, _, _ -> createSpacing(0, minLineFeeds = 1) }

        inPosition(parent = KtNodeTypes.WHEN_ENTRY, right = KtNodeTypes.BLOCK).customRule(leftBraceRule())
        inPosition(parent = KtNodeTypes.WHEN, right = KtTokens.LBRACE).customRule { parent, _, _ ->
            spacingForLeftBrace(block = parent.node, blockType = KtNodeTypes.WHEN)
        }

        inPosition(left = KtTokens.LBRACE, right = KtNodeTypes.WHEN_ENTRY).customRule { _, _, _ -> createSpacing(0, minLineFeeds = 1) }

        // INSERT_WHITESPACES_IN_SIMPLE_ONE_LINE_METHOD is on: one space inside a one-line function or lambda
        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, left = KtTokens.LBRACE, right = KtNodeTypes.BLOCK).lineBreakIfLineBreakInParent(1)

        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, left = KtTokens.ARROW, right = KtNodeTypes.BLOCK).lineBreakIfLineBreakInParent(1)

        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, left = KtTokens.LBRACE, right = KtTokens.RBRACE).spacing(createSpacing(0, 1))

        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, right = KtTokens.RBRACE).lineBreakIfLineBreakInParent(1)

        inPosition(parent = KtNodeTypes.FUNCTION_LITERAL, left = KtTokens.LBRACE).customRule { _, _, right ->
            if (right.node.elementType == KtNodeTypes.VALUE_PARAMETER_LIST) createSpacing(1, keepLineBreaks = false) else createSpacing(1)
        }

        inPosition(parent = KtNodeTypes.CLASS_BODY, right = KtTokens.RBRACE).customRule { parent, _, _ ->
            createSpaceBeforeRBrace(1, parent.textRange)
        }

        inPosition(parent = KtNodeTypes.BLOCK, right = KtTokens.RBRACE).customRule { block, left, _ ->
            val psiElement = block.node.treeParent.psi
            val empty = left.node.elementType == KtTokens.LBRACE
            when (psiElement) {
                is KtDeclarationWithBody -> if (psiElement.name != null && !empty) return@customRule null
                is KtWhenEntry, is KtClassInitializer -> if (!empty) return@customRule null
                else -> return@customRule null
            }
            createSpaceBeforeRBrace(if (empty) 0 else 1, psiElement.textRangeWithoutComments)
        }

        inPosition(parent = KtNodeTypes.BLOCK, left = KtTokens.LBRACE).customRule { parent, _, _ ->
            val funNode = parent.node.treeParent.psi as? KtFunction ?: return@customRule null
            if (funNode.name != null) return@customRule null
            // Empty block is covered in above rule
            DependentSpacing(1, 1, listOf(funNode.textRangeWithoutComments), KEEP_LINE_BREAKS, KEEP_BLANK_LINES_IN_CODE)
        }

        At(listOf(Position(EXTEND_COLON_ELEMENTS, set(KtNodeTypes.PRIMARY_CONSTRUCTOR), set(KtTokens.COLON)))).customRule { _, _, _ ->
            // METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE is on; SPACE_BEFORE_EXTEND_COLON is on
            createSpacing(1, keepLineBreaks = false)
        }

        inPosition(parent = KtNodeTypes.CLASS_BODY, left = KtTokens.LBRACE, right = KtNodeTypes.ENUM_ENTRY).lineBreakIfLineBreakInParent(1)

        afterInside(KtTokens.LBRACE, KtNodeTypes.BLOCK).lineBreakInCode()
        beforeInside(KtTokens.RBRACE, KtNodeTypes.BLOCK).spacing(1, 0, 1, KEEP_LINE_BREAKS, KEEP_BLANK_LINES_BEFORE_RBRACE)
        between(KtTokens.LBRACE, KtNodeTypes.ENUM_ENTRY).spacing(1, 0, 0, true, KEEP_BLANK_LINES_IN_CODE)
        beforeInside(KtTokens.RBRACE, KtNodeTypes.WHEN).lineBreakInCode()
        between(KtTokens.RPAR, KtNodeTypes.BODY).spaces(1)

        // if when entry has block, spacing after arrow should be set by lbrace rule
        aroundInside(KtTokens.ARROW, KtNodeTypes.WHEN_ENTRY).spaces(1)

        beforeInside(KtTokens.COLON, EXTEND_COLON_ELEMENTS).spaces(1)

        after(KtTokens.EOL_COMMENT).lineBreakInCode()
    }

    private fun excludeLambdasAndObjects(parent: Block): List<TextRange> {
        val rangesToExclude = mutableListOf<TextRange>()
        parent.node.psi.accept(object : KtTreeVisitorVoid() {
            override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                super.visitLambdaExpression(lambdaExpression)
                rangesToExclude.add(lambdaExpression.textRange)
            }

            override fun visitObjectLiteralExpression(expression: KtObjectLiteralExpression) {
                super.visitObjectLiteralExpression(expression)
                rangesToExclude.add(expression.textRange)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                if (function.name == null) rangesToExclude.add(function.textRange)
            }
        })
        return excludeRanges(parent.textRange, rangesToExclude)
    }

    private companion object {
        const val KEEP_LINE_BREAKS = true
        const val KEEP_BLANK_LINES_IN_CODE = 2
        const val KEEP_BLANK_LINES_IN_DECLARATIONS = 2
        const val KEEP_BLANK_LINES_BEFORE_RBRACE = 2
        const val BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE = 1

        val MODIFIERS_LIST_ENTRIES: TokenSet = TokenSet.orSet(
            TokenSet.create(KtNodeTypes.ANNOTATION_ENTRY, KtNodeTypes.ANNOTATION, KtNodeTypes.CONTEXT_PARAMETER_LIST),
            KtTokens.MODIFIER_KEYWORDS,
        )
        val EXTEND_COLON_ELEMENTS: TokenSet = TokenSet.create(
            KtNodeTypes.TYPE_CONSTRAINT,
            KtNodeTypes.CLASS,
            KtNodeTypes.OBJECT_DECLARATION,
            KtNodeTypes.TYPE_PARAMETER,
            KtNodeTypes.ENUM_ENTRY,
            KtNodeTypes.SECONDARY_CONSTRUCTOR,
        )
        val DECLARATIONS: TokenSet = TokenSet.create(
            KtNodeTypes.PROPERTY,
            KtNodeTypes.FUN,
            KtNodeTypes.CLASS,
            KtNodeTypes.OBJECT_DECLARATION,
            KtNodeTypes.ENUM_ENTRY,
            KtNodeTypes.SECONDARY_CONSTRUCTOR,
            KtNodeTypes.CLASS_INITIALIZER,
        )
        val TYPE_COLON_ELEMENTS: TokenSet = TokenSet.create(
            KtNodeTypes.PROPERTY,
            KtNodeTypes.FUN,
            KtNodeTypes.VALUE_PARAMETER,
            KtNodeTypes.DESTRUCTURING_DECLARATION_ENTRY,
            KtNodeTypes.FUNCTION_LITERAL,
        )

        // KotlinSpacingBuilder's startOfDeclaration: the first child not the modifier list, white space or a comment.
        fun ASTNode.startOfDeclaration(): ASTNode? {
            var child = firstChildNode
            while (child != null) {
                val type = child.elementType
                if (type != KtNodeTypes.MODIFIER_LIST && type !in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET) return child
                child = child.treeNext
            }
            return null
        }

        // FormatterUtil.getPreviousNonWhitespaceLeaf: stops below the file node
        fun previousNonWhitespaceLeaf(node: ASTNode): ASTNode? {
            val treePrev = node.treePrev
            if (treePrev != null) {
                var candidate: ASTNode = treePrev
                while (candidate.lastChildNode != null) candidate = candidate.lastChildNode
                return if (!isWhitespaceOrEmpty(candidate)) candidate else previousNonWhitespaceLeaf(candidate)
            }
            val treeParent = node.treeParent
            return if (treeParent == null || treeParent.treeParent == null) null else previousNonWhitespaceLeaf(treeParent)
        }

        fun isWhitespaceOrEmpty(node: ASTNode): Boolean {
            val type = node.elementType
            return type == TokenType.WHITE_SPACE || (type != TokenType.ERROR_ELEMENT && node.textLength == 0)
        }

        fun ASTNode.firstLeafType(): IElementType? {
            var n: ASTNode? = this
            while (n?.firstChildNode != null) n = n.firstChildNode
            return n?.elementType
        }

        // TextRangeUtil.excludeRanges
        fun excludeRanges(original: TextRange, excluded: List<TextRange>): List<TextRange> {
            if (excluded.isEmpty()) return listOf(original)
            var enabledRangeStart = original.startOffset
            val enabled = ArrayList<TextRange>()
            for (range in excluded.sortedWith(compareBy<TextRange> { it.startOffset }.thenBy { it.endOffset })) {
                if (range.endOffset < enabledRangeStart) continue
                if (range.startOffset > original.endOffset) break
                if (range.startOffset > enabledRangeStart) enabled.add(TextRange(enabledRangeStart, range.startOffset))
                enabledRangeStart = range.endOffset
            }
            if (enabledRangeStart < original.endOffset) enabled.add(TextRange(enabledRangeStart, original.endOffset))
            return enabled
        }
    }
}
