package io.github.lutics.formatter.imports

import io.github.lutics.formatter.rules.layout.ImportOrder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.ImportPath

// KotlinCodeStyleSettings' import options. The defaults are the IDE's; IntelliJ's own tests run in unit-test mode, which the
// testData runner sets per case.
data class ImportSettings(
    val nameCountToUseStarImport: Int = 5,
    val nameCountToUseStarImportForMembers: Int = 3,
    // KotlinPackageEntry(name, withSubpackages)
    val packagesToUseStarImports: List<Pair<String, Boolean>> = listOf("java.util" to false, "kotlinx.android.synthetic" to true, "io.ktor" to true),
)

// IntelliJ's ImportMapper: Java names that have a Kotlin counterpart are imported, and compared, as the Kotlin name.
private object ImportMapper {
    private val map: Map<FqName, Pair<FqName, Pair<Int, Int>>> = buildMap {
        fun mapping(javaPackage: String, kotlinPackage: String, names: List<String>, version: Pair<Int, Int> = 1 to 3) {
            for (name in names) put(FqName("$javaPackage.$name"), FqName("$kotlinPackage.$name") to version)
        }
        mapping("java.util", "kotlin.collections", listOf("RandomAccess", "ArrayList", "LinkedHashMap", "HashMap", "LinkedHashSet", "HashSet"))
        mapping("java.lang", "kotlin.text", listOf("Appendable", "StringBuilder"))
        mapping("java.nio.charset", "kotlin.text", listOf("CharacterCodingException"), 1 to 4)
        mapping("kotlin.jvm", "kotlin", listOf("Throws"), 1 to 4)
        mapping(
            "java.lang",
            "kotlin",
            listOf(
                "Error", "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException", "IndexOutOfBoundsException",
                "UnsupportedOperationException", "ArithmeticException", "NumberFormatException", "NullPointerException", "ClassCastException",
                "AssertionError",
            ),
        )
        mapping("java.util", "kotlin", listOf("NoSuchElementException", "ConcurrentModificationException", "Comparator"))
    }

    // importSymbolWithMapping: the Kotlin name once the API version has it, else the name itself
    fun mapped(fqName: FqName, apiVersion: String): FqName {
        val (kotlin, since) = map[fqName] ?: return fqName
        val (major, minor) = apiVersion.split('.').let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: 0) }
        return if (major > since.first || major == since.first && minor >= since.second) kotlin else fqName
    }
}

// The port of IntelliJ's K2 OptimizedImportsBuilder (plugins/kotlin/code-insight/kotlin.code-insight.k2/src/org/jetbrains/kotlin/
// idea/k2/codeinsight/imports/OptimizedImportsBuilder.kt) over one file of the imports record. The analysis session calls it makes
// are answered from the record: containing classes and classifier lookups from what the recorder looked up, and the conflict
// check's re-resolution of the file with the new imports from the record of a compile of the step's own output.
class OptimizedImports(private val file: FileImports, private val record: ImportRecord, private val settings: ImportSettings) {
    private val importPaths: Set<ImportPath> = file.imports.filterNotNull().toSet()

    // IntelliJ's ImportRules: Add forces an import's presence, DoNotAdd a star import's absence. Imports that may refer to an
    // unresolved name stay.
    private val forced = importPaths.filterTo(LinkedHashSet()) { it.isAllUnder && file.unresolved.isNotEmpty() || it.importedName in file.unresolved }
    private val banned = HashSet<ImportPath>()

    // buildOptimizedImports: build, then check the result against the compile of it in each verification round; null when the
    // imports are already optimal. rounds[i] is the record of the compile of round i's output; a round without this file ends
    // the replay with that round's output still to be verified.
    fun build(rounds: List<ImportRecord>): List<ImportPath>? {
        var result = tryBuild() ?: return null
        for (round in rounds) {
            val compiled = round.files[ImportRecord.canonical(file.path)] ?: return result
            val before = forced.size + banned.size
            detectConflicts(compiled)
            if (forced.size + banned.size == before) return result
            result = tryBuild() ?: return null
        }
        return result
    }

    private fun tryBuild(): List<ImportPath>? {
        val importsToGenerate = LinkedHashSet(forced)

        val symbolsByParentFqName = LinkedHashMap<FqName, MutableSet<UsedSymbol>>()
        for (symbol in file.used) {
            val fqName = symbol.fqName
            for (name in file.usedDeclarations.getValue(fqName)) {
                val alias = if (name != fqName.shortName()) name else null
                val resultFqName = mapped(fqName)
                val explicitImportPath = ImportPath(resultFqName, false, alias)
                if (explicitImportPath in importsToGenerate) continue
                val parentFqName = resultFqName.parent()
                if (alias == null && canUseStarImport(symbol, resultFqName) && ImportPath(parentFqName, true) !in banned) {
                    symbolsByParentFqName.getOrPut(parentFqName, ::LinkedHashSet).add(symbol)
                } else {
                    importsToGenerate += explicitImportPath
                }
            }
        }

        val classNamesToCheck = LinkedHashSet<FqName>()
        for ((parentFqName, symbols) in symbolsByParentFqName) {
            val starImportPath = ImportPath(parentFqName, true)
            if (starImportPath in importsToGenerate) continue
            val fqNames = symbols.map { mapped(it.fqName) }.toSet()
            val nameCountToUseStar = nameCountToUseStar(symbols.first())
            val useExplicitImports = starImportPath in banned || (fqNames.size < nameCountToUseStar && !isInPackagesToUseStarImport(parentFqName))
            if (useExplicitImports) {
                fqNames.filter { needExplicitImport(it) }.mapTo(importsToGenerate) { ImportPath(it, false) }
            } else {
                symbols.filter { it.classLike }.map { mapped(it.fqName) }.filterTo(classNamesToCheck) { needExplicitImport(it) }
                if (fqNames.all { needExplicitImport(it) }) importsToGenerate += starImportPath
            }
        }

        val starImports = importsToGenerate.filter { it.isAllUnder }
        for (fqName in classNamesToCheck) {
            val found = findClassifiers(fqName.shortName(), starImports)
            if (found?.singleOrNull() != fqName) {
                // add explicit import if failed to import with * (or from current package)
                importsToGenerate += ImportPath(fqName, false)
                val parentFqName = fqName.parent()
                val siblingsToImport = symbolsByParentFqName.getValue(parentFqName)
                siblingsToImport.removeAll { it.fqName == fqName }
                // star import is not really needed
                if (siblingsToImport.isEmpty()) importsToGenerate -= ImportPath(parentFqName, true)
            }
        }

        val sorted = importsToGenerate.sortedWith(ImportOrder)
        if (file.imports == sorted) return null
        return sorted
    }

