package io.github.lutics.formatter

import io.github.lutics.formatter.rules.control.ConditionCallSpansLines
import io.github.lutics.formatter.rules.control.MixedConditionOperators
import io.github.lutics.formatter.rules.control.MultilineIfElse
import io.github.lutics.formatter.rules.control.MultilineLoop
import io.github.lutics.formatter.rules.control.NoEmptyClassBody
import io.github.lutics.formatter.rules.control.NoSemi
import io.github.lutics.formatter.rules.control.ParenSpacing
import io.github.lutics.formatter.rules.control.RoomQueryKeywordCase
import io.github.lutics.formatter.rules.control.SqlLowercaseKeywords
import io.github.lutics.formatter.rules.control.StringTemplate
import io.github.lutics.formatter.rules.function.FunctionExpressionBody
import io.github.lutics.formatter.rules.function.FunctionLiteral
import io.github.lutics.formatter.rules.function.FunctionSignature
import io.github.lutics.formatter.rules.indent.Indent
import io.github.lutics.formatter.rules.layout.BlankLineAfterSdkInitGuard
import io.github.lutics.formatter.rules.layout.BlankLineBetweenWhenConditions
import io.github.lutics.formatter.rules.layout.ComposableBlankLines
import io.github.lutics.formatter.rules.layout.ComposableOrder
import io.github.lutics.formatter.rules.layout.FinalNewline
import io.github.lutics.formatter.rules.layout.ImportOrdering
import io.github.lutics.formatter.rules.layout.NoBlankLineBeforeRbrace
import io.github.lutics.formatter.rules.layout.NoBlankLinesInChainedMethodCalls
import io.github.lutics.formatter.rules.layout.NoConsecutiveBlankLines
import io.github.lutics.formatter.rules.layout.PropertyAccessor
import io.github.lutics.formatter.rules.layout.SpacingBetweenDeclarationsWithAnnotations
import io.github.lutics.formatter.rules.layout.UiStateOrder
import io.github.lutics.formatter.rules.wrapcomma.ArgumentListWrapping
import io.github.lutics.formatter.rules.wrapcomma.StatementWrapping
import io.github.lutics.formatter.rules.wrapcomma.TrailingCommaOnCallSite
import io.github.lutics.formatter.rules.wrapcomma.TrailingCommaOnDeclarationSite
import io.github.lutics.formatter.rules.wrapcomma.Wrapping

// The rules in the order they run: by id, a rule that depends on another after it, and the rules that settle layout
// (signature, trailing commas, indent) last.
val RULES: List<Rule> = listOf(
    BlankLineAfterSdkInitGuard,
    BlankLineBetweenWhenConditions,
    ComposableBlankLines,
    ComposableOrder,
    ConditionCallSpansLines,
    FinalNewline,
    FunctionExpressionBody,
    FunctionLiteral,
    ImportOrdering,
    MixedConditionOperators,
    MultilineIfElse,
    MultilineLoop,
    NoBlankLineBeforeRbrace,
    NoBlankLinesInChainedMethodCalls,
    NoConsecutiveBlankLines,
    NoEmptyClassBody,
    NoSemi,
    ParenSpacing,
    PropertyAccessor,
    RoomQueryKeywordCase,
    SpacingBetweenDeclarationsWithAnnotations,
    SqlLowercaseKeywords,
    StatementWrapping,
    StringTemplate,
    UiStateOrder,
    Wrapping,
    ArgumentListWrapping,
    FunctionSignature,
    TrailingCommaOnCallSite,
    TrailingCommaOnDeclarationSite,
    Indent,
)
