package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Engine
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import java.io.File

// A sealed *UiState's members, and the branches of a when over one, run Initial → Loading → Empty → Success → Error.
// Only the canonical members move, and only among the positions canonical members already hold: a member with another
// name (SignedOut, Loaded) has no place in that order, so it keeps its position and its neighbours.
// A when names its type by text only, so it is rewritten only when its shape and the type's declaration in the index make
// the reorder safe; any other when out of order is reported. A type whose simple name is declared more than once in the
// files formatted together is never rewritten, even where its qualifier resolves to one of them. The equals checks below
// apply only to a when with a value branch: `is` branches over distinct final members match by class alone.
// The rule has no compiler, so these are left unseen on purpose, and a when they make unsafe can still be rewritten:
// - the qualifier's first segment resolves through the enclosing classes' own nested declarations, the explicit imports,
//   the package and the star imports, over the files formatted together only: a nested class inherited from an enclosing
//   class's supertype, a local class, or a same-named class outside those files that Kotlin would pick is not seen;
// - a value branch calls the subject's equals; the check covers every class in those files that names the type as a
//   supertype (by simple name, through import aliases and typealiases), but not a subject whose static type is wider than
//   the UiState and whose value is some other class with an equals that matches a member.
object UiStateOrder : Rule {
    override val id = "ui-state-order"

    // A member of a sealed *UiState: final when nothing can extend it, the simple names of its supertypes, the ones of those
    // that may be classes (so may carry an equals), and whether it declares equals itself.
    class Member(val final: Boolean, val supertypes: Set<String>, val classSupertypes: Set<String>, val declaresEquals: Boolean)

    // A sealed *UiState; outsideSubtype: a class outside its body that names it as a supertype.
    class SealedType(val simpleName: String, val members: Map<String, Member>, val declaresEquals: Boolean, val extendsClass: Boolean, val outsideSubtype: String?)

    // types: the sealed *UiState declarations by fully qualified name (package and enclosing classes); declared: how many
    // of them carry each simple name; classifiers: the qualified name of every class, object and typealias, for resolving
    // a qualifier's first segment.
    class Index(val types: Map<String, SealedType>, val declared: Map<String, Int>, val classifiers: Set<String>)

    // The index over all the files formatted together. The when sees one file and the type is usually declared in another,
    // so whoever has all the files sets this before FORMAT or CHECK and resets it after; null: never set, and no when is
    // rewritten.
    var index: Index? = null

    // Every file is parsed: a subtype can name the type through a typealias declared in another file.
    fun buildIndex(files: List<File>): Index {
        val parsed = files.map { Engine.parse(it.name, it.readText(), allowErrors = true) }
        val classifiers = mutableSetOf<String>()
        val aliases = mutableMapOf<String, String>()
        for (file in parsed) {
            for (declaration in file.descendants<KtClassOrObject>()) declaration.fqName?.let { classifiers += it.asString() }
            for (alias in file.descendants<KtTypeAlias>()) {
                alias.fqName?.let { classifiers += it.asString() }
                val target = (alias.getTypeReference()?.typeElement as? KtUserType)?.referencedName ?: continue
                aliases[alias.name ?: continue] = target
            }
        }
        // (declaration, simple names its supertypes may stand for) for every class and object
        val subtypes = parsed.flatMap { file ->
            val imported = file.importDirectives.mapNotNull { directive ->
                directive.aliasName?.let { alias -> directive.importedFqName?.let { alias to it.shortName().asString() } }
            }.toMap()
            file.descendants<KtClassOrObject>().map { it to it.superTypeListEntries.flatMap { entry -> supertypeNames(entry, imported, aliases) }.toSet() }
        }
        val types = mutableMapOf<String, SealedType>()
        val declared = mutableMapOf<String, Int>()
        for (file in parsed) {
            for (type in file.descendants<KtClass>()) {
                val name = type.name ?: continue
                if (!type.isSealed() || !name.endsWith("UiState")) continue
                val fqName = type.fqName?.asString() ?: continue
                declared[name] = (declared[name] ?: 0) + 1
                val members = type.body?.declarations.orEmpty().filterIsInstance<KtClassOrObject>()
                    .filter { it.name != null && !(it is KtObjectDeclaration && it.isCompanion()) }
                    .associate { member ->
                        val supertypes = member.superTypeListEntries.associateWith { entry -> (entry.typeReference?.typeElement as? KtUserType)?.referencedName }
                        member.name!! to Member(
                            final = isFinal(member),
                            supertypes = supertypes.values.filterNotNull().toSet(),
                            classSupertypes = supertypes.filter { (entry, _) -> mayBeClass(member, entry) }.values.map { it ?: "?" }.toSet(),
                            declaresEquals = declaresEquals(member),
                        )
                    }
                val outside = subtypes.firstOrNull { (declaration, names) ->
                    name in names && ((declaration.parent as? KtClassBody)?.parent as? KtClassOrObject)?.fqName?.asString() != fqName
                }?.first
                types[fqName] = SealedType(
                    simpleName = name,
                    members = members,
                    declaresEquals = declaresEquals(type),
                    extendsClass = type.superTypeListEntries.any { mayBeClass(type, it) },
                    outsideSubtype = outside?.let { it.name ?: "an object expression" },
                )
            }
        }
        return Index(
            types = types,
            declared = declared,
            classifiers = classifiers,
        )
    }

