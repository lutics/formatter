package io.github.lutics.formatter.rules.wrapcomma

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Engine
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.mock.MockComponentManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.pom.PomModelAspect
import org.jetbrains.kotlin.com.intellij.pom.PomTransaction
import org.jetbrains.kotlin.com.intellij.pom.impl.PomTransactionBase
import org.jetbrains.kotlin.com.intellij.pom.tree.TreeAspect
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.leaves
import sun.reflect.ReflectionFactory

// The wrapping rules rewrite the tree while they walk it, so a node visited later sees the rewrites made before it
// (an argument moved to a new line gives its own arguments that line's indent). Reproducing them means walking a private,
// mutable parse of the file the same way, and returning the text that changed as one edit.
abstract class TreeRule : Rule {
    protected open fun beforeVisit(node: ASTNode) {}

    protected open fun afterVisit(node: ASTNode) {}

    override fun edits(file: KtFile): List<Edit> {
        val before = file.text
        val copy = Engine.parse(file.name, before)
        enableMutation(copy.project)
        visit(copy.node)
        val after = copy.node.text
        if (after == before) return emptyList()
        val prefix = before.commonPrefixWith(after).length
        val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
        return listOf(Edit(prefix, before.length - suffix, after.substring(prefix, after.length - suffix)))
    }

    private fun visit(node: ASTNode) {
        beforeVisit(node)
        // the children as they were before the visit, as the rules expect: a node the rule inserts is not visited
        node.getChildren(null).forEach { visit(it) }
        afterVisit(node)
    }

    private companion object {
        val mutable = mutableSetOf<Project>()

        // The core environment cannot change a tree until a PomModel runs its transactions.
        fun enableMutation(project: Project) =
            synchronized(mutable) {
                if (mutable.add(project)) (project as MockComponentManager).registerService(PomModel::class.java, FormatPomModel())
            }
    }
}

private class FormatPomModel :
    UserDataHolderBase(),
    PomModel {
    override fun runTransaction(transaction: PomTransaction) {
        (transaction as PomTransactionBase).run()
    }

    // TreeAspect has no usable constructor outside the IDE, so it is built the way serialization builds objects
    @Suppress("UNCHECKED_CAST")
    override fun <T : PomModelAspect> getModelAspect(aspect: Class<T>): T? {
        if (aspect != TreeAspect::class.java) return null
        val constructor = ReflectionFactory.getReflectionFactory()
            .newConstructorForSerialization(aspect, Any::class.java.getDeclaredConstructor())
        return constructor.newInstance() as T
    }
}

const val INDENT = "    "

// AST helpers shared by the rule groups, with exact semantics: leaves of zero length are skipped, whitespace is only
// WHITE_SPACE, and an indent is the text after the last line break before the line the node starts on.

val ASTNode.parent: ASTNode? get() = treeParent

val ASTNode.childNodes: Sequence<ASTNode> get() = generateSequence(firstChildNode) { it.treeNext }

val ASTNode.isLeaf: Boolean get() = firstChildNode == null

val ASTNode?.isWhiteSpace: Boolean get() = this != null && elementType == KtTokens.WHITE_SPACE

val ASTNode?.isWhiteSpaceWithNewline: Boolean get() = this != null && isWhiteSpace && textContains('\n')

val ASTNode?.isWhiteSpaceWithoutNewline: Boolean get() = this != null && isWhiteSpace && !textContains('\n')

fun ASTNode.isPartOf(type: IElementType): Boolean = generateSequence(this) { it.treeParent }.any { it.elementType == type }

val ASTNode.isPartOfComment: Boolean get() = generateSequence(this) { it.treeParent }.any { it.elementType in KtTokens.COMMENTS }

val ASTNode.isCode: Boolean get() = !isWhiteSpace && !isPartOfComment

fun ASTNode.parent(predicate: (ASTNode) -> Boolean): ASTNode? = generateSequence(treeParent) { it.treeParent }.firstOrNull(predicate)

fun ASTNode.prevSibling(predicate: (ASTNode) -> Boolean): ASTNode? = generateSequence(treePrev) { it.treePrev }.firstOrNull(predicate)

fun ASTNode.nextSibling(predicate: (ASTNode) -> Boolean): ASTNode? = generateSequence(treeNext) { it.treeNext }.firstOrNull(predicate)

val ASTNode.prevCodeSibling: ASTNode? get() = prevSibling { it.isCode }

val ASTNode.nextCodeSibling: ASTNode? get() = nextSibling { it.isCode }

val ASTNode.firstChildLeafOrSelf: ASTNode get() = generateSequence(this) { it.firstChildNode }.last()

val ASTNode.lastChildLeafOrSelf: ASTNode get() = generateSequence(this) { it.lastChildNode }.last()

