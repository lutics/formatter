package io.github.lutics.formatter.ide

import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange

// The IntelliJ Platform formatter's model (com.intellij.formatting, Apache 2.0): the blocks a language builds and the
// indent, wrap and spacing each block carries. Only what the Kotlin blocks use with the fleet scheme is here.

class Indent(
    val type: Type,
    val spaces: Int = 0,
    val relativeToDirectParent: Boolean = false,
    val enforceIndentToChildren: Boolean = false,
) {
    enum class Type { SPACES, NONE, NORMAL, CONTINUATION, CONTINUATION_WITHOUT_FIRST }

    companion object {
        val NONE = Indent(Type.NONE)
        val NORMAL = Indent(Type.NORMAL)
        val NORMAL_ENFORCED = Indent(Type.NORMAL, enforceIndentToChildren = true)
        val CONTINUATION = Indent(Type.CONTINUATION)
        val CONTINUATION_WITHOUT_FIRST = Indent(Type.CONTINUATION_WITHOUT_FIRST)
        val CONTINUATION_WITHOUT_FIRST_RELATIVE = Indent(Type.CONTINUATION_WITHOUT_FIRST, relativeToDirectParent = true)
    }
}

// A wrap is shared by the blocks it may break before; its state changes while the processor walks the leaves.
class Wrap(val type: Type, val wrapFirstElement: Boolean) {
    enum class Type { DO_NOT_WRAP, WRAP_AS_NEEDED, CHOP_IF_NEEDED, WRAP_ALWAYS }

    var chopStartBlock: LeafWrapper? = null
        private set
    var wrapOffset = -1
        private set
    var isActive = false
        private set
    private val parents = LinkedHashSet<Wrap>()
    private var ignoredWraps: MutableMap<Wrap?, MutableSet<LeafWrapper>>? = null

    private class Monitor(var left: Int, val fallback: Boolean)

    fun isChildOf(wrap: Wrap?, leaf: LeafWrapper?): Boolean = isChildOf(wrap, leaf, Monitor(MAX_IS_CHILD_OF_ITERATIONS, false))

    private fun isChildOf(wrap: Wrap?, leaf: LeafWrapper?, monitor: Monitor): Boolean {
        if (monitor.left-- <= 0) return monitor.fallback
        if (leaf != null && ignoredWraps?.get(wrap)?.contains(leaf) == true) return false
        for (parent in parents) {
            if (parent === wrap) return true
            if (parent.isChildOf(wrap, leaf, monitor)) return true
        }
        return false
    }

    fun registerParent(parent: Wrap?) {
        if (parent == null || parent === this) return
        if (parent.isChildOf(this, null, Monitor(MAX_IS_CHILD_OF_ITERATIONS, true))) return
        parents.add(parent)
    }

    fun ignoreParentWrap(wrap: Wrap?, leaf: LeafWrapper) {
        val map = ignoredWraps ?: HashMap<Wrap?, MutableSet<LeafWrapper>>().also { ignoredWraps = it }
        map.getOrPut(wrap) { HashSet() }.add(leaf)
    }

    fun setActive() {
        chopStartBlock = null
        isActive = true
    }

    fun setWrapOffset(offset: Int) {
        if (wrapOffset < 0) wrapOffset = offset
    }

    fun saveChopBlock(leaf: LeafWrapper) {
        if (chopStartBlock == null) chopStartBlock = leaf
    }

    private companion object {
        const val MAX_IS_CHILD_OF_ITERATIONS = 50
    }
}

// SpacingImpl: the white space allowed between two adjacent blocks.
open class Spacing(
    val minSpaces: Int,
    maxSpaces: Int,
    private val minLineFeedsField: Int,
    val keepLineBreaks: Boolean,
    keepBlankLines: Int,
    val keepFirstColumn: Boolean = false,
) {
    val maxSpaces = maxOf(minSpaces, maxSpaces)
    val keepBlankLines = if (minLineFeedsField > 1 && minLineFeedsField - 1 > keepBlankLines) minLineFeedsField - 1 else keepBlankLines

    open val minLineFeeds: Int get() = minLineFeedsField

    open fun refresh(ranges: BlockRangesMap) {}
}

// DependantSpacingImpl: line feeds that depend on whether some text ranges end up spanning lines. The Kotlin plugin's
// createLineFeedDependentSpacing is the one with line feeds even while the ranges stay on one line.
class DependentSpacing(
    minSpaces: Int,
    maxSpaces: Int,
    val ranges: List<TextRange>,
    keepLineBreaks: Boolean,
    keepBlankLines: Int,
    private val triggeredLineFeeds: Int = 1,
    private val otherwiseLineFeeds: Int = 0,
) : Spacing(minSpaces, maxSpaces, 0, keepLineBreaks, keepBlankLines) {
    private var dependenceContainsLineFeeds = false
    private var lastLineFeedChangedIteration = 0

    override val minLineFeeds: Int get() = if (dependenceContainsLineFeeds) triggeredLineFeeds else otherwiseLineFeeds

    val changedAtLeastOnce get() = lastLineFeedChangedIteration > 0

    override fun refresh(ranges: BlockRangesMap) {
        if (changedAtLeastOnce) return
        dependenceContainsLineFeeds = this.ranges.any { ranges.containsLineFeedsOrTooLong(it) }
    }

    fun changedInIteration(iteration: Int) = iteration == lastLineFeedChangedIteration

    fun setChangedInIteration(iteration: Int) {
        lastLineFeedChangedIteration = iteration
        dependenceContainsLineFeeds = minLineFeeds <= 0
    }
}

// A formatting block: a node or a synthetic group of blocks, with what its parent decided for it.
interface Block {
    val node: ASTNode
    val textRange: TextRange
    val subBlocks: List<Block>
    val wrap: Wrap?
    val indent: Indent?

    fun spacing(child1: Block?, child2: Block): Spacing?
}
