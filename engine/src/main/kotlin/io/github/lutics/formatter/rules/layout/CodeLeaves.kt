package io.github.lutics.formatter.rules.layout

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace

fun PsiElement.isComment() = this is PsiComment || parent is org.jetbrains.kotlin.kdoc.psi.api.KDoc

// Whitespace, comments and KDoc contents carry no code.
fun PsiElement.isCode() = this !is PsiWhiteSpace && !isComment()

// The leading whitespace of the line the element starts on.
fun PsiElement.lineIndent(): String {
    val text = containingFile.text
    val start = textRange.startOffset
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    return text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
}

// Every descendant of a type, nested ones included (PsiTreeUtil.findChildrenOfType skips leaves such as whitespace).
inline fun <reified T : PsiElement> PsiElement.descendants(): List<T> {
    val found = mutableListOf<T>()
    accept(object : org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element is T) found += element
            super.visitElement(element)
        }
    })
    return found
}

// Whether only whitespace precedes the element on its line.
fun PsiElement.startsLine(): Boolean {
    val text = containingFile.text
    val lineStart = text.lastIndexOf('\n', textRange.startOffset - 1) + 1
    return text.substring(lineStart, textRange.startOffset).isBlank()
}

// An item (a statement, a member, a when branch) with the comments that travel with it when it moves: own-line comments
// right above it, up to a blank line (a comment a blank line above heads a group, not this item), and a comment after
// it on its last line.
fun PsiElement.travellingSpan(): TextRange {
    var start: PsiElement = this
    var prev = prevSibling
    while (prev != null && (prev is PsiWhiteSpace || prev.isComment())) {
        if (prev is PsiWhiteSpace && prev.text.count { it == '\n' } >= 2) break
        if (prev.isComment()) {
            if (!prev.startsLine()) break
            start = prev
        }
        prev = prev.prevSibling
    }
    var end: PsiElement = this
    var next = nextSibling
    while (next is PsiWhiteSpace && !next.text.contains('\n')) next = next.nextSibling
    if (next != null && next.isComment()) end = next
    return TextRange(start.textRange.startOffset, end.textRange.endOffset)
}

// Whether the item, with the comments that travel with it, starts its line and ends it: it shares no line with other
// code, so its lines can move as a whole without carrying that code along or leaving a separator after a line comment.
fun PsiElement.ownsLines(): Boolean {
    val span = travellingSpan()
    val text = containingFile.text
    val lineStart = text.lastIndexOf('\n', span.startOffset - 1) + 1
    val lineEnd = text.indexOf('\n', span.endOffset).takeIf { it >= 0 } ?: text.length
    return text.substring(lineStart, span.startOffset).isBlank() && text.substring(span.endOffset, lineEnd).isBlank()
}
