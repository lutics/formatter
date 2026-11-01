package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import io.github.lutics.formatter.rules.layout.descendants
import io.github.lutics.formatter.rules.layout.lineIndent
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpressionBase

// A condition — `if (…)`, `while (…)`, a `when (…)` subject — holds no call that spans lines: the call goes to a `val`
// above it. Only a statement-position `if` whose whole condition is the call or its negation is hoisted, because only
// there the `val` runs the call exactly when the condition did. Everywhere else moving it changes when or whether it
// runs (a loop re-evaluates its condition, `&&`/`||` short-circuit, an else-if runs after the earlier branch, a
// brace-less branch or loop body and an expression-position `if` have no statement slot above them), so it is reported
// for a person.
// The `val` carries no type, so a callee whose return type was inferred from the condition's expected Boolean (`fun <T>
// flag(): T`) would stop compiling. When a declaration of the callee in this file has type parameters and does not
// return a plain `Boolean`, and the call gives no type arguments, the if is reported. A callee declared elsewhere is
// hoisted unchecked, even when this file calls the same name with type arguments elsewhere: the verifying compile that
// follows the formatter in kotlin.sh fails loudly on an inference that broke.
object ConditionCallSpansLines : Rule {
    override val id = "condition-call-spans-lines"

    private sealed interface Verdict

    private class Hoist(val owner: KtIfExpression, val core: KtExpression, val negated: Boolean, val base: String) : Verdict

    private class Report(val call: KtCallExpression, val reason: String) : Verdict

