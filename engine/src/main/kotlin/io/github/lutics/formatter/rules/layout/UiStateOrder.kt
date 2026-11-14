package io.github.lutics.formatter.rules.layout

import io.github.lutics.formatter.Edit
import io.github.lutics.formatter.Engine
import io.github.lutics.formatter.Finding
import io.github.lutics.formatter.Rule
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
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
// A when without a subject is rewritten one run at a time: an unbroken run of branches that each test one distinct member
// against the same local val or parameter S, as `S is T.M` or `S == T.M` (M an object), alone or as the first operand of
// a `&&` chain whose rest is a guard. S cannot change between the tests, at most one member test of the run holds, and
// `&&` evaluates a guard only after its own member test held, so in any order the run evaluates the same guard at most
// once and picks the same branch; the branches around the run do not move. Such a when is rewritten only when reordering
// its runs puts the whole when in order; otherwise it is reported, untouched.
// A value branch (`S == T.M`, or `T.M ->` with a subject) calls the subject's equals and first uses the object M. So
// with one, every member that extends the type must be final (a subclass of an open one, wherever it is declared, could
// bring an equals that matches), S's declared type, where the code spells it, must be the UiState, and neither M nor,
// for a sealed class, the type's constructor may run code when first used (which objects a run compares with depends on
// the order). A branch that shares its line with other code is never moved (permute); the when is then reported.
// The rule has no compiler, so these are left unseen on purpose, and a when they make unsafe can still be rewritten:
// - the qualifier's first segment resolves through the enclosing classes' own nested declarations, the explicit imports,
//   the package and the star imports, over the files formatted together only: a nested class inherited from an enclosing
//   class's supertype, a local class, or a same-named class outside those files that Kotlin would pick is not seen;
// - a value branch calls the subject's equals; the check covers every class in those files that names the type as a
//   supertype (by simple name, through import aliases and typealiases), and a subject whose declared type the code
//   spells, but not a subject whose inferred type (or a property's, or a call's) is wider than the UiState and whose value
//   is some other class with an equals that matches a member;
// - first using an object also initialises the interfaces it implements that declare default methods; a member's other
//   interfaces are not followed.
object UiStateOrder : Rule {
    override val id = "ui-state-order"

    // A member of a sealed *UiState: final when nothing can extend it, the simple names of its supertypes, the ones of those
    // that may be classes (so may carry an equals), whether it declares equals itself, and whether first using it runs code.
    class Member(val final: Boolean, val supertypes: Set<String>, val classSupertypes: Set<String>, val declaresEquals: Boolean, val isObject: Boolean, val runsCode: Boolean)

    // A sealed *UiState; outsideSubtype: a class outside its body that names it as a supertype; runsCode: a sealed class
    // whose constructor, which every member object calls when first used, runs code.
    class SealedType(val simpleName: String, val members: Map<String, Member>, val declaresEquals: Boolean, val extendsClass: Boolean, val outsideSubtype: String?, val runsCode: Boolean)

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
                            isObject = member is KtObjectDeclaration,
                            runsCode = runsCodeOnInit(member),
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
                    runsCode = !type.isInterface() && runsCodeOnInit(type),
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

