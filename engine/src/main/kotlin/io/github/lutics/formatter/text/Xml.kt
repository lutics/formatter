package io.github.lutics.formatter.text

import io.github.lutics.formatter.TextRule
import java.io.File

// Android XML (manifests and the res/ files aapt compiles) in Android Studio's layout, the one the fleet follows: LF line
// endings; 4-space indent by depth; an element with 2+ attributes puts each on its own line at +4, except that a root's
// first xmlns stays on the element's line; one attribute stays on the line; res/values* files keep every element's
// attributes on one line and lay out only their containers; a self-closing tag ends in " />". Only whitespace between
// markup and inside tags moves: comments, CDATA, text and attribute values keep their bytes (line endings aside, which
// an XML parser normalizes too), and attributes keep their order.
object XmlLayout : TextRule {
    override val id = "xml-layout"

    // The res/ directory types aapt compiles as XML; res/raw* and anything else is read by the app as bytes. The plugin
    // builds its res glob from this const, which the compiler inlines: the engine is not on the plugin's classpath.
    const val RES_TYPES = "anim animator color drawable font interpolator layout menu mipmap navigation transition values xml"

    private val TYPES = RES_TYPES.split(' ').toSet()

    override fun applies(path: String) = isAndroidXml(path)

    override fun format(path: String, text: String): String = Xml.write(Xml.parse(Xml.lf(text)), valuesFile = isValuesFile(path))

    fun isAndroidXml(path: String): Boolean {
        val f = File(path)
        if (f.extension != "xml") return false
        if (f.name == "AndroidManifest.xml") return true
        // res/<type>[-qualifiers]/<name>.xml
        val dir = f.parentFile ?: return false
        return dir.parentFile?.name == "res" && dir.name.substringBefore('-') in TYPES
    }

    fun isValuesFile(path: String): Boolean {
        val dir = File(path).parentFile?.name ?: return false
        return dir == "values" || dir.startsWith("values-")
    }
}

sealed class XNode {
    abstract val start: Int
}

// Text between markup, byte for byte.
class XText(val raw: String, override val start: Int = -1) : XNode()

// A comment, CDATA section, processing instruction or doctype, byte for byte.
class XRaw(val raw: String, val kind: Kind, override val start: Int = -1) : XNode() {
    enum class Kind { COMMENT, CDATA, PI, DOCTYPE }

    val isComment get() = kind == Kind.COMMENT

    // A comment's text without its delimiters, trimmed.
    val commentText get() = raw.removePrefix("<!--").removeSuffix("-->").trim()
}

// name="value" with the value's quotes and bytes as written; the whitespace around '=' is not kept.
class XAttr(val name: String, val raw: String) {
    val value get() = raw.substring(raw.indexOf('=') + 2, raw.length - 1)
}

class XElement(
    val name: String,
    val attrs: List<XAttr>,
    val selfClosing: Boolean,
    override val start: Int,
) : XNode() {

    var children: MutableList<XNode> = mutableListOf()

    // The source between the start tag and the end tag, written back as is when the content is not laid out in lines.
    var contentRaw: String = ""

    fun attr(name: String) = attrs.firstOrNull { it.name == name }?.value

    val elements get() = children.filterIsInstance<XElement>()
}

class XDocument(val bom: Boolean, val children: MutableList<XNode>) {
    val root get() = children.filterIsInstance<XElement>().first()
}

class XmlSyntaxError(message: String) : Exception(message)

object Xml {

    // The res/values elements that hold other elements; every other element there (string, item, dimen, color, …) is a
    // value, whose content keeps its bytes.
    private val VALUES_CONTAINERS = setOf("resources", "string-array", "integer-array", "array", "plurals", "style", "declare-styleable", "attr")

