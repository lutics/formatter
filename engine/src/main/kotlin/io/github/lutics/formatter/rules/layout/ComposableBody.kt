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
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
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
// composable-order's.
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

// reads → derived values → effects → content → the effects the content drives. A statement out of order is one that
// moving fixes without changing what the composition does:
// - a statement that can leave the function (a return or throw, a guard included; a return labeled with a lambda's
//   name leaves only that lambda, one labeled with the function's own name leaves the function) decides whether
//   everything after it runs, so nothing may cross it: it closes a section, the order starts over after it, and it is
//   never out of order itself;
// - a statement that uses what an earlier out-of-order statement declares or assigns during composition, directly or
//   through the statements between them, cannot move above it (`val vm = if (booted) koinViewModel() else null`
//   then `vm?.uiState?.collectAsStateWithLifecycle()`), and neither can one that declares a name that statement uses,
//   which would shadow it. An effect's lambdas and a local fun's body run later, so what they assign does not count.
// format moves only a CompositionLocal read alone (`val x = LocalX.current`, LocalX neither a name the function
// declares nor a var of the file): reading a CompositionLocal runs none of the code around it and changes nothing, and no statement of the body
// can change what it reads (only an enclosing provider sets it), so it may run before the statements it crosses, a
// statement that throws included. It moves up to the end of its section's leading reads, with the comments that travel
// with it (travellingSpan), keeping the reads it moves in their order, unless it would cross a read that stays and that
// it depends on (by name, as above), or it or the statement it lands above shares a line with another statement. Every
// other statement out of order is reported, never moved: injection runs a constructor, collected state evaluates its
// receiver and initial value and starts a collection, a CompositionLocal read wrapped in a call or chained runs that
// code, and a derived value or content crosses effects and content; none of that code is visible to a name analysis
// (a call may change a global, or the receiver of a call made without one).
// The move trusts the name: LocalX is a CompositionLocal, declared as a val outside the function (a var of another file
// that a crossed call reassigns is not seen). Where nothing provides it, its default factory runs (or throws) before
// the crossed statements instead of after them.
object ComposableOrder : Rule {
    override val id = "composable-order"

    override fun edits(file: KtFile): List<Edit> = composableBodies(file).flatMap { analyse(it).first }

    override fun findings(file: KtFile): List<Finding> = composableBodies(file).flatMap { analyse(it).second }

    // One statement of a section: the names it uses, the earlier statements it depends on (closed transitively), and why
    // it is out of order (null when it is not).
    private class Entry(val statement: KtExpression, val kind: Kind, val used: Set<String>, val dependsOn: Set<Int>, val detail: String?)