    // HierarchicalScope.findClassifiers over buildImportingScopes(file, star imports): the first scope layer, in tower order,
    // that has a classifier of this name. The dummy file has no explicit imports.
    private fun findClassifiers(name: Name, starImports: List<ImportPath>): List<FqName>? {
        val facts = file.classifiers[name] ?: return null
        val layers = sequence {
            yield(facts.inPackage)
            yield(record.defaults.filter { !it.isAllUnder && !it.hasAlias() && it.fqName.shortName() == name }.map { it.fqName })
            yield(starImports.mapNotNull { facts.inParent[it.fqName] }.distinct())
            yield(facts.defaultStar)
        }
        return layers.firstOrNull { it.isNotEmpty() }
    }

    // detectConflictsAndUpdateRules: a reference whose targets differ in the compile of the new imports locks the imports of
    // its symbols. References are matched per resolve names and type position, as multisets, because the compile of the new
    // imports sees the file after the steps that ran since the first compile.
    private fun detectConflicts(compiled: FileImports) {
        val original = groups(file.references)
        val alternative = groups(compiled.references)
        for (key in original.keys + alternative.keys) {
            val names = key.substringBefore('|').split(',').filter { it.isNotEmpty() }.map { Name.guessByFirstCharacter(it) }
            val isTypePosition = key.substringAfter('|') == "t"
            val before = original[key].orEmpty().toMutableList()
            val after = alternative[key].orEmpty().toMutableList()
            for (targets in before.toList()) {
                if (after.remove(targets)) before.remove(targets)
            }
            if (before.isEmpty() && after.isEmpty()) continue
            val symbolsToLock = if (isTypePosition) before.flatten() else before.flatten() + after.flatten()
            for (symbol in symbolsToLock) lockImportForSymbol(symbol, names)
        }
    }

    // each reference as its targets after importSymbolWithMapping, which is how areTargetsEqual compares them
    private fun groups(references: List<String>): Map<String, List<List<String>>> = references.groupBy(
        { it.substringBeforeLast('|') },
        { ref -> ref.substringAfterLast('|').split(',').filter { it.isNotEmpty() }.map { if (it == "U") it else mapped(FqName(it.substring(2))).asString() }.sorted() },
    )

    private fun lockImportForSymbol(symbol: String, existingNames: Collection<Name>) {
        if (symbol == "U") return
        val fqName = FqName(symbol)
        val name = fqName.shortName()
        val names = (file.usedDeclarations[fqName] ?: setOf(name)).intersect(existingNames.toSet())
        val starImportPath = ImportPath(fqName.parent(), true)
        for (n in names) {
            val alias = if (n != fqName.shortName()) n else null
            val explicitImportPath = ImportPath(fqName, false, alias)
            when {
                explicitImportPath in importPaths -> forced += explicitImportPath
                alias == null && starImportPath in importPaths -> forced += starImportPath
                // there is no import for this symbol in the original import list, so do not allow to import it by star-import
                else -> banned += starImportPath
            }
        }
    }

    private fun mapped(fqName: FqName) = ImportMapper.mapped(fqName, file.apiVersion)

    // star import from objects is not allowed
    private fun canUseStarImport(symbol: UsedSymbol, fqName: FqName) = !fqName.parent().isRoot && symbol.outer != 'o'

    private fun nameCountToUseStar(symbol: UsedSymbol) =
        if (symbol.outer == ' ') settings.nameCountToUseStarImport else settings.nameCountToUseStarImportForMembers

    // KotlinPackageEntryTable.contains
    private fun isInPackagesToUseStarImport(fqName: FqName): Boolean {
        val name = fqName.asString()
        return settings.packagesToUseStarImports.any { (pkg, withSubpackages) ->
            name.startsWith(pkg) && (name.length == pkg.length || withSubpackages && name[pkg.length] == '.')
        }
    }

    private fun needExplicitImport(fqName: FqName) = hasAlias(fqName) || !isImportedByDefault(fqName)

    private fun hasAlias(fqName: FqName) = file.usedDeclarations[fqName].orEmpty().size > 1

    // KotlinIdeDefaultImportProvider.isImportedWithDefault, every priority
    private fun isImportedByDefault(fqName: FqName): Boolean {
        val imported = record.defaults.any { default ->
            when {
                default.hasAlias() -> false
                default.isAllUnder && !fqName.isRoot -> default.fqName == fqName.parent()
                else -> default.fqName == fqName
            }
        }
        return imported && fqName !in record.excluded
    }
}
