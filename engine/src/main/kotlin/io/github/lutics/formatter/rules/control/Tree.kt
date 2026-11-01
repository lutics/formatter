package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.rules.wrapcomma.firstChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.isPartOfComment
import io.github.lutics.formatter.rules.wrapcomma.lastChildLeafOrSelf
import io.github.lutics.formatter.rules.wrapcomma.nextLeaf
import io.github.lutics.formatter.rules.wrapcomma.prevLeaf
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

// The shared AST helpers on PSI elements. Whitespace and comments are tree nodes in the PSI,
// so "previous leaf" and "next sibling" see them the way a token-based rule does.

val PsiElement.type: IElementType get() = node.elementType

val PsiElement.isWhiteSpace: Boolean get() = this is PsiWhiteSpace
val PsiElement.isWhiteSpaceWithNewline: Boolean get() = this is PsiWhiteSpace && textContains('\n')
val PsiElement.isWhiteSpaceWithoutNewline: Boolean get() = this is PsiWhiteSpace && !textContains('\n')
val PsiElement.isPartOfComment: Boolean get() = node.isPartOfComment
val PsiElement.isCode: Boolean get() = !isWhiteSpace && !isPartOfComment

fun PsiElement.lastLeaf(): PsiElement = node.lastChildLeafOrSelf.psi

// The leaf right after / before this element's subtree, skipping empty nodes.
val PsiElement.nextLeaf: PsiElement? get() = node.lastChildLeafOrSelf.nextLeaf?.psi

val PsiElement.prevLeaf: PsiElement? get() = node.prevLeaf?.psi

fun PsiElement.nextLeaf(predicate: (PsiElement) -> Boolean): PsiElement? =
    generateSequence(nextLeaf) { it.nextLeaf }.firstOrNull(predicate)

fun PsiElement.prevLeaf(predicate: (PsiElement) -> Boolean): PsiElement? =
    generateSequence(prevLeaf) { it.prevLeaf }.firstOrNull(predicate)

val PsiElement.prevCodeLeaf: PsiElement? get() = prevLeaf { it.isCode }

// Siblings after / before this element, nearest first, excluding the element itself.
fun PsiElement.siblingsAfter(): Sequence<PsiElement> = generateSequence(nextSibling) { it.nextSibling }

fun PsiElement.siblingsBefore(): Sequence<PsiElement> = generateSequence(prevSibling) { it.prevSibling }

// Ancestors from the parent up, stopping before the file.
fun PsiElement.ancestors(): Sequence<PsiElement> = generateSequence(parent) { it.parent }.takeWhile { it !is PsiFile }

// Every leaf of the file in document order (PsiTreeUtil.findChildrenOfType does not visit leaves).
fun PsiElement.leaves(): Sequence<PsiElement> = generateSequence(node.firstChildLeafOrSelf.psi) { it.nextLeaf }