    // The simple names a supertype entry may stand for: its own, an import alias's target, and a typealias chain's targets.
    private fun supertypeNames(entry: KtSuperTypeListEntry, imported: Map<String, String>, aliases: Map<String, String>): List<String> {
        val names = mutableListOf<String>()
        var name = (entry.typeReference?.typeElement as? KtUserType)?.referencedName
        while (name != null && name !in names) {
            names += name
            name = imported[name] ?: aliases[name]
        }
        return names
    }

    // A supertype called with a constructor is a class; one named bare is an interface, except in a class whose only
    // constructors are secondary ones, which call the superclass's constructor themselves.
    private fun mayBeClass(declaration: KtClassOrObject, entry: KtSuperTypeListEntry): Boolean =
        entry is KtSuperTypeCallEntry || (entry !is KtDelegatedSuperTypeEntry && declaration is KtClass && declaration.primaryConstructor == null && declaration.secondaryConstructors.isNotEmpty())

    private fun declaresEquals(declaration: KtClassOrObject): Boolean =
        declaration.body?.declarations.orEmpty().any { it is KtNamedFunction && it.name == "equals" && it.valueParameters.size == 1 }

    private fun isFinal(member: KtClassOrObject): Boolean = when (member) {
        is KtObjectDeclaration -> true
        is KtClass -> !member.isInterface() && !member.isSealed() && !member.hasModifier(KtTokens.OPEN_KEYWORD) && !member.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        else -> false
    }

    private val ORDER = listOf("Initial", "Loading", "Empty", "Success", "Error")

    // A type qualifier the rule can trust by its text alone: dotted identifiers whose last one ends in UiState.
    private val QUALIFIER = Regex("""([A-Za-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)?UiState""")

    override fun edits(file: KtFile): List<Edit> {
        val edits = mutableListOf<Edit>()
        // an inner when or type inside a moved part waits for the next round, so no two edits overlap
        val claimed = mutableListOf<TextRange>()
        for (element in file.descendants<PsiElement>()) {
            val edit = when (element) {
                is KtClass -> memberEdit(element)
                is KtWhenExpression -> branchEdit(element)
                else -> null
            } ?: continue
            val range = TextRange(edit.start, edit.end)
            if (claimed.any { it.intersects(range) }) continue
            claimed += range
            edits += edit
        }
        return edits
    }

    override fun findings(file: KtFile): List<Finding> =
        file.descendants<KtWhenExpression>().mapNotNull { whenExpr ->
            val outOfOrder = outOfOrderEntry(whenExpr) ?: return@mapNotNull null
            val reason = unsafeReason(whenExpr) ?: return@mapNotNull null
            Finding(outOfOrder.textRange.startOffset, "Branches over a UiState run Initial, Loading, Empty, Success, Error; not reordered because $reason. Reorder it by hand.")
        }