    override fun edits(file: KtFile): List<Edit> {
        // names chosen earlier in this pass, per scope, so two hoists in one function never pick the same name
        val chosen = mutableMapOf<PsiElement, MutableSet<String>>()
        return verdicts(file).filterIsInstance<Hoist>().flatMap { hoist ->
            val scope = scopeOf(hoist.owner)
            val taken = chosen.getOrPut(scope) { identifiers(scope).toMutableSet() }
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) hoist.base else "${hoist.base}$it" }.first { it !in taken }
            taken += name
            val indent = hoist.owner.lineIndent()
            listOf(
                Edit(hoist.owner.textRange.startOffset, hoist.owner.textRange.startOffset, "val $name = ${reindented(hoist.core, indent)}\n$indent"),
                Edit(hoist.owner.leftParenthesis!!.textRange.endOffset, hoist.owner.rightParenthesis!!.textRange.startOffset, if (hoist.negated) "!$name" else name),
            )
        }
    }

    override fun findings(file: KtFile): List<Finding> =
        verdicts(file).filterIsInstance<Report>().map {
            Finding(it.call.textRange.startOffset, "A condition holds a call that spans lines (${it.reason}). Hoist the call to a val above; out of '&&'/'||' a call with a side effect becomes its own guard.")
        }

    private fun verdicts(file: KtFile): List<Verdict> {
        val all = PsiTreeUtil.findChildrenOfAnyType(file, KtIfExpression::class.java, KtWhileExpressionBase::class.java, KtWhenExpression::class.java)
            .mapNotNull { owner ->
                val condition = when (owner) {
                    is KtIfExpression -> owner.condition
                    is KtWhileExpressionBase -> owner.condition
                    is KtWhenExpression -> owner.subjectVariable ?: owner.subjectExpression
                    else -> null
                } ?: return@mapNotNull null
                val call = condition.splitCall() ?: return@mapNotNull null
                verdict(owner, condition, call)
            }
        // A hoistable if inside the call's arguments (a when-entry or try block) would put its edits inside the outer
        // `(…)` replacement. The inner one goes first; the next round of the rule sees the outer one again and hoists it.
        val hoists = all.filterIsInstance<Hoist>()
        return all.map { verdict ->
            if (verdict is Hoist && hoists.any { it !== verdict && it.owner.textRange in verdict.core.textRange }) {
                Report(verdict.core.splitCall()!!, "the call holds an if that is hoisted first")
            } else {
                verdict
            }
        }
    }

    private fun verdict(owner: PsiElement, condition: PsiElement, call: KtCallExpression): Verdict {
        if (owner is KtWhileExpressionBase) return Report(call, "a loop condition runs on every iteration")
        if (owner is KtWhenExpression) return Report(call, "a when subject")
        owner as KtIfExpression
        if (call.ancestorsUpTo(condition).any { it is KtLambdaExpression || it is KtFunction }) {
            return Report(call, "inside a lambda of the condition")
        }
        if (call.ancestorsUpTo(condition).any { it is KtBinaryExpression && it.operationToken in logical }) {
            return Report(call, "inside '&&'/'||'")
        }
        val negated = condition is KtPrefixExpression && condition.operationToken == KtTokens.EXCL
        val core = if (negated) (condition as KtPrefixExpression).baseExpression else condition as? KtExpression
        if (core == null || !core.isCall()) return Report(call, "not the whole condition")
        if (core.descendants<PsiElement>().any { it is KtLambdaExpression || it is KtFunction }) {
            return Report(call, "the condition holds a lambda")
        }
        if (owner.parent !is KtBlockExpression) {
            val reason = when (owner.parent?.node?.elementType) {
                KtNodeTypes.ELSE -> "an else-if runs after the earlier branch"
                KtNodeTypes.THEN -> "the body of a brace-less if branch"
                KtNodeTypes.BODY -> "the body of a brace-less loop"
                else -> "an expression-position if"
            }
            return Report(call, reason)
        }
        val before = PsiTreeUtil.prevLeaf(owner)
        if (before != null && !(before is PsiWhiteSpace && before.textContains('\n'))) {
            return Report(call, "the if does not start its line")
        }
        // Any comment between the parentheses, one inside `!` or the call's arguments included: the hoist rewrites that
        // span, and where a comment should land is a person's call.
        val inParens = generateSequence(owner.leftParenthesis?.let { PsiTreeUtil.nextLeaf(it) }) { PsiTreeUtil.nextLeaf(it) }
            .takeWhile { it != owner.rightParenthesis }
        if (owner.rightParenthesis == null || inParens.any { it is PsiComment }) return Report(call, "a comment in the condition")
        // The call whose value the condition tests is the selector, not the call that spans lines (`make<X>(…).flag()`);
        // `<_>` still leaves its type to the expected Boolean.
        val tested = core.call()
        val pinned = tested.typeArguments.let { args -> args.isNotEmpty() && args.none { it.text.trim() == "_" } }
        if (!pinned && !returnsPlainBoolean(tested)) {
            return Report(call, "the generic callee declared in this file does not return a plain Boolean")
        }
        val base = baseName(core) ?: return Report(call, "no plain identifier names the call")
        return Hoist(owner, core, negated, base)
    }

    private val logical = setOf(KtTokens.ANDAND, KtTokens.OROR)

    // The outermost call in the condition whose text spans lines, the condition itself included.
    private fun PsiElement.splitCall(): KtCallExpression? =
        (listOf(this) + descendants<PsiElement>()).filterIsInstance<KtCallExpression>().firstOrNull { it.textContains('\n') }

    private fun PsiElement.ancestorsUpTo(top: PsiElement): Sequence<PsiElement> =
        generateSequence(parent) { it.parent }.takeWhile { it != top.parent }

    private fun KtExpression.isCall(): Boolean =
        this is KtCallExpression || (this is KtDotQualifiedExpression && selectorExpression is KtCallExpression)

    private fun KtExpression.call(): KtCallExpression =
        this as? KtCallExpression ?: (this as KtDotQualifiedExpression).selectorExpression as KtCallExpression

    // The member or top-level declaration the if sits in: a local `val` is visible to the rest of it, nested lambdas and
    // local functions included, so every name in it is off limits.
    private fun scopeOf(owner: PsiElement): PsiElement =
        owner.ancestors()
            .filter { it is KtNamedFunction || it is KtSecondaryConstructor || it is KtClassInitializer || it is KtPropertyAccessor || it is KtProperty }
            .lastOrNull() ?: owner.containingFile

    // Every name declared or used in the scope, callees included: `val exists = exists(…)` compiles, but reads as if the
    // call were the value.
    private fun identifiers(scope: PsiElement): Set<String> =
        scope.descendants<PsiElement>().mapNotNull {
            when (it) {
                is KtNameReferenceExpression -> it.getReferencedName()
                is KtNamedDeclaration -> it.name
                else -> null
            }
        }.toSet()

    // Every function in the file sharing the callee's name, overloads and members of other classes included, is either
    // non-generic (its return type cannot come from the caller) or says `Boolean` outright; with none in the file the call
    // is not checked.
    private fun returnsPlainBoolean(call: KtCallExpression): Boolean {
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return true
        return PsiTreeUtil.findChildrenOfType(call.containingFile, KtNamedFunction::class.java).filter { it.name == callee }.all { function ->
            function.typeParameters.isEmpty() ||
                (function.typeReference?.text in setOf("Boolean", "kotlin.Boolean") && function.typeParameters.none { it.name == "Boolean" })
        }
    }

    // Soft and modifier keywords (`open`, `data`, `value`) are legal names; `field` is not, inside an accessor.
    private val keywords = KtTokens.KEYWORDS.types.map { it.toString() }.toSet() + "field"

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val factPrefixes = listOf("is", "has", "can", "should", "was", "will", "needs")

    // The `val` reads as the fact the call tests: isBlocked → blocked, shouldRing → ring. A getter given a literal key
    // (`getBoolean(key = "delete")`) is named after the key. Anything else is `<callee>Result`, never the callee itself.
    // A name that is not a plain, non-keyword identifier falls back to `<callee>Result`; null when that is not one either.
    private fun baseName(core: KtExpression): String? {
        val call = core.call()
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val fact = factPrefixes.firstNotNullOfOrNull { prefix ->
            callee.removePrefix(prefix).takeIf { callee.startsWith(prefix) && it.firstOrNull()?.isUpperCase() == true }
        }
        val key = if (callee.startsWith("get")) literalKey(call) else null
        val derived = fact?.let(::decapitalized) ?: key?.let(::lowerCamel)
        return listOfNotNull(derived, "${callee}Result").firstOrNull { it.matches(identifier) && it !in keywords }
    }

    private fun literalKey(call: KtCallExpression): String? {
        val argument = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "key" } ?: return null
        val string = argument.getArgumentExpression() as? KtStringTemplateExpression ?: return null
        if (string.entries.isEmpty() || !string.entries.all { it is KtLiteralStringTemplateEntry }) return null
        return string.entries.joinToString("") { it.text }
    }

    // "delete-all" / "delete_all" / "DELETE_ALL" → deleteAll
    private fun lowerCamel(text: String): String =
        text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .map { part -> if (part.none { it.isLowerCase() }) part.lowercase() else part }
            .mapIndexed { i, part -> if (i == 0) decapitalized(part) else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")

    // The leading uppercase run is lowercased except the letter that opens the next word: URLValid → urlValid, URL → url,
    // Blocked → blocked.
    private fun decapitalized(text: String): String {
        val run = text.takeWhile { it.isUpperCase() }.length
        val lowered = if (run > 1 && text.getOrNull(run)?.isLowerCase() == true) run - 1 else maxOf(run, 1)
        return text.take(lowered).lowercase() + text.drop(lowered)
    }

    // The call's continuation lines move with it: the line its closing bracket is on lands at the if's indent. A call
    // holding a multi-line string keeps its lines, since shifting them would change the string.
    private fun reindented(core: KtExpression, indent: String): String {
        val text = core.text
        val lines = text.split('\n')
        if (lines.size == 1) return text
        if (core.descendants<KtStringTemplateExpression>().any { it.textContains('\n') }) return text
        val last = lines.last()
        val delta = indent.length - last.takeWhile { it == ' ' }.length
        return lines.mapIndexed { i, line ->
            when {
                i == 0 || line.isBlank() -> line
                delta >= 0 -> " ".repeat(delta) + line
                else -> line.drop(minOf(-delta, line.takeWhile { it == ' ' }.length))
            }
        }.joinToString("\n")
    }
}
