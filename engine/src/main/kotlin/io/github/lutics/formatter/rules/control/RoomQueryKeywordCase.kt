package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

// Room @Query SQL uses UPPERCASE keywords. Only words of KEYWORDS change: Room columns are camelCase, and :params,
// a.b qualifiers, quoted strings and identifiers, comments and $templates are never words of the SQL.
// Deliberate limit: `end` keeps its case even where an open CASE forces it, and a word glued to a non-literal operand
// (`" + T + "where`) is left alone.
object RoomQueryKeywordCase : Rule {
    override val id = "room-query-keyword-case"

    private val ROOM_QUERY = setOf("androidx.room3.Query", "androidx.room.Query")
    private val ROOM_PACKAGES = setOf("androidx.room3", "androidx.room")

    // SQLite's keywords that can never be a bare column, alias or table name (each fails `create table t(w)`,
    // `select 1 as w`, `select a w from x` and `select a from w` in sqlite3). Every other keyword of SQLite's list
    // (desc, asc, end, like, glob, replace, match, offset, left, inner, with, do, conflict, ...) is accepted as an
    // unquoted identifier, and changing an alias's case renames the result column Room maps to a field, so those keep
    // the case they were written in. None of these words is an SQLite function name, so `word (` needs no exception.
    private val KEYWORDS = setOf(
        "add", "all", "alter", "and", "as", "autoincrement", "between", "case", "check", "collate", "commit",
        "constraint", "create", "default", "deferrable", "delete", "distinct", "drop", "else", "escape", "except",
        "exists", "foreign", "from", "group", "having", "in", "index", "insert", "intersect", "into", "is", "join",
        "limit", "not", "nothing", "null", "on", "or", "order", "primary", "references", "returning", "select", "set",
        "table", "then", "to", "transaction", "union", "unique", "update", "using", "values", "when", "where",
    )

    // `by` is an identifier elsewhere, but right after ORDER or GROUP the grammar allows nothing else
    private val BY_AFTER = setOf("order", "group")

    // `asc`/`desc` right after a term of an ORDER BY clause can only be its sort order; after `BY` or `,` it is the term
    private val SORT_ORDER = setOf("asc", "desc")

    override fun edits(file: KtFile): List<Edit> {
        val imports = file.importDirectives.mapNotNull { it.importPath }
        val names = imports.filter { !it.isAllUnder && it.pathStr in ROOM_QUERY }.mapNotNull { it.importedName?.asString() }.toMutableSet()
        // `import androidx.room3.*` brings `Query` unless an explicit import of another `Query` shadows it
        val star = imports.any { it.isAllUnder && it.fqName.asString() in ROOM_PACKAGES }
        val shadowed = imports.any { !it.isAllUnder && it.pathStr !in ROOM_QUERY && it.importedName?.asString() == "Query" }
        if (star && !shadowed) names += "Query"
        return PsiTreeUtil.findChildrenOfType(file, KtAnnotationEntry::class.java)
            .filter { it.typeReference?.text in names || it.typeReference?.text in ROOM_QUERY }
            .flatMap { entry -> entry.valueArguments.mapNotNull { it.getArgumentExpression() }.flatMap { edits(pieces(it)) } }
    }

    // The operands of `"..."`, `"..." + x + "..."` and `("...")`, in source order: a string literal, or null for an
    // operand that is not one (a `const val` table name), which reads as an identifier character. Anything else is
    // not SQL text.
    private fun pieces(expression: KtExpression?): List<KtStringTemplateExpression?> = when (expression) {
        is KtStringTemplateExpression -> listOf(expression)
        is KtParenthesizedExpression -> pieces(expression.expression)
        is KtBinaryExpression -> if (expression.operationToken == KtTokens.PLUS) operand(expression.left) + operand(expression.right) else emptyList()
        else -> emptyList()
    }

    private fun operand(expression: KtExpression?): List<KtStringTemplateExpression?> = pieces(expression).ifEmpty { listOf(null) }