    private fun memberEdit(type: KtClass): Edit? {
        if (!type.isSealed() || type.name?.endsWith("UiState") != true) return null
        val members = type.body?.declarations.orEmpty().filterIsInstance<KtClassOrObject>()
            .filter { !(it is KtObjectDeclaration && it.isCompanion()) && it.name in ORDER }
        return permute(members) { ORDER.indexOf(it.name) }
    }

    private fun branchEdit(whenExpr: KtWhenExpression): Edit? {
        if (outOfOrderEntry(whenExpr) == null || unsafeReason(whenExpr) != null) return null
        val ranked = whenExpr.entries.filter { singleState(it)?.second in ORDER }
        return permute(ranked) { ORDER.indexOf(singleState(it)!!.second) }
    }

    // Moves the items into canonical order within the positions they hold; null when they already are in order.
    private fun <T : PsiElement> permute(items: List<T>, rank: (T) -> Int): Edit? {
        val sorted = items.sortedBy(rank)
        if (sorted == items) return null
        val text = items.first().containingFile.text
        val spans = items.map { span(it) }
        val start = spans.first().startOffset
        val out = StringBuilder()
        var at = start
        for ((index, span) in spans.withIndex()) {
            out.append(text, at, span.startOffset)
            out.append(span(sorted[index]).substring(text))
            at = span.endOffset
        }
        return Edit(start, at, out.toString())
    }

    // An item with the comments that travel with it: own-line comments right above it and a comment after it on its line.
    private fun span(item: PsiElement): TextRange {
        var start: PsiElement = item
        var prev = item.prevSibling
        while (prev != null && (prev is PsiWhiteSpace || prev.isComment())) {
            // a comment a blank line above heads a group of items, not this one
            if (prev is PsiWhiteSpace && prev.text.count { it == '\n' } >= 2) break
            if (prev.isComment()) {
                if (!startsLine(prev)) break
                start = prev
            }
            prev = prev.prevSibling
        }
        var end: PsiElement = item
        var next = item.nextSibling
        while (next is PsiWhiteSpace && !next.text.contains('\n')) next = next.nextSibling
        if (next != null && next.isComment()) end = next
        return TextRange(start.textRange.startOffset, end.textRange.endOffset)
    }

    private fun startsLine(element: PsiElement): Boolean {
        val text = element.containingFile.text
        val lineStart = text.lastIndexOf('\n', element.textRange.startOffset - 1) + 1
        return text.substring(lineStart, element.textRange.startOffset).isBlank()
    }

    // The first entry whose earliest named state comes before a state an entry above it already named, per UiState type.
    private fun outOfOrderEntry(whenExpr: KtWhenExpression): KtWhenEntry? {
        val latest = mutableMapOf<String, Int>()
        val bare = bareScope(whenExpr)
        for (entry in whenExpr.entries) {
            val named = entry.conditions.flatMap { namedStates(it) + bareStates(it, bare) }.filter { it.second in ORDER }.groupBy({ it.first }, { ORDER.indexOf(it.second) })
            for ((qualifier, ranks) in named) {
                val rank = ranks.min()
                if (rank < (latest[qualifier] ?: -1)) return entry
                latest[qualifier] = rank
            }
        }
        return null
    }

    // Every `Qualifier.Name` a condition names as a type or a value, with a UiState qualifier.
    private fun namedStates(element: PsiElement): List<Pair<String, String>> =
        element.descendants<PsiElement>().mapNotNull { node ->
            when (node) {
                is KtUserType -> node.qualifier?.text?.let { qualifier -> node.referencedName?.let { qualifier to it } }
                is KtDotQualifiedExpression -> (node.selectorExpression as? KtNameReferenceExpression)?.let { node.receiverExpression.text to it.getReferencedName() }
                else -> null
            }
        }.filter { QUALIFIER.matches(it.first) }