    // The moves as edits, and a finding for every statement out of order that is not moved.
    private fun analyse(body: KtBlockExpression): Pair<List<Edit>, List<Finding>> {
        val function = body.parent as KtNamedFunction
        val name = function.name
        // the names that are not a CompositionLocal here: every name the function declares (its parameters and nested
        // declarations included), and every var of the file (a class's or a top-level one), which a crossed call may
        // reassign
        val notLocals = function.descendants<KtNamedDeclaration>().mapNotNull { it.name }.toSet() +
            function.containingKtFile.descendants<KtProperty>().filter { it.isVar }.mapNotNull { it.name }
        val edits = mutableListOf<Edit>()
        val findings = mutableListOf<Finding>()
        var seenNonRead: Kind? = null
        var seenEffect = false
        var seenContent = false
        var seenLateEffect = false
        val section = mutableListOf<Entry>()
        val defines = mutableListOf<Set<String>>()
        for (statement in body.statements) {
            val kind = statement.kind()
            if (kind != Kind.LOCAL_FUN && statement.exits(name)) {
                closeSection(section, notLocals, edits, findings)
                seenNonRead = null
                seenEffect = false
                seenContent = false
                seenLateEffect = false
                section.clear()
                defines.clear()
                continue
            }
            val used = statement.usedNames()
            val declared = statement.declaredNames()
            val defined = declared + if (kind == Kind.EFFECT || kind == Kind.LOCAL_FUN) emptySet() else statement.assignedNames()
            val direct = section.indices.filter { j -> (used intersect defines[j]).isNotEmpty() || (section[j].used intersect declared).isNotEmpty() }
            val dependsOn = direct.toSet() + direct.flatMap { section[it].dependsOn }
            // the earlier statements this one would have to move above
            val offenders = section.indices.filter { j ->
                val other = section[j].kind
                when (kind) {
                    Kind.READ -> other != Kind.READ
                    Kind.DERIVED -> other == Kind.EFFECT || other == Kind.CONTENT
                    Kind.CONTENT -> other == Kind.EFFECT && section.subList(0, j).any { it.kind == Kind.CONTENT }
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
            section += Entry(statement, kind, used, dependsOn, detail)
            defines += defined
        }
        closeSection(section, notLocals, edits, findings)
        return edits to findings
    }

    // Moves the section's CompositionLocal reads that are out of order up to the end of its leading reads, in their order,
    // and reports every other statement out of order.
    private fun closeSection(section: List<Entry>, notLocals: Set<String>, edits: MutableList<Edit>, findings: MutableList<Finding>) {
        val leading = section.indexOfFirst { it.kind != Kind.READ }.takeIf { it >= 0 } ?: return
        // the section's order as the moves so far leave it; the moved reads sit at [leading, end)
        val order = section.indices.toMutableList()
        var end = leading
        val moved = mutableListOf<Entry>()
        for ((i, entry) in section.withIndex()) {
            entry.detail ?: continue
            val safe = entry.statement.isCompositionLocalRead(notLocals) &&
                // a crossed statement it depends on can only be a read that stays: any other makes it not out of order
                order.subList(end, order.indexOf(i)).none { it in entry.dependsOn } &&
                entry.statement.ownsLines() && section[leading].statement.ownsLines()
            if (!safe) {
                // A comment bound to a declaration starts its text range, so the finding points at the first code leaf
                findings += Finding(entry.statement.firstCodeLeaf().textRange.startOffset, entry.detail)
                continue
            }
            order.remove(i)
            order.add(end, i)
            end++
            moved += entry
        }
        if (moved.isEmpty()) return
        val target = section[leading].statement
        val indent = target.lineIndent()
        val text = target.containingFile.text
        val at = target.travellingSpan().startOffset
        edits += Edit(at, at, moved.joinToString(separator = "") { it.statement.travellingSpan().substring(text) + "\n" + indent })
        for (entry in moved) {
            val span = entry.statement.travellingSpan()
            // the read's lines go, from the start of its first line through the line break after it, so the finding check
            // reports for this edit points at the read
            val lineStart = text.lastIndexOf('\n', span.startOffset - 1) + 1
            val lineEnd = text.indexOf('\n', span.endOffset).takeIf { it >= 0 }?.plus(1) ?: text.length
            edits += Edit(lineStart, lineEnd, "")
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

private val LOCAL_NAME = Regex("""Local[A-Z]\w*""")

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

// `val x = LocalX.current` (or `var`, with a type or not) and nothing else, LocalX not one of notLocals (the function's own
// names and the file's vars), so it names a CompositionLocal declared as a val outside the function. A delegate, a call
// around it or a selector after it runs code the read alone does not.
private fun KtExpression.isCompositionLocalRead(notLocals: Set<String>): Boolean {
    val property = this as? KtProperty ?: return false
    if (property.hasDelegate()) return false
    val value = property.initializer as? KtDotQualifiedExpression ?: return false
    val local = (value.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
    val selector = (value.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
    return selector == "current" && LOCAL_NAME.matches(local) && local !in notLocals
}

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

// A local class counts: a later statement that names it means that class, not one declared outside the function.
private fun KtExpression.declaredNames(): Set<String> = when (this) {
    is KtProperty -> setOfNotNull(name)
    is KtDestructuringDeclaration -> entries.mapNotNull { it.name }.toSet()
    is KtNamedFunction -> setOfNotNull(name)
    is KtClassOrObject -> setOfNotNull(name)
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

// An unlabeled return leaves the function, from an inline lambda too, and so does one labeled with the function's own name
// (`return@MainScreen`, `?: return@MainScreen`); one labeled with a lambda's name leaves only that lambda.
private fun KtExpression.exits(function: String?): Boolean =
    descendants<KtReturnExpression>().any { it.getLabelName().let { label -> label == null || label == function } } ||
        descendants<KtThrowExpression>().isNotEmpty()
