package io.github.lutics.formatter.imports

import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.ImportPath
import java.io.File

// A used importable symbol (IntelliJ's SymbolInfo): its importable name, whether it is class-like, and its containing class:
// 'o' an object, 'c' another class, ' ' none.
class UsedSymbol(val fqName: FqName, val classLike: Boolean, val outer: Char)

// What the classifier lookups of the star-import check find for one short name: in the current package, in the default star
// imports (java.lang.* only when kotlin's have none), and in each candidate star import's parent.
class ClassifierFacts(val inPackage: List<FqName>, val defaultStar: List<FqName>, val inParent: Map<FqName, FqName>)

// One file of the compiler plugin's imports record (ImportRecorder): UsedReferencesCollector's result plus the lookups
// OptimizedImportsBuilder makes.
class FileImports(
    val path: String,
    val packageFqName: FqName,
    val apiVersion: String,
    val imports: List<ImportPath?>,
    val importText: String,
    val used: List<UsedSymbol>,
    val usedDeclarations: Map<FqName, Set<Name>>,
    val unresolved: Set<Name>,
    // "names|t|targets": each reference with its resolve names, whether it is in a type, and its targets as SymbolInfos
    val references: List<String>,
    val classifiers: Map<Name, ClassifierFacts>,
)

// One recording compile: the platform's default imports and every file it compiled, by canonical path.
class ImportRecord(val defaults: List<ImportPath>, val excluded: Set<FqName>, val files: Map<String, FileImports>) {
    companion object {
        fun load(file: File): ImportRecord {
            var defaults = emptyList<ImportPath>()
            var excluded = emptySet<FqName>()
            val files = LinkedHashMap<String, FileImports>()
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                @Suppress("UNCHECKED_CAST")
                val o = Json(line).parse() as Map<String, Any?>
                if ("defaults" in o) {
                    defaults = strings(o["defaults"]).map { ImportPath.fromString(it) }
                    excluded = strings(o["excluded"]).map { FqName(it) }.toSet()
                } else {
                    files[canonical(o["file"] as String)] = fileImports(o)
                }
            }
            return ImportRecord(defaults, excluded, files)
        }

        fun canonical(path: String): String = File(path).canonicalPath

        @Suppress("UNCHECKED_CAST")
        private fun fileImports(o: Map<String, Any?>): FileImports = FileImports(
            path = o["file"] as String,
            packageFqName = FqName(o["pkg"] as String),
            apiVersion = o["api"] as String,
            imports = (o["imports"] as List<Any?>).map { entry ->
                val parts = entry as? List<Any?> ?: return@map null
                ImportPath(FqName(parts[0] as String), parts[1] as Boolean, (parts[2] as String?)?.let { Name.guessByFirstCharacter(it) })
            },
            importText = o["importText"] as String,
            used = (o["used"] as List<List<String>>).map { UsedSymbol(FqName(it[0]), it[1] == "C", it[2].firstOrNull() ?: ' ') },
            usedDeclarations = (o["decl"] as Map<String, Any?>).map { (k, v) -> FqName(k) to strings(v).map { Name.guessByFirstCharacter(it) }.toSet() }.toMap(),
            unresolved = strings(o["unresolved"]).map { Name.guessByFirstCharacter(it) }.toSet(),
            references = strings(o["refs"]),
            classifiers = (o["cls"] as Map<String, Map<String, Any?>>).map { (name, facts) ->
                Name.guessByFirstCharacter(name) to ClassifierFacts(
                    inPackage = strings(facts["pkg"]).map(::FqName),
                    defaultStar = strings(facts["dstar"]).map(::FqName),
                    inParent = (facts["stars"] as Map<String, String>).map { (k, v) -> FqName(k) to FqName(v) }.toMap(),
                )
            }.toMap(),
        )

        @Suppress("UNCHECKED_CAST")
        private fun strings(v: Any?): List<String> = (v as List<String>?).orEmpty()
    }
}

// The JSON the recorder writes: objects, arrays, strings, booleans and null.
private class Json(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        skip()
        return when (s[i]) {
            '{' -> {
                i++
                val map = LinkedHashMap<String, Any?>()
                while (!closes('}')) {
                    val key = string()
                    skip()
                    check(s[i++] == ':')
                    map[key] = parse()
                }
                map
            }
            '[' -> {
                i++
                val list = ArrayList<Any?>()
                while (!closes(']')) list += parse()
                list
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> error("unexpected '${s[i]}' at $i")
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        check(s.startsWith(word, i))
        i += word.length
        return value
    }

    // skips a separating comma; at the closing bracket, steps past it and returns true
    private fun closes(bracket: Char): Boolean {
        skip()
        if (s[i] == ',') i++
        skip()
        if (s[i] != bracket) return false
        i++
        return true
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun string(): String {
        check(s[i++] == '"')
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
}
