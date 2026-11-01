package io.github.lutics.formatter.rules.control

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.ValueArgument

// Backend embedded SQL uses lowercase keywords. A string is a candidate only because it reaches a Postgres sink: the
// argument of r2dbc's @Query, Connection.createStatement, a JdbcTemplate or DatabaseClient call, directly or through a
// same-file val/const passed by name. Room's @Query (UPPERCASE) is told apart by the imports, since the rule never sees
// the file's path. The rule has no type resolution, so where a name, a receiver or a quote cannot be decided from the
// file it makes no edit.
// Name resolution from syntax alone still guesses (inherited members, labelled scope lambdas, same-package classes beside
// a star import), so two nets stand behind it and either one alone keeps a non-SQL or BigQuery string as written:
// - Content: each carrier (the literal text written in the sink, and each followed constant on its own) is edited only
//   when, templates stripped, it opens (past whitespace, comments and parentheses) with a statement verb and holds two
//   keywords, one of them a clause word. Real SQL without a clause word (`SELECT 1`, `SELECT $$x$$ AS label`, `DO $$ … $$`)
//   is therefore left as written, and so is a `+` piece that does not open the statement: a statement split across a
//   followed constant and a literal is edited only in the carrier that opens it.
// - BigQuery (UPPERCASE by the operator's decision): in a file with a QueryJobConfiguration call or a
//   com.google.cloud.bigquery import, no constant is followed at all; only a literal written in the sink call changes.
//   The file is all the rule sees, so a constant that reaches BigQuery from another file is not seen.
object SqlLowercaseKeywords : Rule {
    override val id = "sql-lowercase-keywords"

    private const val R2DBC_QUERY = "org.springframework.data.r2dbc.repository.Query"
    private const val R2DBC_PACKAGE = "org.springframework.data.r2dbc.repository"
    private val JDBC_TYPES = setOf("JdbcTemplate", "NamedParameterJdbcTemplate")
    private val JDBC_IMPORTS = setOf("org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
    private val JDBC_METHODS = setOf("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute")
    private const val DATABASE_CLIENT = "org.springframework.r2dbc.core.DatabaseClient"
    private const val BIGQUERY_PACKAGE = "com.google.cloud.bigquery."

    // The content net: a carrier opens with one of these and holds one of the clause words.
    private val SQL_VERBS = setOf("select", "insert", "update", "delete", "with", "create", "alter", "drop", "truncate", "grant", "merge")
    private val SQL_CLAUSES = setOf("from", "into", "set", "where", "table", "values", "returning")

    // The stdlib scope functions whose lambda runs with a receiver as `this`, so a bare name in it may be that receiver's member.
    private val RECEIVER_SCOPES = setOf("with", "apply", "run")

    private val KEYWORDS = (
        "select from where and or not insert into values update set delete order by group having limit offset join left right inner outer " +
            "cross on as asc desc null is in like ilike between case when then else end exists distinct union returning conflict with using " +
            "all do nothing nulls first last interval true false cast any over partition filter lateral array ordinality recursive except " +
            "intersect create table alter drop if index primary key references default unique constraint cascade truncate only for skip locked nowait"
        ).split(" ").toSet()

    // Function names are lowercased only as a call (the word followed by `(`), so a column that shares the name is left alone.
    // They count as keywords because the fleet's backend SQL already writes them lowercase (396 to 11, measured 2026-10-29).
    private val FUNCTIONS = (
        "count sum max min avg coalesce greatest least nullif now lower upper row_number string_agg array_agg jsonb_build_object " +
            "json_agg jsonb_agg date_trunc extract abs round substr length trim unnest"
        ).split(" ").toSet()

    // A character that stands for something the rule cannot read: a template entry or a non-string operand.
    private const val OPAQUE = '\u0000'

    // What resolve() returns for a name it cannot decide: a receiver's member, an inherited one, or two same-named declarations.
    private object Undecided

    override fun edits(file: KtFile): List<Edit> = scan(file).mapNotNull { it.edit }

    override fun findings(file: KtFile): List<Finding> =
        scan(file).filter { it.edit == null }.map { Finding(it.offset, "SQL keyword '${it.word}' is not lowercase and ${it.reason}, so it is left for a person") }

    private class Hit(val offset: Int, val word: String, val edit: Edit?, val carrier: Int, val reason: String = "touches a template or escape")

    private fun scan(file: KtFile): List<Hit> {
        val imports = file.importDirectives.mapNotNull { it.importPath?.pathStr }.toSet()
        val sinks = mutableListOf<KtExpression>()
        var bigQuery = false

        val queryNames = r2dbcQueryNames(file)
        for (entry in PsiTreeUtil.findChildrenOfType(file, KtAnnotationEntry::class.java)) {
            val type = entry.typeReference?.text ?: continue
            val r2dbc = if ('.' in type) type == R2DBC_QUERY else type in queryNames
            if (r2dbc) entry.valueArguments.sqlArgument(name = "value")?.let { sinks += it }
        }
        val jdbc = imports.any { it in JDBC_IMPORTS }
        val databaseClient = DATABASE_CLIENT in imports
        for (call in PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)) {
            val callee = call.calleeExpression?.text ?: continue
            val receiver = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call }?.receiverExpression
            when {
                callee == "createStatement" -> call.valueArguments.sqlArgument(name = "sql")?.let { sinks += it }
                jdbc && callee in JDBC_METHODS && receiverType(receiver = receiver, call = call) in JDBC_TYPES -> call.valueArguments.sqlArgument(name = "sql")?.let { sinks += it }
                databaseClient && callee == "sql" && receiverType(receiver = receiver, call = call) == "DatabaseClient" -> call.valueArguments.sqlArgument(name = "sql")?.let { sinks += it }
                (callee == "of" || callee == "newBuilder") && receiver?.text?.substringAfterLast('.') == "QueryJobConfiguration" -> bigQuery = true
            }
        }

