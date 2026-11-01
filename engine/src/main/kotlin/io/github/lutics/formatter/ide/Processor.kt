package io.github.lutics.formatter.ide

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import java.util.TreeMap

// The IntelliJ Platform formatter's engine (com.intellij.formatting and .engine, Apache 2.0) for one whole file
// with the fleet's indent options (4 spaces, continuation 4, no tabs): wrap the blocks with the white space before each
// leaf, adjust every white space in document order (line feeds, wraps, dependent spacing, indent), then write the white
// space that changed. Alignment is left out: the Kotlin blocks align only parameters, and with this scheme a parameter
// list that spans lines has every parameter on its own line at the same indent already. No block makes its white space
// read-only or safe, and no indent is absolute.

private const val INDENT_SIZE = 4
private const val CONTINUATION_INDENT_SIZE = 4
private const val TAB_SIZE = 4

// the scheme's RIGHT_MARGIN and the formatter's MAX_RIGHT_MARGIN alike: no line is wrapped for length below it
private const val RIGHT_MARGIN = 1000

// IndentData: an indent in indent spaces plus alignment spaces. Nothing here goes negative, so neither is clamped.
class IndentData(val indentSpaces: Int, val spaces: Int = 0) {
    val isEmpty get() = indentSpaces == 0 && spaces == 0

    fun add(other: IndentData) = IndentData(indentSpaces + other.indentSpaces, spaces + other.spaces)

    fun add(ws: WhiteSpace) = IndentData(indentSpaces + ws.indentSpaces, spaces + ws.spaces)
}

// WhiteSpace: the text between two leaves, as line feeds plus the spaces of its last line.
class WhiteSpace(val start: Int, end: Int, initial: String, private val isFirst: Boolean) {
    var end = end
        private set
    private var initial = initial
    var spaces = 0
        private set
    var indentSpaces = 0
        private set
    var lineFeeds = 0
    var keepFirstColumn = false
    var containsLineFeedsInitially = false
        private set
    private var containsSpacesInitially = false

    init {
        parse()
    }

    // changeEndOffset: a block that starts after the end of its parent's white space extends it.
    fun extendTo(end: Int, initial: String) {
        this.end = end
        this.initial = initial
        parse()
    }

    private fun parse() {
        spaces = 0
        indentSpaces = 0
        lineFeeds = 0
        var column = 0
        for (c in initial) {
            when (c) {
                '\n' -> {
                    lineFeeds++
                    spaces = 0
                    indentSpaces = 0
                    column = 0
                }
                '\t' -> {
                    val change = TAB_SIZE - column % TAB_SIZE
                    indentSpaces += change
                    column += change
                }
                else -> {
                    spaces++
                    column++
                }
            }
        }
        containsLineFeedsInitially = lineFeeds > 0
        containsSpacesInitially = totalSpaces > 0
    }

    val totalSpaces get() = spaces + indentSpaces
    val textRange get() = TextRange(start, end)

    fun containsLineFeeds() = isFirst || lineFeeds > 0

    fun setSpaces(spaces: Int, indent: Int) {
        if (!keepFirstColumn || containsSpacesInitially) {
            this.spaces = spaces
            indentSpaces = indent
        }
    }

    fun arrangeSpaces(spacing: Spacing?) {
        if (spacing != null && lineFeeds == 0) {
            if (spacing.minSpaces >= 0 && totalSpaces < spacing.minSpaces) setSpaces(spacing.minSpaces, 0)
            if (spacing.maxSpaces >= 0 && totalSpaces > spacing.maxSpaces) setSpaces(spacing.maxSpaces, 0)
        }
    }

    fun arrangeLineFeeds(spacing: Spacing?, ranges: BlockRangesMap) {
        if (spacing != null) {
            spacing.refresh(ranges)
            if (spacing.minLineFeeds >= 0 && lineFeeds < spacing.minLineFeeds) lineFeeds = spacing.minLineFeeds
            if (lineFeeds > 0) {
                if (spacing.keepBlankLines > 0) {
                    if (lineFeeds >= spacing.keepBlankLines + 1) lineFeeds = spacing.keepBlankLines + 1
                } else if (lineFeeds > spacing.minLineFeeds) {
                    if (spacing.keepLineBreaks) {
                        lineFeeds = maxOf(spacing.minLineFeeds, 1)
                    } else {
                        lineFeeds = spacing.minLineFeeds
                        if (lineFeeds == 0) spaces = 0
                    }
                }
                if (lineFeeds == 1 && spacing.minLineFeeds == 0 && !spacing.keepLineBreaks) {
                    lineFeeds = 0
                    spaces = 0
                }
            }
        } else if (isFirst) {
            lineFeeds = 0
            spaces = 0
        }
    }

