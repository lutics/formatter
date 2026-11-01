package io.github.lutics.formatter.text

import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.TextRule
import java.io.File

// AndroidManifest.xml (Messenger's is the reference): inside <application>, sections in SECTIONS order, each opened by
// its header comment after one blank line, a section the app does not have left out; <uses-permission>, the Meta Data
// section and the InitializationProvider's <meta-data> sort by android:name, each element into the slot (and the blank
// line) of another. A comment after an element on its line, or on the line(s) right above it, explains it and moves
// with it; a comment on the parent's start tag line stays there. Where the mapping is not certain the elements stay
// where they are and it is reported: an element no section names or a comment with a blank line under it leaves the
// sections alone; a comment right under a header may introduce the section or explain the element below it; and a
// comment above the first element of a run that sorting would reorder (now or once sorted) may explain the run as well
// as the element, and leaves that run unsorted. A run already in order, or of one element, moves nothing and is fine.
object ManifestSections : TextRule {
    override val id = "manifest-sections"

    // header → the elements it holds
    val SECTIONS = listOf(
        "Meta Data" to "meta-data",
        "Library" to "uses-library",
        "Provider" to "provider",
        "Service" to "service",
        "Receiver" to "receiver",
        "Activity Alias" to "activity-alias",
        "Activity" to "activity",
    )
    private val HEADERS = SECTIONS.map { it.first }.toSet()
    private const val NAME = "android:name"
    private const val INITIALIZATION_PROVIDER = "androidx.startup.InitializationProvider"

    override fun applies(path: String) = File(path).name == "AndroidManifest.xml"

    override fun format(path: String, text: String): String {
        val doc = Xml.parse(Xml.lf(text))
        val before = Xml.write(doc, valuesFile = false)
        rearrange(doc, mutableListOf())
        val after = Xml.write(doc, valuesFile = false)
        return if (after == before) text else after
    }

    override fun findings(path: String, text: String): List<Finding> = mutableListOf<Finding>().also { rearrange(Xml.parse(Xml.lf(text)), it) }

    private fun rearrange(doc: XDocument, problems: MutableList<Finding>) {
        val manifest = doc.root
        if (manifest.name != "manifest") return
        if (Xml.isBlockContent(manifest, valuesFile = false)) sortSlots(manifest, "uses-permission", problems)
        val application = manifest.elements.firstOrNull { it.name == "application" } ?: return
        if (!Xml.isBlockContent(application, valuesFile = false)) return
        application.elements.filter { it.name == "provider" && it.attr(NAME) == INITIALIZATION_PROVIDER && Xml.isBlockContent(it, valuesFile = false) }.forEach { sortSlots(it, "meta-data", problems) }
        sections(application, problems)
    }

    // A run of children: free comments, or an element with the comments right above it and after it on its line, with
    // the whitespace before each node (lead is the whitespace before the first).
    private class Piece(val lead: String, val nodes: List<Pair<String, XNode>>) {
        val element get() = nodes.firstNotNullOfOrNull { it.second as? XElement }
        val comments get() = nodes.map { it.second }.filterIsInstance<XRaw>()

        // the comments above the element
        val above get() = nodes.map { it.second }.takeWhile { it !is XElement }
        val header get() = (nodes.singleOrNull()?.second as? XRaw)?.takeIf { it.isComment && it.commentText in HEADERS }?.commentText

        operator fun plus(node: Pair<String, XNode>) = Piece(lead, nodes + node)

        fun withLead(ws: String) = Piece(ws, nodes)

        fun emit(out: MutableList<XNode>) {
            nodes.forEachIndexed { i, (ws, n) ->
                val w = if (i == 0) lead else ws
                if (w.isNotEmpty()) out += XText(w)
                out += n
            }
        }
    }

    // Splits children into pieces: a comment after an element on its line joins that element; a header comment is a
    // piece of its own; other comments join the element below them when no blank line separates them, and are free
    // otherwise. Comments on the parent's start tag line are pieces of their own, first, and stay there.
    private fun pieces(children: List<XNode>): Pair<List<Piece>, String> {
        val pieces = mutableListOf<Piece>()
        var pending = mutableListOf<Pair<String, XNode>>()
        var ws = ""
        var tagLine = true
        fun flushFree() {
            pending.forEach { pieces += Piece(it.first, listOf(it)) }
            pending = mutableListOf()
        }
        for (n in children) {
            if (n is XText) {
                ws += n.raw
                continue
            }
            if ('\n' in ws || n !is XRaw || !n.isComment) tagLine = false
            if (pending.isNotEmpty() && Xml.isBlank(ws)) flushFree()
            when {
                tagLine -> pieces += Piece(ws, listOf(ws to n))
                n is XRaw && n.isComment && '\n' !in ws && pending.isEmpty() && pieces.lastOrNull()?.element != null -> pieces[pieces.lastIndex] = pieces.last() + (ws to n)
                n is XRaw && n.isComment && n.commentText in HEADERS -> {
                    flushFree()
                    pieces += Piece(ws, listOf(ws to n))
                }
                n is XRaw -> pending += ws to n
                else -> {
                    val nodes = pending + (ws to n)
                    pieces += Piece(nodes.first().first, nodes)
                    pending = mutableListOf()
                }
            }
            ws = ""
        }
        flushFree()
        return pieces to ws
    }

