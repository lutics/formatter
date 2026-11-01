package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtValueArgumentName

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
// A statement is reported only when moving it fixes it without changing what the composition does:
// - a statement that can leave the function (a return or throw outside a labeled lambda return, a guard included)
//   decides whether everything after it runs, so nothing may cross it: it closes a section, the order starts over
//   after it, and it is never reported itself;
// - a statement that uses what an earlier out-of-order statement declares or assigns during composition, directly or
//   through the statements between them, cannot move above it (`val vm = if (booted) koinViewModel() else null`
//   then `vm?.uiState?.collectAsStateWithLifecycle()`), and neither can one that declares a name that statement uses,
//   which would shadow it. An effect's lambdas and a local fun's body run later, so what they assign does not count.
object ComposableOrder : Rule {
    override val id = "composable-order"

    override fun findings(file: KtFile): List<Finding> =
        composableBodies(file).flatMap { body ->
            val name = (body.parent as KtNamedFunction).name
            var seenNonRead: Kind? = null
            var seenEffect = false
            var seenContent = false
            var seenLateEffect = false
            // the section so far: each statement's kind, its dependencies on earlier ones (closed transitively), and
            // what makes the current statement out of order
            val kinds = mutableListOf<Kind>()
            val uses = mutableListOf<Set<String>>()
            val defines = mutableListOf<Set<String>>()
            val deps = mutableListOf<Set<Int>>()
            body.statements.mapNotNull { statement ->
                val kind = statement.kind()
                if (kind != Kind.LOCAL_FUN && statement.exits()) {
                    seenNonRead = null
                    seenEffect = false
                    seenContent = false
                    seenLateEffect = false
                    kinds.clear()
                    uses.clear()
                    defines.clear()
                    deps.clear()
                    return@mapNotNull null
                }
                val used = statement.usedNames()
                val declared = statement.declaredNames()
                val defined = declared + if (kind == Kind.EFFECT || kind == Kind.LOCAL_FUN) emptySet() else statement.assignedNames()
                val direct = kinds.indices.filter { j -> (used intersect defines[j]).isNotEmpty() || (uses[j] intersect declared).isNotEmpty() }
                val dependsOn = direct.toSet() + direct.flatMap { deps[it] }
                // the earlier statements this one would have to move above
                val offenders = kinds.indices.filter { j ->
                    when (kind) {
                        Kind.READ -> kinds[j] != Kind.READ
                        Kind.DERIVED -> kinds[j] == Kind.EFFECT || kinds[j] == Kind.CONTENT
                        Kind.CONTENT -> kinds[j] == Kind.EFFECT && kinds.subList(0, j).contains(Kind.CONTENT)
                        else -> false
                    }
                }
                val movable = offenders.none { it in dependsOn }
                val detail = if (!movable) {
                    null
                } else {
                    when (kind) {
                        Kind.READ -> seenNonRead?.let { "A read (CompositionLocal, collected state, injection) after ${it.label()} in $name: reads come first." }
                        Kind.DERIVED -> if (seenEffect || seenContent) "A derived value after ${if (seenContent) "content" else "an effect"} in $name: values come before effects and content." else null
                        Kind.CONTENT -> if (seenLateEffect) "Content after an effect that follows the content in $name: only the effects the content drives come after it." else null
                        else -> null
                    }
                }
                if (kind != Kind.READ) seenNonRead = kind
                when (kind) {
                    Kind.EFFECT -> if (seenContent) seenLateEffect = true else seenEffect = true
                    Kind.CONTENT -> seenContent = true
                    else -> Unit
                }
                kinds += kind
                uses += used
                defines += defined
                deps += dependsOn
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

// A local fun is a group of its own: only a read after one is out of order. A guard closes a section (ComposableOrder).
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
    // `state = rememberX(…)` into a lateinit local the callbacks above it read is a value being given, not content
    is KtBinaryExpression -> if (operationToken in KtTokens.ALL_ASSIGNMENTS) Kind.DERIVED else Kind.CONTENT
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

// Names the statement reads; named-argument labels, operators and labels name nothing in scope.
private fun KtExpression.usedNames(): Set<String> =
    descendants<KtSimpleNameExpression>()
        .filter { it !is KtOperationReferenceExpression && it !is KtLabelReferenceExpression && it.parent !is KtValueArgumentName }
        .map { it.getReferencedName() }
        .toSet()

private fun KtExpression.declaredNames(): Set<String> = when (this) {
    is KtProperty -> setOfNotNull(name)
    is KtDestructuringDeclaration -> entries.mapNotNull { it.name }.toSet()
    is KtNamedFunction -> setOfNotNull(name)
    else -> emptySet()
}

// The local each assignment or increment writes, by the name at the root of its target (`state.value = x` writes state)
private fun KtExpression.assignedNames(): Set<String> {
    fun root(target: KtExpression?): String? = when (target) {
        is KtSimpleNameExpression -> target.getReferencedName()
        is KtQualifiedExpression -> root(target.receiverExpression)
        is KtArrayAccessExpression -> root(target.arrayExpression)
        else -> null
    }
    val assignments = descendants<KtBinaryExpression>().filter { it.operationToken in KtTokens.ALL_ASSIGNMENTS }.mapNotNull { root(it.left) }
    val increments = descendants<KtUnaryExpression>()
        .filter { it.operationToken == KtTokens.PLUSPLUS || it.operationToken == KtTokens.MINUSMINUS }
        .mapNotNull { root(it.baseExpression) }
    return (assignments + increments).toSet()
}

// An unlabeled return leaves the function, from an inline lambda too; a labeled one leaves only its lambda
private fun KtExpression.exits(): Boolean =
    descendants<KtReturnExpression>().any { it.getTargetLabel() == null } || descendants<KtThrowExpression>().isNotEmpty()