    fun ensureLineFeed() {
        if (!containsLineFeeds()) {
            lineFeeds = 1
            spaces = 0
        }
    }

    fun removeLineFeeds(spacing: Spacing?, ranges: BlockRangesMap) {
        lineFeeds = 0
        spaces = 0
        indentSpaces = 0
        arrangeLineFeeds(spacing, ranges)
        arrangeSpaces(spacing)
    }

    // IndentInfo.generateNewWhiteSpace without tabs; an empty line keeps no indent.
    fun generate(): String = "\n".repeat(lineFeeds) + " ".repeat(indentSpaces + spaces)

    fun changed() = generate() != initial
}

sealed class BlockWrapper(block: Block, val whiteSpace: WhiteSpace, var parent: CompositeWrapper?, textRange: TextRange) {
    var start = textRange.startOffset
        protected set
    var end = textRange.endOffset
        protected set
    val ownWrap: Wrap? = block.wrap
    lateinit var indent: Indent
    var canUseFirstChildIndentAsBlockIndent = true

    // The wraps of this block and of the parents that start where it does, the outermost first.
    fun wraps(): List<Wrap> {
        val result = ArrayList<Wrap>(3)
        var current: BlockWrapper? = this
        while (current != null && current.start == start) {
            val wrap = current.ownWrap
            if (wrap != null && result.none { it === wrap }) result.add(0, wrap)
            current = current.parent
        }
        return result
    }

    fun firstWrap(): Wrap? = wraps().firstOrNull()

    fun arrangeParentTextRange() {
        parent?.arrangeStartOffset(start)
    }

    protected fun arrangeStartOffset(offset: Int) {
        if (start == offset) return
        val p = parent
        val isFirst = p != null && start == p.start
        start = offset
        if (isFirst) p.arrangeStartOffset(offset)
    }

    abstract fun indentAlreadyUsedBefore(child: BlockWrapper): Boolean

    abstract fun numberOfSymbolsBeforeBlock(): IndentData

    // AbstractBlockWrapper.getChildOffset: the offset of a child that starts a line, from its parents' lines.
    fun childOffset(child: BlockWrapper, targetBlockStartOffset: Int): IndentData {
        val childStartsNewLine = child.whiteSpace.containsLineFeeds()
        val childIndentType = child.indent.type
        var childIndent: IndentData
        if (childStartsNewLine || (!whiteSpace.containsLineFeeds() && childIndentType in RELATIVE_INDENT_TYPES && indentAlreadyUsedBefore(child))) {
            childIndent = indentOf(child, targetBlockStartOffset)
        } else if (child.indent.enforceIndentToChildren && !child.whiteSpace.containsLineFeeds()) {
            childIndent = indentOf(child, start)
        } else {
            childIndent = IndentData(0)
        }

        if (childStartsNewLine && child.indent.relativeToDirectParent && child.start > start) return childIndent.add(numberOfSymbolsBeforeBlock())

        if (child.start == start) {
            canUseFirstChildIndentAsBlockIndent = canUseFirstChildIndentAsBlockIndent && child.canUseFirstChildIndentAsBlockIndent && childIndent.isEmpty
        }

        val p = parent
        return when {
            start == targetBlockStartOffset -> if (p == null) childIndent else childIndent.add(p.childOffset(this, targetBlockStartOffset))
            !whiteSpace.containsLineFeeds() -> childIndent.add(p!!.childOffset(this, targetBlockStartOffset))
            p == null || canUseFirstChildIndentAsBlockIndent -> childIndent.add(whiteSpace)
            else -> childIndent.add(p.childOffset(this, targetBlockStartOffset))
        }
    }