    // Scans the SQL as one text across the pieces; a template entry is an opaque identifier character, so a word it
    // touches is never changed, and quote and comment state runs through it.
    private fun edits(literals: List<KtStringTemplateExpression?>): List<Edit> {
        if (literals.none { it != null }) return emptyList()
        val out = mutableListOf<Edit>()
        var quote: Char? = null
        var lineComment = false
        var blockComment = false
        var prev = ' '
        var wordStart = -1
        val word = StringBuilder()
        var wordBlocked = false
        // the word still open when its piece ended; the next piece's first character decides whether it goes on
        var acrossPieces = false
        // the last keyword, while only whitespace has followed it
        var lastKeyword: String? = null
        var depth = 0
        // the paren depth of the ORDER BY clause being read; a `)` below it ends the clause
        var orderDepth: Int? = null
        // the last token ends a term (a column, :param, literal or `)`), not a keyword, `,` or `(`
        var afterTerm = false
        // the state before a `-` or `/`, put back when it turns out to open `--` or `/*`: a comment is not an operator
        var savedAfterTerm = false
        var savedLastKeyword: String? = null

        fun endWord() {
            if (wordStart >= 0) {
                val lower = word.toString().lowercase()
                val by = lower == "by" && lastKeyword in BY_AFTER
                val sortOrder = lower in SORT_ORDER && afterTerm && orderDepth == depth
                val keyword = !wordBlocked && (lower in KEYWORDS || by || sortOrder)
                if (keyword && word.toString() != lower.uppercase()) {
                    out += Edit(wordStart, wordStart + word.length, lower.uppercase())
                }
                if (keyword && by && lastKeyword == "order") orderDepth = depth
                lastKeyword = if (keyword) lower else null
                afterTerm = !keyword || sortOrder
            }
            wordStart = -1
            word.clear()
            wordBlocked = false
        }

        for (literal in literals) {
            if (wordStart >= 0) acrossPieces = true
            val entries = literal?.entries?.map { entry ->
                val text = when (entry) {
                    is KtLiteralStringTemplateEntry -> entry.text
                    is KtEscapeStringTemplateEntry -> entry.unescapedValue
                    // `$x` / `${x}` stands for an identifier character
                    else -> "\u0000"
                }
                Triple(text, entry.textRange.startOffset, entry is KtLiteralStringTemplateEntry)
            } ?: listOf(Triple("\u0000", 0, false))
            for ((text, base, editable) in entries) {
                for ((i, c) in text.withIndex()) {
                    val inWord = quote == null && !lineComment && !blockComment
                    val identifier = c == '_' || c == '\u0000' || c.isLetterOrDigit()
                    var opened = false
                    when {
                        lineComment -> if (c == '\n') lineComment = false
                        blockComment -> if (prev == '*' && c == '/') blockComment = false
                        quote != null -> if (c == quote || (quote == '[' && c == ']')) {
                            quote = null
                            afterTerm = true
                        }
                        inWord && identifier -> {
                            if (wordStart < 0) {
                                // `:param`, `@param`, `$param`, `a.b` and `x::type` are not keywords
                                wordBlocked = prev == ':' || prev == '@' || prev == '$' || prev == '.' || prev == '\u0000' || prev.isLetterOrDigit()
                                wordStart = if (editable) base + i else 0
                            }
                            // a word that goes on into the next `"..." +` piece has no single range to replace
                            if (acrossPieces || !editable || c == '\u0000') wordBlocked = true
                            word.append(c)
                        }
                        else -> {
                            // `a.b`: the word before a dot is a qualifier
                            if (c == '.') wordBlocked = true
                            endWord()
                            val commentOpener = (c == '-' && prev == '-') || (c == '*' && prev == '/')
                            if (commentOpener) {
                                afterTerm = savedAfterTerm
                                lastKeyword = savedLastKeyword
                            } else {
                                if (c == '-' || c == '/') {
                                    savedAfterTerm = afterTerm
                                    savedLastKeyword = lastKeyword
                                }
                                // any other operator (`a - desc`, `b / asc`, `||`) makes the next word an operand
                                if (!c.isWhitespace()) lastKeyword = null
                                if (c == '(') depth++
                                if (c == ')') depth--
                                if (orderDepth != null && depth < orderDepth) orderDepth = null
                                if (!c.isWhitespace()) afterTerm = c == ')'
                            }
                            when {
                                c == '\'' || c == '"' || c == '`' || c == '[' -> quote = c
                                c == '-' && prev == '-' -> lineComment = true
                                c == '*' && prev == '/' -> {
                                    blockComment = true
                                    opened = true
                                }
                            }
                        }
                    }
                    acrossPieces = false
                    // the `*` of `/*` cannot also close it (`/*/` is still open), and `*/` must not also open a new `/*`
                    prev = if (opened || (!blockComment && prev == '*' && c == '/')) ' ' else c
                }
            }
        }
        endWord()
        return out
    }
}
