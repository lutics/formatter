package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPropertyAccessor

// An annotated declaration that directly follows another declaration gets a blank line before it. A property's
// accessors are one declaration's parts, so an annotated setter stays directly under its getter.
object SpacingBetweenDeclarationsWithAnnotations : Rule {
    override val id = "spacing-between-declarations-with-annotations"

    override fun edits(file: KtFile): List<Edit> =
        file.descendants<KtDeclaration>().mapNotNull { decl ->
            if (decl is KtPropertyAccessor) return@mapNotNull null
            if (decl.modifierList?.children?.none { it is KtAnnotationEntry } != false) return@mapNotNull null
            val prev = generateSequence(decl.prevSibling) { it.prevSibling }.firstOrNull { it.isCode() }
            if (prev !is KtDeclaration) return@mapNotNull null
            val gap = generateSequence(PsiTreeUtil.prevLeaf(decl)) { PsiTreeUtil.prevLeaf(it) }.takeWhile { !it.isCode() }
            if (gap.any { it is PsiWhiteSpace && it.text.count { c -> c == '\n' } > 1 }) return@mapNotNull null
            val lastCode = generateSequence(PsiTreeUtil.prevLeaf(decl)) { PsiTreeUtil.prevLeaf(it) }.first { it.isCode() }
            val ws = generateSequence<PsiElement>(PsiTreeUtil.nextLeaf(lastCode)) { PsiTreeUtil.nextLeaf(it) }.first { it is PsiWhiteSpace }
            Edit(ws.textRange.startOffset, ws.textRange.endOffset, "\n\n" + decl.lineIndent())
        }.distinctBy { it.start }
}