    // CoreFormatterUtil.getIndent
    private fun indentOf(block: BlockWrapper, tokenBlockStartOffset: Int): IndentData {
        val indent = block.indent
        return when (indent.type) {
            Indent.Type.CONTINUATION -> IndentData(CONTINUATION_INDENT_SIZE)
            Indent.Type.CONTINUATION_WITHOUT_FIRST ->
                if (block.start != block.parent!!.start && block.start == tokenBlockStartOffset) IndentData(CONTINUATION_INDENT_SIZE) else IndentData(0)
            Indent.Type.NONE -> IndentData(0)
            Indent.Type.SPACES -> IndentData(indent.spaces, 0)
            Indent.Type.NORMAL -> IndentData(INDENT_SIZE)
        }
    }

    private companion object {
        val RELATIVE_INDENT_TYPES = setOf(Indent.Type.NORMAL, Indent.Type.CONTINUATION, Indent.Type.CONTINUATION_WITHOUT_FIRST)
    }
}

class CompositeWrapper(block: Block, whiteSpace: WhiteSpace, parent: CompositeWrapper?) :
    BlockWrapper(block, whiteSpace, parent, block.textRange) {
    var children: List<BlockWrapper> = emptyList()

    override fun indentAlreadyUsedBefore(child: BlockWrapper): Boolean {
        for (before in children) {
            if (before === child) return false
            if (before.whiteSpace.containsLineFeeds()) return true
        }
        return false
    }

    override fun numberOfSymbolsBeforeBlock() = children.firstOrNull()?.numberOfSymbolsBeforeBlock() ?: IndentData(0, 0)
}

class LeafWrapper(
    block: Block,
    whiteSpace: WhiteSpace,
    parent: CompositeWrapper?,
    val previous: LeafWrapper?,
    text: CharSequence,
) : BlockWrapper(block, whiteSpace, parent, block.textRange) {
    var next: LeafWrapper? = null
    var spaceProperty: Spacing? = null
    val containsLineFeeds: Boolean
    val symbolsAtTheLastLine: Int

    init {
        val content = text.subSequence(start, end)
        val lastLf = content.lastIndexOf('\n')
        containsLineFeeds = lastLf >= 0
        var symbols = 0
        content.subSequence(lastLf + 1, content.length).codePoints().forEach {
            symbols += if (it == '\t'.code) TAB_SIZE else if (isFullwidth(it)) 2 else 1
        }
        symbolsAtTheLastLine = symbols
    }

    val length get() = end - start

    override fun indentAlreadyUsedBefore(child: BlockWrapper) = false

    override fun numberOfSymbolsBeforeBlock(): IndentData {
        var spaces = whiteSpace.spaces
        var indentSpaces = whiteSpace.indentSpaces
        if (whiteSpace.containsLineFeeds()) return IndentData(indentSpaces, spaces)
        var current = previous
        while (current != null) {
            spaces += current.whiteSpace.spaces
            spaces += current.symbolsAtTheLastLine
            indentSpaces += current.whiteSpace.indentSpaces
            if (current.whiteSpace.containsLineFeeds()) break
            current = current.previous
        }
        return IndentData(indentSpaces, spaces)
    }

    // LeafBlockWrapper.calculateOffset, set as the indent of the white space before this leaf
    fun indentFromParents() {
        val offset = parent?.childOffset(this, start) ?: IndentData(0)
        whiteSpace.setSpaces(offset.spaces, offset.indentSpaces)
    }

    private companion object {
        val FULLWIDTH = setOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
        )

        fun isFullwidth(codePoint: Int) = Character.isValidCodePoint(codePoint) && Character.UnicodeBlock.of(codePoint) in FULLWIDTH
    }
}

// BlockRangesMap: whether a range spans lines (or one line longer than the formatter's margin limit).
class BlockRangesMap(first: LeafWrapper?) {
    private val byStart = HashMap<Int, LeafWrapper>()

    init {
        var current = first
        while (current != null) {
            byStart[current.start] = current
            current = current.next
        }
    }