    // CRLF and CR to LF, as an XML parser reads them.
    fun lf(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

    fun parse(text: String): XDocument {
        val bom = text.startsWith('﻿')
        val top = mutableListOf<XNode>()
        val stack = ArrayDeque<Pair<XElement, Int>>()
        fun add(n: XNode) = (stack.lastOrNull()?.first?.children ?: top).add(n)
        var i = if (bom) 1 else 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) {
                add(XText(text.substring(i), i))
                break
            }
            if (lt > i) add(XText(text.substring(i, lt), i))
            i = when {
                text.startsWith("<!--", lt) -> raw(text, lt, "-->", XRaw.Kind.COMMENT, ::add)
                text.startsWith("<![CDATA[", lt) -> raw(text, lt, "]]>", XRaw.Kind.CDATA, ::add)
                text.startsWith("<?", lt) -> raw(text, lt, "?>", XRaw.Kind.PI, ::add)
                text.startsWith("<!", lt) -> {
                    // a doctype's internal subset holds '>' of its own
                    val bracket = text.indexOf('[', lt)
                    val gt = text.indexOf('>', lt)
                    raw(text, lt, if (bracket in 0 until gt) "]>" else ">", XRaw.Kind.DOCTYPE, ::add)
                }
                text.startsWith("</", lt) -> {
                    val gt = text.indexOf('>', lt)
                    if (gt < 0) throw XmlSyntaxError("unclosed end tag at $lt")
                    val name = text.substring(lt + 2, gt).trim()
                    val (open, contentStart) = stack.removeLastOrNull() ?: throw XmlSyntaxError("</$name> with no open element at $lt")
                    if (open.name != name) throw XmlSyntaxError("</$name> closes <${open.name}> at $lt")
                    open.contentRaw = text.substring(contentStart, lt)
                    gt + 1
                }
                else -> {
                    val (element, end) = startTag(text, lt)
                    add(element)
                    if (!element.selfClosing) stack.addLast(element to end)
                    end
                }
            }
        }
        stack.lastOrNull()?.let { throw XmlSyntaxError("<${it.first.name}> at ${it.first.start} is never closed") }
        top.forEach { if (it is XText && it.raw.isNotBlank()) throw XmlSyntaxError("text outside the root element at ${it.start}") }
        if (top.none { it is XElement }) throw XmlSyntaxError("no root element")
        return XDocument(bom, top)
    }

    private fun raw(text: String, at: Int, close: String, kind: XRaw.Kind, add: (XNode) -> Unit): Int {
        val end = text.indexOf(close, at + 2)
        if (end < 0) throw XmlSyntaxError("unclosed ${kind.name.lowercase()} at $at")
        add(XRaw(text.substring(at, end + close.length), kind, at))
        return end + close.length
    }

    private fun startTag(text: String, lt: Int): Pair<XElement, Int> {
        var j = lt + 1
        fun stop(c: Char) = c.isWhitespace() || c == '/' || c == '>' || c == '='
        val nameStart = j
        while (j < text.length && !stop(text[j])) j++
        val name = text.substring(nameStart, j)
        if (name.isEmpty()) throw XmlSyntaxError("element with no name at $lt")
        val attrs = mutableListOf<XAttr>()
        while (true) {
            while (j < text.length && text[j].isWhitespace()) j++
            if (j >= text.length) throw XmlSyntaxError("unclosed <$name> at $lt")
            when {
                text.startsWith("/>", j) -> return XElement(name, attrs, selfClosing = true, start = lt) to j + 2
                text[j] == '>' -> return XElement(name, attrs, selfClosing = false, start = lt) to j + 1
            }
            val attrStart = j
            while (j < text.length && !stop(text[j])) j++
            val attr = text.substring(attrStart, j)
            while (j < text.length && text[j].isWhitespace()) j++
            if (attr.isEmpty() || j >= text.length || text[j] != '=') throw XmlSyntaxError("attribute with no value in <$name> at $attrStart")
            j++
            while (j < text.length && text[j].isWhitespace()) j++
            val quote = text.getOrNull(j)
            if (quote != '"' && quote != '\'') throw XmlSyntaxError("unquoted attribute value in <$name> at $j")
            val close = text.indexOf(quote, j + 1)
            if (close < 0) throw XmlSyntaxError("unclosed attribute value in <$name> at $j")
            attrs += XAttr(attr, attr + "=" + text.substring(j, close + 1))
            j = close + 1
        }
    }

    fun write(doc: XDocument, valuesFile: Boolean): String {
        val w = Writer(valuesFile, doc.root)
        if (doc.bom) w.sb.append('﻿')
        w.lines(doc.children, depth = 0, topLevel = true)
        w.sb.append('\n')
        return w.sb.toString()
    }

    // Whitespace that holds a blank line.
    fun isBlank(ws: String) = ws.count { it == '\n' } >= 2

    private class Writer(val valuesFile: Boolean, val root: XElement) {
        val sb = StringBuilder()

        fun indent(depth: Int) = "    ".repeat(depth)

        // Children laid out one per line, keeping one blank line where the source had any, and a comment that sat on
        // the line of the node before it, or of the parent's start tag, on that line. At the top level the trailing whitespace is dropped (the file's
        // final newline is the caller's); in an element it decides the blank line before the end tag.
        fun lines(children: List<XNode>, depth: Int, topLevel: Boolean): String {
            var ws = ""
            var first = true
            for (n in children) {
                if (n is XText) {
                    ws += n.raw
                    continue
                }
                if ((!first || !topLevel) && n is XRaw && n.isComment && '\n' !in ws) {
                    sb.append(' ')
                } else {
                    if (!topLevel || !first) sb.append('\n')
                    if (isBlank(ws) && (!topLevel || !first)) sb.append('\n')
                    sb.append(indent(depth))
                }
                node(n, depth)
                ws = ""
                first = false
            }
            return ws
        }

        fun node(n: XNode, depth: Int) {
            when (n) {
                is XText -> sb.append(n.raw)
                is XRaw -> sb.append(n.raw)
                is XElement -> element(n, depth)
            }
        }

        fun element(e: XElement, depth: Int) {
            sb.append('<').append(e.name)
            val attrs = e.attrs
            if (valuesFile || attrs.size == 1) {
                attrs.forEach { sb.append(' ').append(it.raw) }
            } else if (attrs.isNotEmpty()) {
                val onLine = if (e === root && attrs[0].name.startsWith("xmlns")) 1 else 0
                attrs.take(onLine).forEach { sb.append(' ').append(it.raw) }
                attrs.drop(onLine).forEach { sb.append('\n').append(indent(depth + 1)).append(it.raw) }
            }
            if (e.selfClosing) {
                sb.append(" />")
                return
            }
            sb.append('>')
            if (isBlockContent(e, valuesFile)) {
                val trailing = lines(e.children, depth + 1, topLevel = false)
                sb.append('\n')
                if (isBlank(trailing)) sb.append('\n')
                sb.append(indent(depth))
            } else if ('\n' in e.contentRaw && e.contentRaw.isBlank() && (!valuesFile || e.name in VALUES_CONTAINERS)) {
                // whitespace-only content over lines: the end tag on a line of its own at the element's indent
                sb.append('\n').append(indent(depth))
            } else {
                sb.append(e.contentRaw)
            }
            sb.append("</").append(e.name).append('>')
        }
    }

    // Content laid out in lines: child elements or comments with only whitespace between them. Anything else (text,
    // CDATA) is written back as it is. In res/values only a container is laid out, since every other element's content
    // is a value: <string><xliff:g …>%d</xliff:g></string> is the string, not a layout.
    fun isBlockContent(e: XElement, valuesFile: Boolean): Boolean {
        if (e.selfClosing) return false
        if (valuesFile && e.name !in VALUES_CONTAINERS) return false
        if (e.children.none { it is XElement || (it is XRaw && (it.isComment || it.kind == XRaw.Kind.PI)) }) return false
        return e.children.none { (it is XText && it.raw.isNotBlank()) || (it is XRaw && it.kind == XRaw.Kind.CDATA) }
    }
}
