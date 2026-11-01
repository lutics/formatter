package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression

// A Unit-returning @Composable body opens with a blank line, keeps its reads (CompositionLocals, collected state,
// injection) together, and puts one blank line around every other statement. Only blank lines change: the order is
// reported by composable-order, because moving a statement can change what the composition does.
object ComposableBlankLines : Rule {
    override val id = "composable-blank-lines"

    override fun edits(file: KtFile): List<Edit> =
        composableBodies(file).flatMap { body ->
            val statements = body.statements
            statements.indices.flatMap { i ->
                val statement = statements[i]
                val anchor = if (i == 0) body.lBrace else statements[i - 1]
                val join = i > 0 && statements[i - 1].kind() == Kind.READ && statement.kind() == Kind.READ && !statement.isAnnotatedDeclaration()
                gapEdits(anchor, statement, join)
            }
        }.distinctBy { it.start }

    // The newline whitespace between the anchor and the statement: a comment directly above the statement is part of it,
    // so the blank line goes above the comment. A same-line comment or a `;` keeps its line.
    private fun gapEdits(anchor: PsiElement?, statement: KtExpression, join: Boolean): List<Edit> {
        anchor ?: return emptyList()
        val gap = generateSequence(anchor.nextSibling) { it.nextSibling }
            .takeWhile { it != statement }
            .filter { it is PsiWhiteSpace && it.textContains('\n') }
            .toList()
        // A join pulls a detached comment into the read group too, so no blank line is left anywhere between the reads
        val targets = if (join) gap else gap.take(1)
        val newlines = if (join) "\n" else "\n\n"
        return targets.mapNotNull { ws ->
            val text = ws.text
            if (text.count { it == '\n' } == newlines.length) return@mapNotNull null
            // No trailing spaces kept, as SpacingBetweenDeclarationsWithAnnotations writes the same whitespace
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, newlines + text.substringAfterLast('\n'))
        }
    }

    // SpacingBetweenDeclarationsWithAnnotations puts a blank line above an annotated declaration, so it never joins a group
    private fun KtExpression.isAnnotatedDeclaration() = this is KtProperty && annotationEntries.isNotEmpty()
}

// reads → derived values → effects → content → the effects the content drives. One finding per statement out of order.
object ComposableOrder : Rule {
    override val id = "composable-order"

    override fun findings(file: KtFile): List<Finding> =
        composableBodies(file).flatMap { body ->
            val name = (body.parent as KtNamedFunction).name
            var seenNonRead: Kind? = null
            var seenEffect = false
            var seenContent = false
            var seenLateEffect = false
            var leading = true
            body.statements.mapNotNull { statement ->
                val kind = statement.kind()
                // Guards before everything else may precede the reads: the skill puts the SDK guard first
                leading = leading && kind == Kind.GUARD
                val detail = when (kind) {
                    Kind.READ -> seenNonRead?.let { "A read (CompositionLocal, collected state, injection) after ${it.label()} in $name: reads come first." }
                    Kind.DERIVED -> if (seenEffect || seenContent) "A derived value after ${if (seenContent) "content" else "an effect"} in $name: values come before effects and content." else null
                    Kind.CONTENT -> if (seenLateEffect) "Content after an effect that follows the content in $name: only the effects the content drives come after it." else null
                    else -> null
                }
                if (kind != Kind.READ && !leading) seenNonRead = kind
                when (kind) {
                    Kind.EFFECT -> if (seenContent) seenLateEffect = true else seenEffect = true
                    Kind.CONTENT -> seenContent = true
                    else -> Unit
                }
                // A comment bound to a declaration starts its text range, so the finding points at the first code leaf
                detail?.let { Finding(statement.firstCodeLeaf().textRange.startOffset, it) }
            }
        }

    private fun Kind.label() = when (this) {
        Kind.DERIVED -> "a derived value"
        Kind.EFFECT -> "an effect"
        Kind.GUARD -> "a guard"
        Kind.LOCAL_FUN -> "a local function"
        else -> "content"
    }
}