    fun containsLineFeedsOrTooLong(range: TextRange): Boolean {
        var child = byStart[range.startOffset] ?: return false
        val endOffset = range.endOffset
        val startOffset = child.start
        while (child.end < endOffset) {
            if (child.containsLineFeeds || child.start - startOffset > RIGHT_MARGIN) return true
            child = child.next ?: return false
            if (child.whiteSpace.end <= endOffset && child.whiteSpace.containsLineFeeds()) return true
        }
        return false
    }
}

class FormatProcessor(private val text: String, root: Block) {
    private var first: LeafWrapper? = null
    private val ranges: BlockRangesMap

    init {
        Builder().buildFrom(root, 0, null, null)
        ranges = BlockRangesMap(first)
    }

    // InitialInfoBuilder: one wrapper per block, each leaf owning the white space before it, its spacing taken from the
    // lowest parent in which that white space separates two sibling blocks.
    private inner class Builder {
        private var currentWhiteSpaceStart = 0
        private var isFirstWhiteSpace = true
        private var spaceProperty: Spacing? = null
        private var previous: LeafWrapper? = null
        private var pendingWhiteSpace: WhiteSpace? = null

        private fun whiteSpaceUpTo(offset: Int): WhiteSpace {
            val pending = pendingWhiteSpace
            if (pending != null) {
                if (offset > pending.end) pending.extendTo(offset, text.substring(pending.start, offset))
                return pending
            }
            val ws = WhiteSpace(currentWhiteSpaceStart, offset, text.substring(currentWhiteSpaceStart, offset), isFirstWhiteSpace)
            pendingWhiteSpace = ws
            return ws
        }

        fun buildFrom(block: Block, index: Int, parent: CompositeWrapper?, wrapParent: Wrap?): BlockWrapper {
            var currentWrapParent = wrapParent
            block.wrap?.let {
                it.registerParent(currentWrapParent)
                currentWrapParent = it
            }
            val ws = whiteSpaceUpTo(block.textRange.startOffset)
            val subBlocks = block.subBlocks
            if (subBlocks.isEmpty()) return buildLeaf(block, index, parent, ws)
            val composite = CompositeWrapper(block, ws, parent)
            if (parent == null) composite.indent = Indent.NONE
            if (index == 0) composite.arrangeParentTextRange()
            val children = ArrayList<BlockWrapper>(subBlocks.size)
            var previousBlock: Block? = null
            for ((i, child) in subBlocks.withIndex()) {
                if (previousBlock != null || isFirstWhiteSpace) spaceProperty = block.spacing(previousBlock, child)
                val wrapper = buildFrom(child, i, composite, currentWrapParent)
                wrapper.indent = child.indent ?: Indent.CONTINUATION_WITHOUT_FIRST
                children.add(wrapper)
                previousBlock = child
            }
            composite.children = children
            return composite
        }

        private fun buildLeaf(block: Block, index: Int, parent: CompositeWrapper?, ws: WhiteSpace): LeafWrapper {
            val leaf = LeafWrapper(block, ws, parent, previous, text)
            if (index == 0) leaf.arrangeParentTextRange()
            previous?.next = leaf
            if (first == null) first = leaf
            spaceProperty?.let { ws.keepFirstColumn = it.keepFirstColumn }
            leaf.spaceProperty = spaceProperty
            currentWhiteSpaceStart = block.textRange.endOffset
            isFirstWhiteSpace = false
            pendingWhiteSpace = null
            previous = leaf
            return leaf
        }
    }

    // AdjustWhiteSpacesState with WrapProcessor, IndentAdjuster and DependentSpacingEngine.
    fun process() {
        val root = first ?: return
        var iteration = 1
        val dependencies = TreeMap<TextRange, LeafWrapper>(compareBy<TextRange> { it.endOffset }.thenByDescending { it.startOffset })
        val toReformat = LinkedHashSet<LeafWrapper>()
        val alignAgain = HashSet<WhiteSpace>()
        val wraps = WrapState()
        var current: LeafWrapper? = root
        while (true) {
            while (current != null) {
                val leaf: LeafWrapper = current
                val spacing = leaf.spaceProperty
                val ws = leaf.whiteSpace
                updateSpacing(spacing, ws)
                val restart = try {
                    wraps.processWrap(leaf)
                } finally {
                    if (ws.containsLineFeeds()) wraps.onCurrentLineChanged()
                }
                if (restart != null) {
                    current = restart
                    continue
                }
                adjustSpaces(leaf)
                if (leaf.containsLineFeeds) wraps.onCurrentLineChanged()
                if (spacing is DependentSpacing && !spacing.changedInIteration(iteration)) {
                    for (range in spacing.ranges) if (ws.start < range.endOffset) dependencies[range] = leaf
                }
                if (shouldReformatPreviouslyLocatedDependentSpacing(ws, dependencies, iteration, toReformat)) {
                    alignAgain.add(ws)
                } else if (alignAgain.isNotEmpty()) {
                    alignAgain.remove(ws)
                }
                current = leaf.next
            }
            if (alignAgain.isEmpty()) break
            alignAgain.clear()
            dependencies.clear()
            current = root
            iteration++
        }
        for (leaf in toReformat) {
            updateSpacing(leaf.spaceProperty, leaf.whiteSpace)
            adjustSpaces(leaf)
        }
    }