// A composite's next leaf is its own first leaf; its previous leaf is the one before it.
private val ASTNode.nextLeafAny: ASTNode? get() = if (isLeaf) nextLeafStrict else firstChildLeafOrSelf

private val ASTNode.nextLeafStrict: ASTNode? get() = treeNext?.firstChildLeafOrSelf ?: parent?.nextLeafStrict

private val ASTNode.prevLeafAny: ASTNode? get() = treePrev?.lastChildLeafOrSelf ?: parent?.prevLeafAny

val ASTNode.nextLeaf: ASTNode? get() = generateSequence(nextLeafAny) { it.nextLeafAny }.firstOrNull { it.textLength > 0 }

val ASTNode.prevLeaf: ASTNode? get() = generateSequence(prevLeafAny) { it.prevLeafAny }.firstOrNull { it.textLength > 0 }

// Unlike nextLeaf and prevLeaf, these test empty leaves too.
fun ASTNode.nextLeaf(predicate: (ASTNode) -> Boolean): ASTNode? = generateSequence(nextLeafAny) { it.nextLeafAny }.firstOrNull(predicate)

fun ASTNode.prevLeaf(predicate: (ASTNode) -> Boolean): ASTNode? = generateSequence(prevLeafAny) { it.prevLeafAny }.firstOrNull(predicate)

val ASTNode.prevCodeLeaf: ASTNode? get() = generateSequence(prevLeaf) { it.prevLeaf }.firstOrNull { it.isCode }

val ASTNode.nextCodeLeaf: ASTNode? get() = generateSequence(nextLeaf) { it.nextLeaf }.firstOrNull { it.isCode }

// Whether a leaf from the first leaf of from to the last leaf of to holds a line break.
fun hasNewLineInClosedRange(from: ASTNode, to: ASTNode): Boolean {
    val first = from.firstChildLeafOrSelf
    val stop = to.lastChildLeafOrSelf.nextLeaf
    return (sequenceOf(first) + first.leaves()).takeWhile { it != stop }.any { it.textContains('\n') }
}

val ASTNode.indentWithoutNewlinePrefix: String
    get() = leaves(forward = false).firstOrNull { it.isWhiteSpaceWithNewline }?.text?.substringAfterLast('\n').orEmpty()

// The indent of the line the node starts on, with the line break in front: the text a rule puts before a wrapped node.
val ASTNode.indent: String get() = "\n" + indentWithoutNewlinePrefix

// How many indents deep a whitespace's last line is, tabs counting as one indent.
fun indentLevelFrom(text: String): Int = text.substringAfterLast('\n').replace("\t", INDENT).length / INDENT.length

fun ASTNode.hasModifier(type: IElementType): Boolean =
    findChildByType(KtNodeTypes.MODIFIER_LIST)?.childNodes.orEmpty().any { it.elementType == type }

fun ASTNode.replaceTextWith(text: String) {
    require(this is LeafElement)
    if (this.text != text) rawReplaceWithText(text)
}

fun ASTNode.remove() {
    parent?.removeChild(this)
}

fun ASTNode.upsertWhitespaceBeforeMe(text: String) {
    if (isLeaf) {
        if (isWhiteSpace) return replaceTextWith(text)
        val previous = treePrev ?: prevLeaf
        when {
            previous != null && previous.isWhiteSpace -> previous.replaceTextWith(text)
            // never a whitespace as the first child of a composite
            parent?.firstChildNode == this -> parent?.upsertWhitespaceBeforeMe(text)
            else -> (this as LeafElement).rawInsertBeforeMe(PsiWhiteSpaceImpl(text))
        }
    } else {
        when (val prev = treePrev) {
            null -> parent?.upsertWhitespaceBeforeMe(text)
            is LeafElement -> prev.upsertWhitespaceAfterMe(text)
            else -> parent?.addChild(PsiWhiteSpaceImpl(text), this)
        }
    }
}

fun ASTNode.upsertWhitespaceAfterMe(text: String) {
    if (isLeaf) {
        if (isWhiteSpace) return replaceTextWith(text)
        val next = treeNext ?: nextLeaf
        when {
            next != null && next.isWhiteSpace -> next.replaceTextWith(text)
            // never a whitespace as the last child of a composite
            parent?.lastChildNode == this -> parent?.upsertWhitespaceAfterMe(text)
            else -> (this as LeafElement).rawInsertAfterMe(PsiWhiteSpaceImpl(text))
        }
    } else {
        when (val next = treeNext) {
            null -> parent?.upsertWhitespaceAfterMe(text)
            is LeafElement -> next.upsertWhitespaceBeforeMe(text)
            else -> parent?.addChild(PsiWhiteSpaceImpl(text), next)
        }
    }
}
