package io.github.lutics.formatter.rules.layout

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