        val follow = !bigQuery && imports.none { it.startsWith(BIGQUERY_PACKAGE) }
        val hits = mutableListOf<Hit>()
        for (sink in sinks) {
            val text = SqlText()
            if (!text.read(expression = sink, hop = follow)) continue
            val sql = text.sqlCarriers()
            hits += text.hits().filter { it.carrier in sql }
        }
        // A constant reached from two sinks yields the same hits twice, and edits must not overlap.
        return hits.distinctBy { it.offset to it.word }
    }

    private fun List<ValueArgument>.sqlArgument(name: String): KtExpression? =
        (firstOrNull { it.getArgumentName()?.asName?.asString() == name } ?: firstOrNull { it.getArgumentName() == null })?.getArgumentExpression()

    // The names `@Query` is written with when it is r2dbc's: the exact import or its alias, or the package's star import when
    // no other import or same-file declaration could supply a `Query`. Room's androidx.room.Query never matches.
    private fun r2dbcQueryNames(file: KtFile): Set<String> {
        val names = mutableSetOf<String>()
        var star = false
        var rival = false
        for (directive in file.importDirectives) {
            val fqName = directive.importedFqName?.asString() ?: continue
            when {
                directive.isAllUnder -> if (fqName == R2DBC_PACKAGE) star = true else rival = true
                fqName == R2DBC_QUERY -> names += directive.aliasName ?: "Query"
                (directive.aliasName ?: fqName.substringAfterLast('.')) == "Query" -> rival = true
            }
        }
        val declared = PsiTreeUtil.findChildrenOfType(file, KtClassOrObject::class.java).any { it.name == "Query" }
        if (star && !rival && !declared) names += "Query"
        return names
    }

    // The type written on the declaration the receiver names. A call with no receiver is gated on the receiver of the
    // with/apply/run lambda it sits directly in.
    private fun receiverType(receiver: KtExpression?, call: KtCallExpression): String? {
        val declaration = when (receiver) {
            null -> return implicitReceiverType(call)
            is KtNameReferenceExpression -> resolve(receiver)
            is KtDotQualifiedExpression -> thisMember(receiver)
            else -> null
        }
        val type = when (declaration) {
            is KtParameter, is KtProperty, is KtDestructuringDeclarationEntry -> (declaration as KtCallableDeclaration).typeReference
            else -> null
        }
        return type?.text?.removeSuffix("?")
    }

    private fun implicitReceiverType(call: KtCallExpression): String? {
        val literal = PsiTreeUtil.getParentOfType(call, KtDeclarationWithBody::class.java) as? KtFunctionLiteral ?: return null
        if (literal.hasParameterSpecification()) return null
        // a local function of the same name is nearer than any receiver's member
        val callee = call.calleeExpression?.text
        if (PsiTreeUtil.findChildrenOfType(call.containingFile, KtNamedFunction::class.java).any { it.isLocal && it.name == callee }) return null
        val scope = literal.scopeCall() ?: return null
        val receiver = when (scope.calleeExpression?.text) {
            "with" -> scope.valueArguments.takeIf { it.size == 2 }?.first()?.getArgumentExpression()
            "apply", "run" -> (scope.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == scope }?.receiverExpression
            else -> null
        } ?: return null
        return receiverType(receiver = receiver, call = call)
    }

    // `this.name` in a member resolves to the enclosing class's own property, unless a lambda or an extension sits between,
    // since either can rebind `this`.
    private fun thisMember(expression: KtDotQualifiedExpression): Any? {
        val self = expression.receiverExpression as? KtThisExpression ?: return null
        if (self.getLabelName() != null) return null
        val name = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        var scope: PsiElement? = expression.parent
        while (scope != null && scope !is KtClassOrObject) {
            if (scope is KtFunctionLiteral || (scope is KtCallableDeclaration && scope.receiverTypeReference != null)) return null
            scope = scope.parent
        }
        return (scope as? KtClassOrObject)?.member(name)
    }

    // The declaration a bare name means where it is written: the nearest parameter, lambda parameter (or implicit `it`),
    // local, destructured or loop/catch/when variable, class or companion property, then top-level property. Undecided where
    // a scope the rule cannot see into may supply the name; null when nothing in the file declares it.
    private fun resolve(ref: KtNameReferenceExpression): Any? {
        val name = ref.getReferencedName()
        var child: PsiElement = ref
        var scope: PsiElement? = ref.parent
        while (scope != null) {
            val here: PsiElement = scope
            val found: Any? = when (here) {
                // a same-block local shadows even when declared after the reference, which only makes the rule skip more
                is KtBlockExpression -> here.statements.firstNotNullOfOrNull { it.declares(name) }
                is KtFunctionLiteral -> when {
                    here.hasParameterSpecification() -> here.valueParameters.firstNotNullOfOrNull { it.declares(name) }
                    name == "it" -> here
                    else -> null
                } ?: Undecided.takeIf { here.scopeCall()?.calleeExpression?.text in RECEIVER_SCOPES }
                is KtDeclarationWithBody -> here.valueParameters.firstNotNullOfOrNull { it.declares(name) } ?: Undecided.takeIf { (here as? KtCallableDeclaration)?.receiverTypeReference != null }
                is KtProperty -> Undecided.takeIf { here.receiverTypeReference != null }
                is KtForExpression -> if (PsiTreeUtil.isAncestor(here.body, ref, false)) here.loopParameter?.declares(name) else null
                is KtCatchClause -> if (PsiTreeUtil.isAncestor(here.catchBody, ref, false)) here.catchParameter?.declares(name) else null
                is KtWhenExpression -> here.subjectVariable?.takeIf { it != child && it.name == name }
                is KtClassOrObject -> here.member(name) ?: Undecided.takeIf { here.superTypeListEntries.isNotEmpty() }
                is KtFile -> return here.topLevel(name)
                else -> null
            }
            if (found != null) return found
            child = here
            scope = here.parent
        }
        return null
    }

    private fun PsiElement.declares(name: String): PsiElement? = when (this) {
        is KtDestructuringDeclaration -> entries.firstOrNull { it.name == name }
        is KtParameter -> destructuringDeclaration?.declares(name) ?: takeIf { it.name == name }
        is KtProperty -> takeIf { it.name == name }
        else -> null
    }

    // A constructor parameter without val/var is visible only in initializers, so where it shares the name the rule cannot
    // tell which one a reference means.
    private fun KtClassOrObject.member(name: String): Any? {
        val parameter = primaryConstructorParameters.firstOrNull { it.name == name }
        if (parameter != null) return if (parameter.hasValOrVar()) parameter else Undecided
        val properties = (declarations + companionObjects.flatMap { it.declarations }).filterIsInstance<KtProperty>().filter { it.name == name }
        return if (properties.size > 1) Undecided else properties.singleOrNull()
    }

    // An explicit import outranks a top-level declaration of the same name.
    private fun KtFile.topLevel(name: String): Any? {
        if (importDirectives.any { !it.isAllUnder && (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }) return Undecided
        val properties = declarations.filterIsInstance<KtProperty>().filter { it.name == name }
        return if (properties.size > 1) Undecided else properties.singleOrNull()
    }

    // The call a lambda is passed to, as an argument or a trailing lambda.
    private fun KtFunctionLiteral.scopeCall(): KtCallExpression? {
        val argument = (parent as? KtLambdaExpression)?.parent as? KtValueArgument ?: return null
        return argument.parent as? KtCallExpression ?: argument.parent?.parent as? KtCallExpression
    }

    // One SQL statement as written: the string pieces of a `+` chain (`.trimIndent()` and parentheses unwrapped) in order,
    // each character mapped to its file offset, or to -1 where it is not plain literal text the rule may rewrite, and to its
    // carrier: 0 for the text written in the sink, then one number per followed constant.
    private class SqlText {
        val chars = StringBuilder()
        val offsets = mutableListOf<Int>()
        val carriers = mutableListOf<Int>()
        var carrier = 0
        var followed = 0
        var firstOffset = -1

        fun read(expression: KtExpression?, hop: Boolean): Boolean =
            when (expression) {
                is KtStringTemplateExpression -> {
                    if (firstOffset < 0) firstOffset = expression.textRange.startOffset
                    for (entry in expression.entries) {
                        when (entry) {
                            is KtLiteralStringTemplateEntry -> entry.text.forEachIndexed { i, c -> add(c, entry.textRange.startOffset + i) }
                            is KtEscapeStringTemplateEntry -> entry.unescapedValue.forEach { add(it, -1) }
                            // `${'$'}` is how Kotlin writes a `$` before a letter, so the SQL lexer reads it as that character
                            is KtBlockStringTemplateEntry -> constant(entry.expression)?.forEach { add(it, -1) } ?: add(OPAQUE, -1)
                            else -> add(OPAQUE, -1)
                        }
                    }
                    true
                }
                is KtParenthesizedExpression -> read(expression.expression, hop)
                is KtDotQualifiedExpression -> {
                    val selector = (expression.selectorExpression as? KtCallExpression)?.calleeExpression?.text
                    (selector == "trimIndent" || selector == "trimMargin") && read(expression.receiverExpression, hop)
                }
                is KtBinaryExpression -> {
                    if (expression.operationToken != KtTokens.PLUS) {
                        false
                    } else {
                        val left = read(expression.left, hop)
                        if (!left) add(OPAQUE, -1)
                        val right = read(expression.right, hop)
                        if (!right) add(OPAQUE, -1)
                        left || right
                    }
                }
                // One hop to the class, object, companion or top-level val the name resolves to in its scope.
                is KtNameReferenceExpression -> {
                    val declared = resolve(expression) as? KtProperty
                    hop && declared != null && (declared.parent is KtFile || declared.parent is KtClassBody) && !declared.isVar &&
                        declared.initializer != null && declared.getter == null && !declared.hasDelegate() && followed(declared)
                }
                else -> false
            }

        private fun followed(declared: KtProperty): Boolean {
            val outer = carrier
            carrier = ++followed
            val read = read(declared.initializer, hop = false)
            carrier = outer
            return read
        }

        // The carriers whose own text, templates stripped, reads as a SQL statement; hits in any other carrier are dropped.
        fun sqlCarriers(): Set<Int> =
            carriers.distinct().filterTo(mutableSetOf()) { id ->
                val text = String(CharArray(chars.length) { if (carriers[it] == id && chars[it] != OPAQUE) chars[it] else ' ' })
                looksLikeSql(text)
            }

        private fun looksLikeSql(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                i = when {
                    text[i].isWhitespace() || text[i] == '(' -> i + 1
                    text.startsWith("--", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    text.startsWith("/*", i) -> text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    else -> break
                }
            }
            val words = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text.substring(i)).map { it.value.lowercase() }.toList()
            if (!text.getOrNull(i).isWordStart() || words.firstOrNull() !in SQL_VERBS) return false
            val keywords = words.filter { it in KEYWORDS || it in SQL_VERBS }
            return keywords.size >= 2 && keywords.any { it in SQL_CLAUSES }
        }

        private fun Char?.isWordStart(): Boolean = this != null && (isLetter() || this == '_')

        // The text of a template entry that is a plain character or string constant: `${'$'}`, `${"$"}`.
        private fun constant(expression: KtExpression?): String? = when {
            expression is KtConstantExpression && expression.text.length == 3 && expression.text.startsWith('\'') -> expression.text.substring(1, 2)
            expression is KtStringTemplateExpression && expression.entries.all { it is KtLiteralStringTemplateEntry } -> expression.entries.joinToString("") { it.text }
            else -> null
        }

        private fun add(c: Char, offset: Int) {
            chars.append(c)
            offsets += offset
            carriers += carrier
        }

        // Where the text cannot be lexed with certainty (a template beside a `$` or an E-string prefix, an unterminated
        // dollar quote), the rest of the statement gets no hits.
        fun hits(): List<Hit> {
            val out = mutableListOf<Hit>()
            val s = chars
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '-' && s.startsWith("--", i) -> i = s.indexOf("\n", i).let { if (it < 0) s.length else it }
                    c == '/' && s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    c == '$' -> {
                        val open = dollarQuote(i) ?: return out + undecided(i)
                        if (open == 0) {
                            i++
                        } else {
                            val close = s.indexOf(s.substring(i, i + open), i + open)
                            if (close < 0) return out + undecided(i)
                            i = close + open
                        }
                    }
                    c == '\'' && (s.getOrNull(i - 1) == 'E' || s.getOrNull(i - 1) == 'e') && !s.getOrNull(i - 2).isWordChar() -> {
                        if (s.getOrNull(i - 2) == OPAQUE) return out + undecided(i)
                        i = escapeStringEnd(i)
                    }
                    c == '\'' || c == '"' || c == '`' -> i = s.indexOf(c, i + 1).let { if (it < 0) s.length else it + 1 }
                    c.isLetter() || c == '_' -> {
                        var end = i
                        while (end < s.length && s[end].isWordChar()) end++
                        word(start = i, end = end)?.let { out += it }
                        i = end
                    }
                    c.isDigit() -> {
                        while (i < s.length && s[i].isWordChar()) i++
                    }
                    else -> i++
                }
            }
            return out
        }

        // The first keyword left uppercase after a point the lexer cannot get past, reported so check does not call it clean.
        private fun undecided(from: Int): List<Hit> {
            var i = from
            while (i < chars.length) {
                if (chars[i].isLetter() && !chars.getOrNull(i - 1).isWordChar()) {
                    var end = i
                    while (chars.getOrNull(end).isWordChar()) end++
                    val word = chars.substring(i, end)
                    if (word != word.lowercase() && word.lowercase() in KEYWORDS) {
                        val offset = (i until end).map { offsets[it] }.firstOrNull { it >= 0 } ?: firstOffset
                        return listOf(Hit(offset = offset, word = word, edit = null, carrier = carriers[i], reason = "follows a quote the rule cannot read"))
                    }
                    i = end
                } else {
                    i++
                }
            }
            return emptyList()
        }

        private fun Char?.isWordChar(): Boolean = this != null && (isLetterOrDigit() || this == '_')

        // At a `$`: the length of the `$$` or `$tag$` it opens, 0 when it opens none (`$1`, `$name`, `a$b`), or null when a
        // template sits beside it and the quote cannot be known.
        private fun dollarQuote(i: Int): Int? {
            val s = chars
            if (s.getOrNull(i - 1) == OPAQUE || s.getOrNull(i + 1) == OPAQUE) return null
            if (s.getOrNull(i - 1).isWordChar()) return 0
            var end = i + 1
            if (s.getOrNull(end)?.let { it.isLetter() || it == '_' } == true) {
                while (s.getOrNull(end).isWordChar()) end++
            }
            return if (s.getOrNull(end) == '$') end + 1 - i else 0
        }

        // Postgres E'...' honours backslash escapes, so `\'` does not close it; `''` does not either.
        private fun escapeStringEnd(i: Int): Int {
            val s = chars
            var j = i + 1
            while (j < s.length) {
                when {
                    s[j] == '\\' -> j += 2
                    s[j] == '\'' && s.getOrNull(j + 1) == '\'' -> j += 2
                    s[j] == '\'' -> return j + 1
                    else -> j++
                }
            }
            return s.length
        }

        private fun word(start: Int, end: Int): Hit? {
            val word = chars.substring(start, end)
            val lower = word.lowercase()
            if (word == lower) return null
            val before = chars.getOrNull(start - 1)
            val after = chars.getOrNull(end)
            // a qualified name's part, a :param, a $name, an @name, or a word glued to a template is an identifier or unknown
            if (before == '.' || before == ':' || before == '$' || before == '@' || before == OPAQUE || after == '.' || after == OPAQUE) return null
            var next = end
            while (next < chars.length && chars[next].isWhitespace()) next++
            val call = chars.getOrNull(next) == '('
            if (lower !in KEYWORDS && !(call && lower in FUNCTIONS)) return null
            val first = offsets[start]
            val editable = first >= 0 && (start until end).all { offsets[it] == first + (it - start) }
            return Hit(
                offset = (start until end).map { offsets[it] }.firstOrNull { it >= 0 } ?: firstOffset,
                word = word,
                edit = if (editable) Edit(first, first + word.length, lower) else null,
                carrier = carriers[start],
            )
        }
    }
}
