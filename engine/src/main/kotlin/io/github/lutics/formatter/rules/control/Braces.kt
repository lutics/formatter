package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.rules.wrapcomma.INDENT
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

// A statement body wrapped in braces, as multiline if/else and loops require.
// `edits` rewrite the text; `footprint` is where the decision and the indent were read from, so a caller
// batching several wraps can defer one whose context an earlier edit of the same pass changed.
class Wrap(val edits: List<Edit>, val footprint: Int)

// body is the THEN/ELSE/BODY container; stops are the tokens that end the gap in front of it.
// fixSpaceAfter makes whatever follows the closing brace start with a single space (`} else`).
fun wrapInBlock(body: PsiElement, parent: PsiElement, stops: Set<IElementType>, fixSpaceAfter: Boolean): Wrap {
    val prev = generateSequence(body.prevLeaf) { it.prevLeaf }.takeWhile { it.type !in stops }.toList().reversed()
    val next = generateSequence(body.lastLeaf().nextLeaf) { it.nextLeaf }
        .takeWhile { it.isWhiteSpaceWithoutNewline || it.isPartOfComment }
        .toList()
        .dropLastWhile { it.isWhiteSpaceWithoutNewline }
    val firstPrev = prev.firstOrNull()?.takeIf { it.isWhiteSpace }

    // the child indent is read before the leaves move into the block, with the first gap whitespace already a space
    val childAnchor = body.prevLeaf { it.isWhiteSpaceWithNewline && it !== firstPrev }
    val endAnchor = (prev.firstOrNull() ?: body).prevLeaf { it.isWhiteSpaceWithNewline }
    fun indentOf(anchor: PsiElement?) = anchor?.text?.substringAfterLast('\n').orEmpty()

    val start = (prev.firstOrNull() ?: body).textRange.startOffset
    val end = (next.lastOrNull() ?: body).textRange.endOffset
    val moved = prev.dropWhile { it.isWhiteSpace }.joinToString("") { it.text }
    val text = (if (firstPrev != null) " " else "") + "{\n" + indentOf(childAnchor) + INDENT + moved +
        body.text + next.joinToString("") { it.text } + "\n" + indentOf(endAnchor) + "}"
    val edits = mutableListOf(Edit(start, end, text))

    if (fixSpaceAfter) {
        val sibling = body.siblingsAfter().firstOrNull { it.textRange.startOffset >= end && !it.isPartOfComment }
        if (sibling != null) {
            if (sibling.isWhiteSpace) {
                if (sibling.text != " ") edits += Edit(sibling.textRange.startOffset, sibling.textRange.endOffset, " ")
            } else {
                edits += Edit(sibling.textRange.startOffset, sibling.textRange.startOffset, " ")
            }
        }
    }
    val footprint = listOfNotNull(parent, childAnchor, endAnchor).minOf { it.textRange.startOffset }
    return Wrap(edits, footprint)
}

// Candidates in document order; one whose footprint reaches into an earlier edit waits for the next pass.
fun batchWraps(candidates: Sequence<Wrap?>): List<Edit> {
    val out = mutableListOf<Edit>()
    var lastEnd = -1
    for (w in candidates) {
        if (w == null || w.footprint < lastEnd) continue
        out += w.edits
        lastEnd = w.edits.maxOf { it.end }
    }
    return out
}