// Guards and local funs are groups of their own; only a read after one is out of order.
private enum class Kind { READ, DERIVED, EFFECT, CONTENT, GUARD, LOCAL_FUN }

private val COLLECTORS = setOf("collectAsState", "collectAsStateWithLifecycle", "collectAsLazyPagingItems")

private val INJECTORS = setOf("koinInject", "koinViewModel")

private val COMPOSITION_LOCAL = Regex("""^Local[A-Z]\w*\s*\.\s*current\b""")

private val HANDLERS = setOf("BackHandler", "PredictiveBackHandler")

// TrackScreenViewEvent, TrackJobDetailOpen; not content named Track… (TracksPanel, TrackOption)
private val TRACKERS = Regex("""Track[A-Z]\w*(Event|Open)""")

private val NOT_NULL_CHECKS = setOf("requireNotNull", "checkNotNull")

// The block bodies of Unit-returning @Composable functions; one that returns a value is a remember-style factory, not UI.
private fun composableBodies(file: KtFile): List<KtBlockExpression> =
    file.descendants<KtNamedFunction>()
        .filter { fn -> fn.annotationEntries.any { it.shortName?.asString() == "Composable" } }
        .filter { fn -> fn.typeReference.let { it == null || it.text == "Unit" } }
        .mapNotNull { it.bodyBlockExpression }
        .filter { it.statements.isNotEmpty() }

private fun KtExpression.kind(): Kind = when (this) {
    is KtNamedFunction -> Kind.LOCAL_FUN
    is KtProperty -> if (isRead()) Kind.READ else Kind.DERIVED
    is KtDestructuringDeclaration -> Kind.DERIVED
    is KtCallExpression -> keyedStatement()?.kind() ?: if (isEffect()) Kind.EFFECT else Kind.CONTENT
    is KtIfExpression -> if (isGuard()) Kind.GUARD else Kind.CONTENT
    is KtAnnotatedExpression -> baseExpression?.kind() ?: Kind.CONTENT
    else -> Kind.CONTENT
}

// `key(k) { TrackScreenViewEvent(..) }` only scopes its one statement, so it is that statement
private fun KtCallExpression.keyedStatement(): KtExpression? {
    if (calleeExpression?.text != "key") return null
    return lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression?.statements?.singleOrNull()
}

private fun KtProperty.isRead(): Boolean = (delegateExpression ?: initializer)?.isReadValue() ?: false

private fun KtExpression.isReadValue(): Boolean {
    val callee = (this as? KtCallExpression)?.calleeExpression?.text
    // `requireNotNull(LocalActivity.current)` is still the read; a trailing message lambda does not change that
    if (callee in NOT_NULL_CHECKS) {
        val checked = (this as KtCallExpression).valueArgumentList?.arguments?.singleOrNull()?.getArgumentExpression()
        return checked?.isReadValue() ?: false
    }
    if (COMPOSITION_LOCAL.containsMatchIn(text)) return true
    if (callee in INJECTORS) return true
    // Collected state anywhere in the chain, so `….collectAsStateWithLifecycle().value` is a read too
    return generateSequence(this as? KtQualifiedExpression) { it.receiverExpression as? KtQualifiedExpression }
        .any { (it.selectorExpression as? KtCallExpression)?.calleeExpression?.text in COLLECTORS }
}

private fun KtCallExpression.isEffect(): Boolean {
    val callee = calleeExpression?.text ?: return false
    return callee.endsWith("Effect") || callee in HANDLERS || TRACKERS.matches(callee)
}

// `if (...) return`, braced or not, with no else
private fun KtIfExpression.isGuard(): Boolean {
    if (`else` != null) return false
    val then = then
    return then is KtReturnExpression || (then is KtBlockExpression && then.statements.singleOrNull() is KtReturnExpression)
}

private fun PsiElement.firstCodeLeaf(): PsiElement =
    generateSequence(firstChild) { it.firstChild }.lastOrNull()
        ?.let { first -> generateSequence(first) { PsiTreeUtil.nextLeaf(it) }.firstOrNull { it.isCode() } }
        ?: this