    // The bare names that mean a member of a sealed *UiState here: its members inside its body, and members imported by name
    // (under an alias too) or with a star; each maps to (type, member). An explicit import of a name, of anything, shadows a
    // star import's, as in Kotlin.
    private fun bareScope(whenExpr: KtWhenExpression): Map<String, Pair<String, String>> {
        val scope = mutableMapOf<String, Pair<String, String>>()
        val directives = whenExpr.containingKtFile.importDirectives
        val explicit = directives.filter { !it.isAllUnder }.mapNotNull { it.importedName?.asString() }.toSet()
        for (directive in directives) {
            val fqName = directive.importedFqName ?: continue
            if (directive.isAllUnder) {
                if (!QUALIFIER.matches(fqName.shortName().asString())) continue
                // an indexed type brings only the members it has
                val names = index?.types?.get(fqName.asString())?.members?.keys ?: ORDER
                ORDER.filter { it in names && it !in explicit }.forEach { scope[it] = fqName.shortName().asString() to it }
                continue
            }
            val type = fqName.parent().takeIf { !it.isRoot }?.shortName()?.asString() ?: continue
            val name = directive.importedName?.asString() ?: continue
            if (QUALIFIER.matches(type) && fqName.shortName().asString() in ORDER) scope[name] = type to fqName.shortName().asString()
        }
        // the innermost enclosing type wins, as Kotlin resolves it
        val enclosing = generateSequence(whenExpr.parent) { it.parent }.filterIsInstance<KtClass>()
            .filter { it.isSealed() && it.name?.endsWith("UiState") == true }.toList().asReversed()
        for (type in enclosing) {
            type.body?.declarations.orEmpty().filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.filter { it in ORDER }.forEach { scope[it] = type.name!! to it }
        }
        return scope
    }

    // The members a condition names without a qualifier, as a type or a value, resolved through the scope.
    private fun bareStates(element: PsiElement, scope: Map<String, Pair<String, String>>): List<Pair<String, String>> =
        element.descendants<PsiElement>().mapNotNull { node ->
            when {
                node is KtUserType && node.qualifier == null -> scope[node.referencedName]
                node is KtNameReferenceExpression && node.parent !is KtUserType && (node.parent as? KtDotQualifiedExpression)?.selectorExpression != node -> scope[node.getReferencedName()]
                else -> null
            }
        }

    // The one state an entry's single condition names, as `is T.Member` or `T.Member`; null for any other shape.
    private fun singleState(entry: KtWhenEntry): Pair<String, String>? {
        if (entry.isElse || entry.guard != null) return null
        val condition = entry.conditions.singleOrNull() ?: return null
        val named = when (condition) {
            is KtWhenConditionIsPattern -> {
                val type = condition.typeReference?.typeElement as? KtUserType
                if (condition.isNegated || type == null) return null
                type.qualifier?.text?.let { qualifier -> type.referencedName?.let { qualifier to it } }
            }
            is KtWhenConditionWithExpression -> {
                val expression = condition.expression as? KtDotQualifiedExpression ?: return null
                (expression.selectorExpression as? KtNameReferenceExpression)?.let { expression.receiverExpression.text to it.getReferencedName() }
            }
            else -> null
        }
        return named?.takeIf { QUALIFIER.matches(it.first) }
    }

    // Why moving this when's branches could change what it does, or null when it cannot: each branch then tests one
    // distinct final member of one indexed UiState type against the subject, and no equals can make one value match two
    // members, so at most one branch matches and order is not semantics.
    private fun unsafeReason(whenExpr: KtWhenExpression): String? {
        if (whenExpr.subjectExpression == null && whenExpr.subjectVariable == null) return "the when has no subject"
        val bare = bareScope(whenExpr)
        val states = mutableListOf<Pair<String, String>>()
        for (entry in whenExpr.entries) {
            when {
                entry.conditions.any { bareStates(it, bare).isNotEmpty() } -> return "a branch names a member without its type"
                entry.isElse -> return "it has an 'else' branch"
                entry.guard != null -> return "a branch has a guard"
                entry.conditions.size > 1 -> return "a branch has several conditions"
                entry.conditions.any { it.descendants<PsiElement>().any { leaf -> leaf.node.elementType == KtTokens.NULL_KEYWORD } } -> return "a branch tests 'null'"
            }
            states += singleState(entry) ?: return "a branch tests something other than one UiState member"
        }
        if (states.map { it.first }.distinct().size > 1) return "its branches name more than one UiState type"
        if (states.map { it.second }.distinct().size < states.size) return "two branches name the same member"
        val qualifier = states.firstOrNull()?.first ?: return null
        val byValue = whenExpr.entries.any { entry -> entry.conditions.any { it is KtWhenConditionWithExpression } }
        return declarationReason(whenExpr, qualifier, states.map { it.second }, byValue)
    }