    // A run sorted by android:name; the same list when sorting moves nothing (one element, or already in order).
    private fun sorted(run: List<Piece>) = run.sortedBy { it.element!!.attr(NAME).orEmpty() }

    // Whether a comment above the first element of a run that sorting reorders, now or once sorted, may explain the run
    // as well as the element; if so it is reported. Sorting it away from the top, or to it, would make format's own
    // output ambiguous.
    private fun runComment(run: List<Piece>, sorted: List<Piece>, problems: MutableList<Finding>): Boolean {
        val comments = setOf(run.first(), sorted.first()).mapNotNull { it.above.firstOrNull() }
        comments.forEach {
            problems += Finding(it.start, "This comment is above the <${run.first().element!!.name}> that comes first in a run that sorting by android:name would reorder: it may explain the run or only this element, so the run is left unsorted; put a blank line under it (the run's), move it after the element on its line (this element's), or move the comment directly above the element it explains.")
        }
        return comments.isNotEmpty()
    }

    private fun rebuild(parent: XElement, pieces: List<Piece>, trailing: String) {
        val out = mutableListOf<XNode>()
        pieces.forEach { it.emit(out) }
        if (trailing.isNotEmpty()) out += XText(trailing)
        parent.children = out
    }

    // Sorts the pieces holding a <tag> element by android:name, each into the slot (and the blank line) of another; not
    // when a comment above the first one, now or once sorted, may explain the whole run.
    private fun sortSlots(parent: XElement, tag: String, problems: MutableList<Finding>) {
        val (pieces, trailing) = pieces(parent.children)
        val slots = pieces.indices.filter { pieces[it].element?.name == tag }
        val run = slots.map { pieces[it] }
        val sorted = sorted(run)
        if (sorted == run || runComment(run, sorted, problems)) return
        val out = pieces.toMutableList()
        slots.forEachIndexed { k, slot -> out[slot] = sorted[k].withLead(pieces[slot].lead) }
        rebuild(parent, out, trailing)
    }

    private fun sections(application: XElement, problems: MutableList<Finding>) {
        val (pieces, trailing) = pieces(application.children)
        val ofTag = SECTIONS.associate { it.second to it.first }
        val head = pieces.takeWhile { it.element == null && '\n' !in it.lead && it.header == null }.toSet()
        // header → the comments right under it with a blank line below them, which introduce the section; its pieces in
        // document order
        val intros = mutableMapOf<String, MutableList<Piece>>()
        val members = mutableMapOf<String, MutableList<Piece>>()
        var header: String? = null
        var underHeader = false
        var afterIntro = false
        var ambiguous = false
        for (p in pieces) {
            if (p in head) continue
            val h = p.header
            if (h != null) {
                header = h
                underHeader = true
                afterIntro = false
                continue
            }
            val e = p.element
            if (e == null && underHeader && !Xml.isBlank(p.lead)) {
                intros.getOrPut(header!!) { mutableListOf() } += p
                afterIntro = true
                continue
            }
            if (e == null) {
                problems += Finding(p.comments.first().start, "A comment in <application> explains no element (a blank line or a header separates it from the next one); move it right above the element it explains.")
                ambiguous = true
                underHeader = false
                continue
            }
            val target = ofTag[e.name]
            if (target == null) {
                problems += Finding(e.start, "<${e.name}> belongs to no AndroidManifest section (${SECTIONS.joinToString { it.first }}); place it by hand.")
                ambiguous = true
                underHeader = false
                continue
            }
            // a comment right under a header and right above an element may introduce the section or explain the element:
            // it stays between them, which only holds while the element is its section's first and the section unsorted
            if (underHeader && !Xml.isBlank(p.lead) && p.above.isNotEmpty() && (header != target || members[target] != null)) {
                problems += Finding(p.above.first().start, "This comment is right under <!-- $header --> and right above <${e.name}>: it may introduce the section or explain the element; put a blank line under it (the section's) or above it (the element's).")
                ambiguous = true
            }
            // the blank line under an intro is the intro's, not a gap before this element
            members.getOrPut(target) { mutableListOf() } += if (afterIntro) p.withLead("\n") else p
            underHeader = false
            afterIntro = false
        }
        intros.keys.filter { it !in members }.forEach { h ->
            problems += Finding(intros.getValue(h).first().comments.first().start, "This comment introduces <!-- $h -->, which holds no element; move or remove it.")
            ambiguous = true
        }
        if (ambiguous) return
        // into the slots as sortSlots does: the whitespace before each position stays
        members["Meta Data"]?.let { list ->
            val sorted = sorted(list)
            if (sorted != list && !runComment(list, sorted, problems)) {
                val leads = list.map { it.lead }
                sorted.forEachIndexed { k, p -> list[k] = p.withLead(leads[k]) }
            }
        }
        val out = head.toMutableList<Piece>()
        for ((name, _) in SECTIONS) {
            val list = members[name] ?: continue
            out += Piece("\n\n", listOf("\n\n" to XRaw("<!-- $name -->", XRaw.Kind.COMMENT)))
            val intro = intros[name].orEmpty()
            intro.forEach { out += it.withLead("\n") }
            list.forEachIndexed { i, p ->
                out += p.withLead(if ((i > 0 && Xml.isBlank(p.lead)) || (i == 0 && intro.isNotEmpty())) "\n\n" else "\n")
            }
        }
        rebuild(application, out, trailing)
    }
}
