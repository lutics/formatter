package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.ImportPath

// IntelliJ's KotlinImportPathComparator over the default layout `*, java.**, javax.**, kotlin.**, ^` (KotlinCodeStyleSettings):
// the layout entry an import matches best, then the path text without backticks. An aliased import matches only `^` when
// compared with a plain one, but the package entries when compared with another aliased one, so aliases come last and are
// grouped by package among themselves.
object ImportOrder : Comparator<ImportPath> {
    private class Entry(val packageName: String, val withSubpackages: Boolean)

    private val allOther = Entry("<all other imports>", true)
    private val allOtherAlias = Entry("<all other alias imports>", true)
    private val layout = listOf(allOther, Entry("java", true), Entry("javax", true), Entry("kotlin", true), allOtherAlias)

    override fun compare(a: ImportPath, b: ImportPath): Int {
        val ignoreAlias = a.hasAlias() && b.hasAlias()
        val byEntry = bestEntry(a, ignoreAlias).compareTo(bestEntry(b, ignoreAlias))
        if (byEntry != 0) return byEntry
        return a.toString().replace("`", "").compareTo(b.toString().replace("`", ""))
    }

    private fun bestEntry(path: ImportPath, ignoreAlias: Boolean): Int {
        var best = -1
        for (index in layout.indices) {
            if (isBetterMatch(layout[index], layout.getOrNull(best), path, ignoreAlias)) best = index
        }
        return best
    }

    private fun isBetterMatch(entry: Entry, best: Entry?, path: ImportPath, ignoreAlias: Boolean): Boolean {
        if (!matches(entry, path, ignoreAlias)) return false
        if (best == null) return true
        if (entry == allOther) return false
        if (best == allOther) return true
        if (best.withSubpackages != entry.withSubpackages) return !entry.withSubpackages
        return best.packageName.count { it == '.' } < entry.packageName.count { it == '.' }
    }

    private fun matches(entry: Entry, path: ImportPath, ignoreAlias: Boolean): Boolean {
        if (!ignoreAlias && path.hasAlias()) return entry == allOtherAlias
        if (entry == allOther) return true
        if (entry == allOtherAlias) return false
        val name = path.pathStr
        if (!name.startsWith(entry.packageName)) return false
        return name.length == entry.packageName.length || entry.withSubpackages && name[entry.packageName.length] == '.'
    }
}

// Imports in ImportOrder, one block, no blank lines, no duplicates.
object ImportOrdering : Rule {
    override val id = "import-ordering"

    // A list that holds comments is left alone
    private fun hasComments(file: KtFile) = file.importList?.children?.any { it is PsiComment } == true

    override fun findings(file: KtFile): List<Finding> {
        val list = file.importList ?: return emptyList()
        if (list.children.isEmpty() || !hasComments(file)) return emptyList()
        return listOf(Finding(list.textRange.startOffset, "Imports must be ordered; not fixable with comments in the import list"))
    }

    override fun edits(file: KtFile): List<Edit> {
        val list = file.importList ?: return emptyList()
        if (list.children.isEmpty() || hasComments(file)) return emptyList()
        val seen = mutableSetOf<String>()
        val unique = list.imports.filter { seen.add(it.text) }
        val sorted = unique.sortedWith(compareBy(ImportOrder) { it.importPath!! })
        val gaps = generateSequence(list.firstChild) { it.nextSibling }.filterIsInstance<PsiWhiteSpace>().toList()
        val blankOrSpaced = gaps.any { ws -> ws.text.count { it == '\n' } > 1 || '\n' !in ws.text }
        if (unique.size == list.imports.size && sorted == unique && !blankOrSpaced) return emptyList()
        val first = list.imports.first().textRange.startOffset
        val last = list.imports.last().textRange.endOffset
        return listOf(Edit(first, last, sorted.joinToString("\n") { it.text }))
    }
}