    // Branches over members that extend one another can match the same value, and then their order decides which runs: a
    // when is safe to move only when its type resolves to one indexed declaration and every member it names is final and
    // extends no other member. A value branch matches by the subject's equals, so with one the type must also have no
    // subtype outside its body and no member whose equals is its own or a class's other than the type's generated one.
    private fun declarationReason(whenExpr: KtWhenExpression, qualifier: String, named: List<String>, byValue: Boolean): String? {
        val index = index ?: return "the UiState declarations were not indexed"
        val fqName = resolve(whenExpr, qualifier, index) { return it }
        val type = index.types[fqName] ?: return "no sealed $qualifier is declared in the files formatted with it"
        val simple = type.simpleName
        if ((index.declared[simple] ?: 0) > 1) return "a sealed $simple is declared more than once in the files formatted with it"
        val members = type.members
        for (name in named) {
            val member = members[name] ?: return "$simple.$name is not a member of a sealed $simple"
            if (!member.final) return "$simple.$name is open, abstract, sealed or an interface"
        }
        for ((name, member) in members) {
            member.supertypes.firstOrNull { it in members }?.let { return "$simple.$name extends $simple.$it" }
        }
        if (!byValue) return null
        val equalsReason = "a branch matches by equals and"
        type.outsideSubtype?.let { return "$equalsReason $it implements $simple outside its body" }
        if (type.declaresEquals) return "$equalsReason $simple declares equals"
        if (type.extendsClass) return "$equalsReason $simple extends a class, whose equals its members may inherit"
        for ((name, member) in members) {
            if (simple !in member.supertypes) continue
            if (member.declaresEquals) return "$equalsReason $simple.$name declares equals"
            member.classSupertypes.firstOrNull { it != simple }?.let { return "$equalsReason $simple.$name extends $it, whose equals it may inherit" }
        }
        return null
    }

    // The qualified name of the type a when's qualifier names, as Kotlin resolves its first segment: a class nested in an
    // enclosing class, then an explicit import, the file's package, a star import, and last the qualifier read as fully
    // qualified. An import alias of that segment, or two star imports that both bring it, are passed to unsafe instead.
    private inline fun resolve(whenExpr: KtWhenExpression, qualifier: String, index: Index, unsafe: (String) -> Nothing): String {
        val head = qualifier.substringBefore('.')
        val rest = qualifier.removePrefix(head)
        val file = whenExpr.containingKtFile
        val directives = file.importDirectives
        if (directives.any { it.aliasName == head }) unsafe("its type is named through the import alias $head")
        for (owner in generateSequence(whenExpr.parent) { it.parent }.filterIsInstance<KtClassOrObject>()) {
            val ownerName = owner.fqName?.asString() ?: continue
            if (owner.body?.declarations.orEmpty().any { it is KtClassOrObject && it.name == head }) return "$ownerName.$head$rest"
        }
        directives.firstOrNull { !it.isAllUnder && it.importedName?.asString() == head }?.let { return it.importedFqName!!.asString() + rest }
        val packageName = file.packageFqName.asString()
        val local = if (packageName.isEmpty()) head else "$packageName.$head"
        if (local in index.classifiers) return local + rest
        val starred = directives.filter { it.isAllUnder }.mapNotNull { it.importedFqName?.asString()?.let { star -> "$star.$head" } }.filter { it in index.classifiers }
        if (starred.size > 1) unsafe("two star imports bring $head")
        return (starred.singleOrNull() ?: return qualifier) + rest
    }
}