    private fun updateSpacing(spacing: Spacing?, ws: WhiteSpace) {
        ws.arrangeLineFeeds(spacing, ranges)
        if (!ws.containsLineFeeds()) ws.arrangeSpaces(spacing)
    }

    private fun adjustSpaces(leaf: LeafWrapper) {
        if (leaf.whiteSpace.containsLineFeeds()) leaf.indentFromParents() else leaf.whiteSpace.arrangeSpaces(leaf.spaceProperty)
    }

    private fun shouldReformatPreviouslyLocatedDependentSpacing(
        ws: WhiteSpace,
        dependencies: TreeMap<TextRange, LeafWrapper>,
        iteration: Int,
        toReformat: MutableSet<LeafWrapper>,
    ): Boolean {
        val changed = ws.textRange
        for ((range, block) in dependencies.tailMap(changed, true)) {
            if (!range.contains(changed)) continue
            val spacing = block.spaceProperty as? DependentSpacing ?: continue
            if (spacing.changedInIteration(iteration)) continue
            val containedLineFeeds = spacing.minLineFeeds > 0
            val containsLineFeeds = ranges.containsLineFeedsOrTooLong(range)
            if (containedLineFeeds != containsLineFeeds) {
                if (!spacing.changedAtLeastOnce) {
                    spacing.setChangedInIteration(iteration)
                    return true
                } else if (containsLineFeeds) {
                    spacing.setChangedInIteration(iteration)
                    toReformat.add(block)
                    return false
                }
            }
        }
        return false
    }

