package io.github.lutics.formatter.rules.function

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.rules.wrapcomma.childNodes
import io.github.lutics.formatter.rules.wrapcomma.isLeaf
import io.github.lutics.formatter.rules.wrapcomma.isWhiteSpace
import io.github.lutics.formatter.rules.wrapcomma.nextLeaf
import io.github.lutics.formatter.rules.wrapcomma.prevLeaf
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.psi.KtFile

// Every leaf under the node, empty ones included.
val ASTNode.leaves: List<ASTNode>
    get() = if (isLeaf) listOf(this) else childNodes.flatMap { it.leaves }.toList()

val ASTNode.start: Int get() = startOffset
val ASTNode.end: Int get() = startOffset + textLength

fun ASTNode.preOrder(): Sequence<ASTNode> = sequence {
    yield(this@preOrder)
    for (c in childNodes) yieldAll(c.preOrder())
}

// The indent of a node: what follows the last line break of the nearest preceding whitespace that holds one.
// from is where that whitespace starts: an edit at or after it changes the indent.
class Indent(val from: Int, val text: String)

fun indentAt(file: KtFile, text: String, offset: Int): Indent {
    var i = text.lastIndexOf('\n', offset - 1)
    while (i >= 0) {
        val leaf = file.findElementAt(i)?.node
        if (leaf.isWhiteSpace) return Indent(leaf!!.start, text.substring(i + 1, minOf(leaf.end, offset)))
        i = text.lastIndexOf('\n', i - 1)
    }
    return Indent(0, "")
}

// Whitespace changes to one tree, read back as in-place edits would be, and turned into Edits at the end.
// replace keys a leaf's range; insert keys an empty range at an offset.
class WsChanges {
    private val changes = sortedMapOf<Int, MutableList<Triple<Int, Int, String>>>()

    fun textOf(leaf: ASTNode): String =
        changes[leaf.start]?.lastOrNull { it.second == leaf.end && leaf.textLength > 0 }?.third ?: leaf.text

    fun replace(leaf: ASTNode, text: String) {
        val list = changes.getOrPut(leaf.start) { mutableListOf() }
        list.removeAll { it.second == leaf.end && it.second > it.first }
        if (text != leaf.text) list += Triple(leaf.start, leaf.end, text)
    }

    fun insert(offset: Int, text: String) {
        changes.getOrPut(offset) { mutableListOf() } += Triple(offset, offset, text)
    }

    // For a non-whitespace leaf: replace the adjacent whitespace, else add one.
    fun upsertBefore(node: ASTNode, text: String) {
        val prev = node.treePrev ?: node.prevLeaf
        if (prev.isWhiteSpace) replace(prev!!, text) else insert(node.start, text)
    }

    fun upsertAfter(node: ASTNode, text: String) {
        val next = node.treeNext ?: node.nextLeaf
        if (next.isWhiteSpace) replace(next!!, text) else insert(node.end, text)
    }

    fun edits(): List<Edit> = changes.values.mapNotNull { list ->
        if (list.isEmpty()) return@mapNotNull null
        // insertions go before a replacement starting at the same offset
        val ordered = list.sortedBy { it.second - it.first }
        Edit(ordered.first().first, ordered.maxOf { it.second }, ordered.joinToString("") { it.third })
    }
}

// Collects the edits of one pass over the nodes in tree order. the tree is edited as it is walked, so a node sees the
// edits made before it; here a node whose input [from, to] an earlier edit touched waits for the next pass, and so
// does everything after it.
class Pass {
    val edits = mutableListOf<Edit>()
    var stopped = false
        private set

    fun offer(from: Int, to: Int, nodeEdits: List<Edit>) {
        if (stopped || nodeEdits.isEmpty()) return
        val lo = minOf(from, nodeEdits.minOf { it.start })
        val hi = maxOf(to, nodeEdits.maxOf { it.end })
        if (edits.any { it.start <= hi && it.end >= lo }) {
            stopped = true
            return
        }
        edits += nodeEdits
    }
}
