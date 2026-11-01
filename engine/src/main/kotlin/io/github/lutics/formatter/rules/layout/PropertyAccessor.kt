package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty

// get/set, a visibility-only `private set` included, start their own line at property indent +4. The one exception is a
// read-only getter whose declaration (from `val` on) is one physical line, which shared_sync.py relies on. The exception
// holds in practice only for an expression-body getter (`get() = …`): a one-line block-body getter (`get() { return 1 }`)
// is spread over lines by other rules, and the getter then breaks too. Accessors are found by PSI because Koin's `get()`
// call has the same tokens. Only the line break is decided here; an accessor already on its own line is left to Indent.
object PropertyAccessor : Rule {
    override val id = "property-accessor"

    private val VISIBILITIES = setOf("private", "internal", "protected", "public")

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<KtProperty>().flatMap { property ->
            val indent = property.lineIndent() + "    "
            val misread = property.misreadSetters().mapNotNull { breakBefore(it, indent) }
            if (property.accessors.isEmpty() || property.isOneLineGetter()) return@flatMap misread
            property.accessors.mapNotNull { breakBefore(it, indent) } + misread
        }

    private fun breakBefore(element: PsiElement, indent: String): Edit? {
        val before = PsiTreeUtil.prevLeaf(element) ?: return null
        if (before is PsiWhiteSpace && before.text.contains('\n')) return null
        val start = element.textRange.startOffset
        return if (before is PsiWhiteSpace) {
            Edit(before.textRange.startOffset, start, "\n" + indent)
        } else {
            Edit(start, start, "\n" + indent)
        }
    }

    // A setter on the line of the value before it is not parsed as a setter: `var count = 0 private set` is the infix call
    // `0 private set`; the line break turns it into the setter it was meant to be. Infix binds looser than arithmetic,
    // so the call ends the expression. The value is the initializer or delegate and, separately, a getter's expression
    // body (`get() = _x private set`), since a `var` with an initializer can still carry a misread setter after its getter.
    // The shapes taken, all at the very end of the value:
    // - `<visibility> set`, `<visibility> set(v) { … }`: the infix call `<visibility>` whose right side is `set` or a call of it
    // - `set(v) { … }` with no visibility: the infix call `set` on `(v) { … }`, a parenthesized name invoked with a lambda
    // - `<visibility> set(v) = e` and `set(v) = e`: an assignment whose target is one of those infix calls, never valid code
    // Deliberate limits: a real infix `set` whose argument is `(f) { … }` (a parenthesized function value invoked with a
    // lambda) is taken for a setter, a shape the fleet does not have; a setter parameter with a type (`set(v: Int)`) does
    // not parse as either call and is not taken. Only a `var` can take the setter, so a `val` is left as the compile
    // error it already is, and a local `var` has no accessors, so an infix `set` there is a real call.
    private fun KtProperty.misreadSetters(): List<PsiElement> {
        if (!isVar || isLocal) return emptyList()
        val getterBody = getter?.takeIf { it.hasBody() && !it.hasBlockBody() }?.bodyExpression
        return listOfNotNull(initializer, delegateExpression, getterBody).mapNotNull { it.misreadSetter() }
    }

    private fun KtExpression.misreadSetter(): PsiElement? {
        val end = textRange.endOffset
        return PsiTreeUtil.findChildrenOfType(this, KtBinaryExpression::class.java)
            .plus(listOfNotNull(this as? KtBinaryExpression))
            .filter { it.textRange.endOffset == end }
            .firstNotNullOfOrNull { it.setterCall(withValue = false) ?: it.setterAssignment() }
    }

    // `x = e` whose target `x` is a misread setter call: the setter's expression body.
    private fun KtBinaryExpression.setterAssignment(): PsiElement? {
        if (operationToken != KtTokens.EQ) return null
        return (left as? KtBinaryExpression)?.setterCall(withValue = true)
    }

    // The operator to break before: the visibility of `<visibility> set…`, or `set` itself.
    private fun KtBinaryExpression.setterCall(withValue: Boolean): PsiElement? {
        val op = operationReference
        val right = right
        val taken = when (op.text) {
            in VISIBILITIES -> if (withValue) right.isSetCallWithParameter() else right.isSetName() || right.isSetCallWithLambda()
            "set" -> if (withValue) right.isParenthesizedName() else right.isParenthesizedNameWithLambda()
            else -> false
        }
        return op.takeIf { taken }
    }

    private fun PsiElement?.isSetName(): Boolean = this is KtNameReferenceExpression && getReferencedName() == "set"

    private fun PsiElement?.isSetCallWithLambda(): Boolean =
        this is KtCallExpression && calleeExpression.isSetName() && lambdaArguments.size == 1

    private fun PsiElement?.isSetCallWithParameter(): Boolean =
        this is KtCallExpression && calleeExpression.isSetName() && lambdaArguments.isEmpty() && valueArguments.size == 1 &&
            valueArguments[0].getArgumentExpression() is KtNameReferenceExpression

    private fun PsiElement?.isParenthesizedName(): Boolean =
        this is KtParenthesizedExpression && expression is KtNameReferenceExpression

    private fun PsiElement?.isParenthesizedNameWithLambda(): Boolean =
        this is KtCallExpression && calleeExpression.isParenthesizedName() && valueArgumentList == null && lambdaArguments.size == 1

    // Annotations above the `val` on their own lines do not count against the one line.
    private fun KtProperty.isOneLineGetter(): Boolean {
        if (isVar || setter != null || getter == null) return false
        val from = valOrVarKeyword.textRange.startOffset - textRange.startOffset
        return !text.substring(from).contains('\n')
    }
}