    // WrapProcessor
    private inner class WrapState {
        private var firstWrappedBlockOnLine: LeafWrapper? = null
        private var wrapCandidate: LeafWrapper? = null

        fun onCurrentLineChanged() {
            wrapCandidate = null
        }

        private fun isSuitableInTheCurrentPosition(wrap: Wrap, block: LeafWrapper): Boolean {
            if (wrap.wrapOffset < block.start) return true
            if (wrap.wrapFirstElement) return true
            if (wrap.type == Wrap.Type.WRAP_AS_NEEDED) return positionAfterWrappingIsSuitable(block)
            return wrap.type == Wrap.Type.CHOP_IF_NEEDED && lineOver(block) && positionAfterWrappingIsSuitable(block)
        }

        private fun lineOver(block: LeafWrapper) = !block.containsLineFeeds && startColumn(block) + block.length > RIGHT_MARGIN

        private fun positionAfterWrappingIsSuitable(block: LeafWrapper): Boolean {
            val ws = block.whiteSpace
            if (ws.containsLineFeeds()) return true
            val spaces = ws.spaces
            val indentSpaces = ws.indentSpaces
            try {
                val before = startColumn(block)
                ws.ensureLineFeed()
                block.indentFromParents()
                return before > startColumn(block)
            } finally {
                ws.removeLineFeeds(block.spaceProperty, ranges)
                ws.setSpaces(spaces, indentSpaces)
            }
        }

        private fun wrapToBeUsed(wraps: List<Wrap>, block: LeafWrapper): Wrap? {
            if (wraps.isEmpty()) return null
            if (wrapCandidate === block) return wraps[0]
            for (wrap in wraps) {
                if (!isSuitableInTheCurrentPosition(wrap, block)) continue
                if (wrap.isActive) return wrap
                when (wrap.type) {
                    Wrap.Type.WRAP_ALWAYS -> return wrap
                    Wrap.Type.WRAP_AS_NEEDED, Wrap.Type.CHOP_IF_NEEDED -> if (lineOver(block)) return wrap
                    else -> {}
                }
            }
            return null
        }

        private fun isCandidateToBeWrapped(wrap: Wrap, block: LeafWrapper) =
            isSuitableInTheCurrentPosition(wrap, block) && (wrap.type == Wrap.Type.WRAP_AS_NEEDED || wrap.type == Wrap.Type.CHOP_IF_NEEDED)

        private fun canReplaceWrapCandidate(wrap: Wrap, block: LeafWrapper): Boolean {
            val candidate = wrapCandidate ?: return true
            if (wrap.isActive && (wrap.type == Wrap.Type.CHOP_IF_NEEDED || wrap.type == Wrap.Type.WRAP_ALWAYS)) return true
            val currentWrap = candidate.firstWrap()
            return wrap === currentWrap || !wrap.isChildOf(currentWrap, block)
        }

        private fun isChopNeeded(wrap: Wrap?, block: LeafWrapper) =
            wrap != null && wrap.type == Wrap.Type.CHOP_IF_NEEDED && isSuitableInTheCurrentPosition(wrap, block)

        fun processWrap(block: LeafWrapper): LeafWrapper? {
            val spacing = block.spaceProperty
            val ws = block.whiteSpace
            val wrapWasPresent = ws.containsLineFeeds()
            if (wrapWasPresent) {
                firstWrappedBlockOnLine = null
                if (!ws.containsLineFeedsInitially) ws.removeLineFeeds(spacing, ranges)
            }
            val wrapIsPresent = ws.containsLineFeeds()
            val wraps = block.wraps()
            for (wrap in wraps) wrap.setWrapOffset(block.start)
            val wrap = wrapToBeUsed(wraps, block)
            if (wrap != null || wrapIsPresent) {
                if (!wrapIsPresent && !canReplaceWrapCandidate(wrap!!, block)) return wrapCandidate
                if (wrap != null && wrap.chopStartBlock != null) {
                    val restart = wrap.chopStartBlock
                    wrap.setActive()
                    return restart
                }
                if (wrap != null && isChopNeeded(wrap, block)) wrap.setActive()
                if (!wrapIsPresent) {
                    ws.ensureLineFeed()
                    if (!wrapWasPresent) {
                        val firstOnLine = firstWrappedBlockOnLine
                        if (firstOnLine != null && wrap!!.isChildOf(firstOnLine.firstWrap(), block)) {
                            wrap.ignoreParentWrap(firstOnLine.firstWrap(), block)
                            return firstOnLine
                        } else {
                            firstWrappedBlockOnLine = block
                        }
                    }
                }
                wrapCandidate = null
            } else {
                for (w in wraps) {
                    if (isCandidateToBeWrapped(w, block) && canReplaceWrapCandidate(w, block)) wrapCandidate = block
                    if (isChopNeeded(w, block)) w.saveChopBlock(block)
                }
            }
            if (!ws.containsLineFeeds() && wrapCandidate != null && lineOver(block)) return wrapCandidate
            return null
        }
    }

    // CoreFormatterUtil.getStartColumn
    private fun startColumn(block: LeafWrapper): Int {
        var current: LeafWrapper = block
        var result = 0
        while (true) {
            val ws = current.whiteSpace
            result += ws.totalSpaces
            if (ws.containsLineFeeds()) return result
            current = current.previous ?: return result
            if (result > RIGHT_MARGIN) return result
            result += current.symbolsAtTheLastLine
            if (current.containsLineFeeds) return result
        }
    }

    // ApplyChangesState: the text with every changed white space replaced.
    fun result(): String {
        val sb = StringBuilder(text.length + 1024)
        var at = 0
        var leaf = first
        while (leaf != null) {
            val ws = leaf.whiteSpace
            if (ws.changed()) {
                sb.append(text, at, ws.start).append(ws.generate())
                at = ws.end
            }
            leaf = leaf.next
        }
        sb.append(text, at, text.length)
        return sb.toString()
    }
}