    // Whether creating an instance runs code of the app's beyond its supertype's constructor: an init block, a property
    // initializer or delegate (a const val is inlined), a constructor parameter's default value, a secondary constructor,
    // or arguments passed to a supertype's constructor.
    private fun runsCodeOnInit(declaration: KtClassOrObject): Boolean =
        declaration.getAnonymousInitializers().isNotEmpty() ||
            declaration.body?.declarations.orEmpty().any { it is KtSecondaryConstructor || (it is KtProperty && !it.hasModifier(KtTokens.CONST_KEYWORD) && (it.hasInitializer() || it.hasDelegate())) } ||
            declaration.primaryConstructorParameters.any { it.hasDefaultValue() } ||
            declaration.superTypeListEntries.any { it is KtSuperTypeCallEntry && it.valueArguments.isNotEmpty() }

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
            val found = when (element) {
                is KtClass -> listOfNotNull(memberEdit(element))
                is KtWhenExpression -> branchEdits(element)
                else -> emptyList()
            }
            val ranges = found.map { TextRange(it.start, it.end) }
            if (ranges.any { range -> claimed.any { it.intersects(range) } }) continue
            claimed += ranges
            edits += found
        }
        return edits
    }

    override fun findings(file: KtFile): List<Finding> =
        file.descendants<KtWhenExpression>().mapNotNull { whenExpr ->
            val outOfOrder = outOfOrderEntry(whenExpr) ?: return@mapNotNull null
            val reason = if (whenExpr.hasNoSubject()) subjectlessPlan(whenExpr).reason else unsafeReason(whenExpr)
            reason ?: return@mapNotNull null
            Finding(outOfOrder.textRange.startOffset, "Branches over a UiState run Initial, Loading, Empty, Success, Error; not reordered because $reason. Reorder it by hand.")
        }

    private fun memberEdit(type: KtClass): Edit? {
        if (!type.isSealed() || type.name?.endsWith("UiState") != true) return null
        val members = type.body?.declarations.orEmpty().filterIsInstance<KtClassOrObject>()
            .filter { !(it is KtObjectDeclaration && it.isCompanion()) && it.name in ORDER }
        return permute(members) { ORDER.indexOf(it.name) }
    }

    private fun branchEdits(whenExpr: KtWhenExpression): List<Edit> {
        if (outOfOrderEntry(whenExpr) == null) return emptyList()
        if (whenExpr.hasNoSubject()) return subjectlessPlan(whenExpr).edits
        if (unsafeReason(whenExpr) != null) return emptyList()
        val ranked = whenExpr.entries.filter { singleState(it)?.second in ORDER }
        return listOfNotNull(permute(ranked) { ORDER.indexOf(singleState(it)!!.second) })
    }

    private fun KtWhenExpression.hasNoSubject() = subjectExpression == null && subjectVariable == null

    // A branch of a when without subject that tests one member against a bare name: `S is T.M` or `S == T.M`, alone or
    // as the leftmost operand of a `&&` chain (the rest is its guard). The condition names no other UiState member, so
    // its place in the order is its member's. Null for any other branch.
    private class MemberTest(val entry: KtWhenEntry, val subject: KtNameReferenceExpression, val qualifier: String, val member: String, val byValue: Boolean)

    private fun memberTest(entry: KtWhenEntry, bare: Map<String, Pair<String, String>>): MemberTest? {
        if (entry.isElse || entry.guard != null) return null
        val condition = entry.conditions.singleOrNull() as? KtWhenConditionWithExpression ?: return null
        var test = KtPsiUtil.safeDeparenthesize(condition.expression ?: return null)
        while (test is KtBinaryExpression && test.operationToken == KtTokens.ANDAND) test = KtPsiUtil.safeDeparenthesize(test.left ?: return null)
        val found = when {
            test is KtIsExpression && !test.isNegated -> {
                val subject = test.leftHandSide as? KtNameReferenceExpression ?: return null
                // `is T.M?` also matches null, which every other member test of the run rejects
                val type = test.typeReference?.typeElement as? KtUserType ?: return null
                MemberTest(entry, subject, type.qualifier?.text ?: return null, type.referencedName ?: return null, byValue = false)
            }
            test is KtBinaryExpression && test.operationToken == KtTokens.EQEQ -> {
                val subject = test.left as? KtNameReferenceExpression ?: return null
                val value = test.right as? KtDotQualifiedExpression ?: return null
                val member = (value.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
                MemberTest(entry, subject, value.receiverExpression.text, member, byValue = true)
            }
            else -> null
        } ?: return null
        if (!QUALIFIER.matches(found.qualifier)) return null
        val named = namedStates(condition) + bareStates(condition, bare)
        return found.takeIf { named.all { it == found.qualifier to found.member } }
    }

    // What a when without subject does: the edits that reorder its runs, or why it is left as it is.
    private class Plan(val edits: List<Edit>, val reason: String?)

    private fun subjectlessPlan(whenExpr: KtWhenExpression): Plan {
        val bare = bareScope(whenExpr)
        val entries = whenExpr.entries
        // the runs: maximal stretches of member tests on one name and one qualifier, broken by any other branch
        val runs = mutableListOf<MutableList<MemberTest>>()
        var previous: MemberTest? = null
        for (entry in entries) {
            val test = memberTest(entry, bare)
            if (test != null && previous != null && previous.subject.getReferencedName() == test.subject.getReferencedName() && previous.qualifier == test.qualifier) {
                runs.last() += test
            } else if (test != null) {
                runs += mutableListOf(test)
            }
            previous = test
        }
        val reordered = entries.toMutableList()
        val moves = mutableListOf<Pair<List<KtWhenEntry>, Map<KtWhenEntry, Int>>>()
        var unsafe: String? = null
        for (run in runs) {
            // only the canonical members move, among the positions they hold, as in a when with a subject
            val ranked = run.filter { it.member in ORDER }
            val sorted = ranked.sortedBy { ORDER.indexOf(it.member) }
            if (sorted == ranked) continue
            val reason = runReason(whenExpr, run) ?: SHARED_LINE.takeIf { ranked.any { !it.entry.ownsLines() } }
            if (reason != null) {
                unsafe = unsafe ?: reason
                continue
            }
            ranked.map { reordered.indexOf(it.entry) }.forEachIndexed { i, position -> reordered[position] = sorted[i].entry }
            moves += ranked.map { it.entry } to ranked.associate { it.entry to ORDER.indexOf(it.member) }
        }
        if (outOfOrderEntry(reordered, bare) != null) {
            return Plan(emptyList(), unsafe ?: "the when has no subject, and the branches out of order are not one unbroken run of `S is T.M` or `S == T.M` tests (each alone or before `&&`) on the same local val or parameter")
        }
        return Plan(moves.mapNotNull { (items, rank) -> permute(items) { rank.getValue(it) } }, null)
    }

    // Why the run's branches could match the same value, or its subject change between the tests; null when reordering
    // the run cannot change which branch runs.
    private fun runReason(whenExpr: KtWhenExpression, run: List<MemberTest>): String? {
        val subject = run.first().subject
        if (!isStableLocal(subject)) return "its subject `${subject.getReferencedName()}` is not a local val or a parameter, so it may change between the tests"
        val members = run.map { it.member }
        if (members.distinct().size < members.size) return "two branches test the same member, so more than one can match"
        val type = (localDeclaration(subject) as? KtCallableDeclaration)?.typeReference
        return declarationReason(whenExpr, run.first().qualifier, members, run.any { it.byValue }, run.filter { it.byValue }.map { it.member }, type?.let { subject.getReferencedName() to it })
    }

    // Whether a bare name resolves to a value that cannot change between two reads: a local val without a delegate or a
    // getter (a delegate's getValue may answer differently each time), a destructured val, or a parameter of an enclosing
    // function, lambda, for loop, catch or when subject. A name that reaches a class body or the file unresolved is a
    // property, which a getter or another thread can change; it, and `it` (whose lambda the PSI cannot tell), are not
    // stable.
    private fun isStableLocal(name: KtNameReferenceExpression): Boolean = when (val declaration = localDeclaration(name)) {
        is KtProperty -> !declaration.isVar && !declaration.hasDelegate() && declaration.getter == null
        is KtDestructuringDeclarationEntry -> (declaration.parent as? KtDestructuringDeclaration)?.isVar == false
        is KtParameter -> true
        else -> false
    }

    // What a bare name resolves to, as Kotlin resolves it through the enclosing local scopes: a local property (a when's
    // subject variable included), a destructured entry, or a parameter of an enclosing function, lambda, for loop or catch
    // (or an entry of one destructured); null when it reaches a class body or the file unresolved.
    private fun localDeclaration(name: KtNameReferenceExpression): KtNamedDeclaration? {
        val id = name.getReferencedName()
        fun KtParameter.declared(): KtNamedDeclaration? = if (this.name == id) this else destructuringDeclaration?.entries?.firstOrNull { it.name == id }
        var child: PsiElement = name
        var scope: PsiElement? = name.parent
        while (scope != null && scope !is KtFile && scope !is KtClassBody) {
            when (scope) {
                is KtBlockExpression -> for (statement in scope.statements.takeWhile { it != child }.asReversed()) {
                    when (statement) {
                        is KtProperty -> if (statement.name == id) return statement
                        is KtDestructuringDeclaration -> statement.entries.firstOrNull { it.name == id }?.let { return it }
                    }
                }
                is KtFunction -> scope.valueParameters.firstNotNullOfOrNull { it.declared() }?.let { return it }
                is KtForExpression -> if (child != scope.loopRange) scope.loopParameter?.declared()?.let { return it }
                is KtCatchClause -> scope.catchParameter?.declared()?.let { return it }
                is KtWhenExpression -> scope.subjectVariable?.takeIf { it.name == id && child != it }?.let { return it }
            }
            child = scope
            scope = scope.parent
        }
        return null
    }

    // Moves the items into canonical order within the positions they hold; null when they already are in order, or when
    // one shares a line with other code: moving it would carry that code along, or leave a separator after a line comment
    // (a when's own checks report that case).
    private fun <T : PsiElement> permute(items: List<T>, rank: (T) -> Int): Edit? {
        val sorted = items.sortedBy(rank)
        if (sorted == items || items.any { !it.ownsLines() }) return null
        val text = items.first().containingFile.text
        val spans = items.map { it.travellingSpan() }
        val start = spans.first().startOffset
        val out = StringBuilder()
        var at = start
        for ((index, span) in spans.withIndex()) {
            out.append(text, at, span.startOffset)
            out.append(sorted[index].travellingSpan().substring(text))
            at = span.endOffset
        }
        return Edit(start, at, out.toString())
    }

    // The first entry whose earliest named state comes before a state an entry above it already named, per UiState type.
    private fun outOfOrderEntry(whenExpr: KtWhenExpression): KtWhenEntry? = outOfOrderEntry(whenExpr.entries, bareScope(whenExpr))

    private fun outOfOrderEntry(entries: List<KtWhenEntry>, bare: Map<String, Pair<String, String>>): KtWhenEntry? {
        val latest = mutableMapOf<String, Int>()
        for (entry in entries) {
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

    // Why moving the branches of this when with a subject could change what it does, or null when it cannot: each branch
    // then tests one distinct final member of one indexed UiState type against the subject, and no equals can make one
    // value match two members, so at most one branch matches and order is not semantics. A when without subject is
    // subjectlessPlan's.
    private fun unsafeReason(whenExpr: KtWhenExpression): String? {
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
        val objects = whenExpr.entries.filter { entry -> entry.conditions.single() is KtWhenConditionWithExpression }.map { singleState(it)!!.second }
        val subject = whenExpr.subjectVariable?.let { variable -> variable.typeReference?.let { variable.name.orEmpty() to it } }
            ?: (whenExpr.subjectExpression as? KtNameReferenceExpression)?.let { name ->
                (localDeclaration(name) as? KtCallableDeclaration)?.typeReference?.let { name.getReferencedName() to it }
            }
        return declarationReason(whenExpr, qualifier, states.map { it.second }, objects.isNotEmpty(), objects, subject)
            ?: SHARED_LINE.takeIf { whenExpr.entries.any { singleState(it)!!.second in ORDER && !it.ownsLines() } }
    }

    private const val SHARED_LINE = "a branch shares its line with other code, which would move with it"

    // Branches over members that extend one another can match the same value, and then their order decides which runs: a
    // when is safe to move only when its type resolves to one indexed declaration and every member it names is final and
    // extends no other member. A value branch matches by the subject's equals, so with one the subject's declared type
    // (subject: its name and type, where the code spells one) must be the UiState, and the type must have no subtype
    // outside its body, no member that is open (a subclass may bring its own equals), and no member whose equals is its own
    // or a class's other than the type's generated one. objects: the members a branch compares with by value, which must
    // be objects whose first use, and the type's constructor's, runs no code.
    private fun declarationReason(whenExpr: KtWhenExpression, qualifier: String, named: List<String>, byValue: Boolean, objects: List<String>, subject: Pair<String, KtTypeReference>?): String? {
        val index = index ?: return "the UiState declarations were not indexed"
        val fqName = resolve(whenExpr, qualifier, index) { return it }
        val type = index.types[fqName] ?: return "no sealed $qualifier is declared in the files formatted with it"
        val simple = type.simpleName
        if ((index.declared[simple] ?: 0) > 1) return "a sealed $simple is declared more than once in the files formatted with it"
        val members = type.members
        for (name in named) {
            val member = members[name] ?: return "$simple.$name is not a member of a sealed $simple"
            if (!member.final) return "$simple.$name is open, abstract, sealed or an interface"
            if (name in objects && !member.isObject) return "$simple.$name is compared with '==' but is not an object"
        }
        for ((name, member) in members) {
            member.supertypes.firstOrNull { it in members }?.let { return "$simple.$name extends $simple.$it" }
        }
        if (!byValue) return null
        val equalsReason = "a branch matches by equals and"
        subject?.let { (name, declared) ->
            var element = declared.typeElement
            if (element is KtNullableType) element = element.innerType
            val exact = element is KtUserType && element.referencedName == simple && element.typeArgumentList == null
            if (!exact) return "$equalsReason its subject `$name` is declared as ${declared.text}, wider than $simple, so another class's equals may match a member"
        }
        type.outsideSubtype?.let { return "$equalsReason $it implements $simple outside its body" }
        if (type.declaresEquals) return "$equalsReason $simple declares equals"
        if (type.extendsClass) return "$equalsReason $simple extends a class, whose equals its members may inherit"
        for ((name, member) in members) {
            if (simple !in member.supertypes) continue
            // a branch need not name it: a value of the type may be an instance of its subclass
            if (!member.final) return "$equalsReason $simple.$name is open, abstract, sealed or an interface, so a subclass may declare an equals that matches"
            if (member.declaresEquals) return "$equalsReason $simple.$name declares equals"
            member.classSupertypes.firstOrNull { it != simple }?.let { return "$equalsReason $simple.$name extends $it, whose equals it may inherit" }
        }
        // comparing with an object first uses it, and the order decides which objects a branch compares with
        objects.firstOrNull { members.getValue(it).runsCode }?.let { return "$simple.$it runs code when it is first used, and the order decides whether a branch compares with it" }
        if (objects.isNotEmpty() && type.runsCode) return "$simple runs code when a member object is first used, and the order decides which ones a branch compares with"
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
