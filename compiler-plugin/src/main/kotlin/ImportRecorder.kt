package codes.stable.formatter.kotlin

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.resolve.FirResolvedSymbolOrigin
import org.jetbrains.kotlin.fir.resolve.diagnostics.ContextSensitiveResolutionMightBeUsedInsteadOfImport
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvedImport
import org.jetbrains.kotlin.fir.expressions.FirComponentCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeDiagnosticWithCandidates
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.defaultImportsProvider
import org.jetbrains.kotlin.fir.scopes.impl.typeAliasConstructorInfo
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirDelegateFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirErrorPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructorDelegationReferenceExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntrySuperclassReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

// Records, per file, what IntelliJ's K2 Optimize Imports collects (UsedReferencesCollector in plugins/kotlin/code-insight/
// kotlin.code-insight.k2/.../imports/) and the classifier lookups its OptimizedImportsBuilder makes, so the engine's imports step can
// rebuild the import list without an IDE. IntelliJ walks the PSI references and resolves them with the Analysis API; here the same
// PSI is walked and each reference is resolved through the FIR built for it, matched by source range and element type, which the
// light tree and PSI share. The compile keeps the light-tree frontend: the PSI one (-Xuse-fir-lt=false) breaks other compiler
// plugins' code generation (Koin's).
class ImportRecorder(private val out: File) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    private val headerWritten = AtomicBoolean(false)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val session = context.session
        val ktFile = declaration.source?.psi as? KtFile ?: run {
            val text = declaration.source?.text?.toString() ?: return
            Parser.factory.createFile(if (declaration.name.endsWith(".kts")) "x.kts" else "x.kt", text)
        }
        // a file the recorder cannot read gets no record, which leaves its imports alone; the compile itself must not fail
        val record = try {
            FileRecord(declaration, ktFile, session).json()
        } catch (e: Exception) {
            System.err.println("w: imports record skipped for ${declaration.sourceFile?.path}: $e")
            return
        }
        val header = if (headerWritten.compareAndSet(false, true)) header(session) + "\n" else ""
        synchronized(ImportRecorder::class.java) { out.appendText(header + record + "\n") }
    }

    // the platform's default imports, which IntelliJ's isImportedByDefault reads from the same provider
    private fun header(session: FirSession): String {
        val provider = session.defaultImportsProvider
        return obj(
            "defaults" to arr(provider.getDefaultImports(true).map { str(it.toString()) }),
            "excluded" to arr(provider.excludedImports.map { str(it.asString()) }),
        )
    }
}

// A PSI parser of its own, for the light-tree compile
@OptIn(CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class)
private object Parser {
    val factory: KtPsiFactory by lazy {
        // no compiler plugins: parsing reads the extension storage even when it is empty
        val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
        val environment = KotlinCoreEnvironment.createForProduction(Disposer.newDisposable(), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        KtPsiFactory(environment.project, markGenerated = false)
    }
}

// A FIR source or PSI element by its range and node type, the same in the light tree and PSI.
private data class Key(val start: Int, val end: Int, val type: IElementType?)

private fun PsiElement.key() = Key(textRange.startOffset, textRange.endOffset, node.elementType)

private fun KtSourceElement.key() = Key(startOffset, endOffset, elementType)

// FIR elements by the PSI element at their source
private class SourceIndex<T> {
    val byKey = HashMap<Key, MutableList<T>>()

    operator fun get(e: PsiElement?): List<T>? = e?.let { byKey[it.key()] }

    fun add(source: KtSourceElement?, element: T) {
        if (source != null) byKey.getOrPut(source.key(), ::ArrayList).add(element)
    }
}

// One PSI reference as IntelliJ sees it: the names it resolves by and the symbols it resolves to.
private class Ref(val element: KtElement, val names: List<Name>, val resolved: Boolean, val targets: List<Target>, val contextSensitive: Boolean = false)

// A resolved symbol, adjusted as IntelliJ's adjustSymbolIfNeeded does (constructor to its class), with the FIR access that reached
// it, whose dispatch receiver tells a member access from a static import.
private class Target(val symbol: FirBasedSymbol<*>, val access: FirQualifiedAccessExpression?, val viaConstructor: Boolean = false)

// IntelliJ's SymbolInfo: what a symbol is imported as.
private sealed class Info {
    data class ClassLike(val classId: ClassId) : Info()

    data class Callable(val callableId: CallableId) : Info()

    object Unsupported : Info()

    val fqName: FqName?
        get() = when (this) {
            is ClassLike -> classId.asSingleFqName()
            is Callable -> callableId.asSingleFqName()
            Unsupported -> null
        }

    // as written into the record
    val key get() = if (this is ClassLike) "C:$fqName" else if (this is Callable) "F:$fqName" else "U"
}

@OptIn(SymbolInternals::class)
private class FileRecord(private val firFile: FirFile, private val file: KtFile, private val session: FirSession) {
    // FIR elements by the PSI of their callee reference, and by their own PSI
    private val callees = SourceIndex<FirResolvable>()
    private val nodes = SourceIndex<FirElement>()

    init {
        val seen = IdentityHashMap<FirElement, Unit>()
        firFile.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (seen.put(element, Unit) != null) return
                nodes.add(element.source, element)
                if (element is FirResolvable) callees.add(element.calleeReference.source, element)
                element.acceptChildren(this)
            }
        })
    }

    private val packageFqName = file.packageFqName

    // the PSI element of a FIR source
    private fun psiAt(source: KtSourceElement): PsiElement? {
        var e: PsiElement? = file.findElementAt(source.startOffset)
        while (e != null && e !is KtFile && e.textRange.startOffset == source.startOffset) {
            if (e.textRange.endOffset == source.endOffset && e.node.elementType == source.elementType) return e
            e = e.parent
        }
        return null
    }

    // the symbol provider rejects local class ids
    private fun classLike(id: ClassId): FirClassLikeSymbol<*>? = if (id.isLocal) null else session.symbolProvider.getClassLikeSymbolByClassId(id)

    // UsedReferencesCollector.collectImportAliases
    private val aliases: Map<FqName, List<Name>> = file.importDirectives
        .filter { !it.isAllUnder && it.aliasName != null }
        .mapNotNull { it.importPath }
        .groupBy({ it.fqName }, { it.importedName!! })

    fun json(): String {
        val refs = ArrayList<Ref>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitImportList(importList: KtImportList) {}

            override fun visitPackageDirective(directive: KtPackageDirective) {}

            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is KtElement) refs += referencesOf(element)
            }
        })

        // UsedReferencesCollector.collectReferencesFrom
        val unresolved = LinkedHashSet<Name>()
        val usedDeclarations = LinkedHashMap<FqName, LinkedHashSet<Name>>()
        val used = LinkedHashMap<Info, String>()
        val refsOut = ArrayList<String>()
        for (ref in refs) {
            if (!ref.resolved) {
                unresolved += ref.names
                refsOut += refString(ref, emptyList())
                continue
            }
            val infos = ref.targets.map { infoOf(it) }
            // a reference to locals and members only cannot change with the imports, so the conflict check can skip it
            if (infos.any { it != Info.Unsupported }) refsOut += refString(ref, infos)
            for ((target, info) in ref.targets.zip(infos)) {
                if (!needsImport(target, ref)) continue
                val fqName = info.fqName ?: continue
                if (fqName.parent() == packageFqName && fqName !in aliases) continue
                val newNames = (aliases[fqName].orEmpty() + fqName.shortName()).intersect(ref.names.toSet())
                usedDeclarations.getOrPut(fqName, ::LinkedHashSet) += newNames
                used.getOrPut(info) { outerKind(info) }
            }
        }

        val directives = file.importDirectives
        val importText = if (directives.isEmpty()) "" else file.text.substring(directives.first().textRange.startOffset, directives.last().textRange.endOffset)
        return obj(
            "file" to str(firFile.sourceFile?.path ?: file.virtualFile?.path ?: file.name),
            "pkg" to str(packageFqName.asString()),
            "api" to str(session.languageVersionSettings.apiVersion.versionString),
            "imports" to arr(directives.map { d ->
                val path = d.importPath
                if (path == null) "null" else arr(listOf(str(path.fqName.asString()), path.isAllUnder.toString(), path.alias?.let { str(it.asString()) } ?: "null"))
            }),
            "importText" to str(importText),
            "used" to arr(used.map { (info, outer) -> arr(listOf(str(info.fqName!!.asString()), str(if (info is Info.ClassLike) "C" else "F"), str(outer))) }),
            "decl" to obj(*usedDeclarations.map { (fq, names) -> fq.asString() to arr(names.map { str(it.asString()) }) }.toTypedArray()),
            "unresolved" to arr(unresolved.map { str(it.asString()) }),
            "refs" to arr(refsOut.map { str(it) }),
            "cls" to classifierFacts(used.keys),
        )
    }

    private fun refString(ref: Ref, infos: List<Info>) =
        ref.names.joinToString(",") + "|" + (if (ref.element.parent is KtUserType) "t" else "") + "|" + infos.map { it.key }.sorted().joinToString(",")

    // --- the references of one element (the psiReferenceProvider registrations of the Analysis API)

    private fun referencesOf(element: KtElement): List<Ref> = when (element) {
        is KtSimpleNameExpression -> listOfNotNull(simpleName(element))
        is KtCallExpression -> listOfNotNull(invoke(element))
        is KtArrayAccessExpression -> listOf(multi(element, listOf(OperatorNameConventions.GET, OperatorNameConventions.SET), callees[element].orEmpty()))
        is KtForExpression -> {
            val names = listOf(OperatorNameConventions.ITERATOR, OperatorNameConventions.NEXT, OperatorNameConventions.HAS_NEXT)
            val calls = element.loopRange?.let { range -> callees[range].orEmpty().filter { it.isForLoop() } }.orEmpty()
            listOf(multi(element, names, calls))
        }
        is KtPropertyDelegate -> {
            val names = listOf(OperatorNameConventions.GET_VALUE, OperatorNameConventions.SET_VALUE, OperatorNameConventions.PROVIDE_DELEGATE)
            val calls = element.expression?.let { e -> callees[e].orEmpty().filter { it.isDelegateAccessor() && it.calleeName() in names } }.orEmpty()
            listOf(multi(element, names, calls))
        }
        is KtDestructuringDeclarationEntry -> {
            val index = (element.parent as? KtDestructuringDeclaration)?.entries?.indexOf(element) ?: -1
            val names = listOfNotNull(element.nameAsName, Name.identifier("component${index + 1}"))
            listOf(multi(element, names, callees[element].orEmpty().filterIsInstance<FirComponentCall>()))
        }
        else -> emptyList()
    }

    private fun FirResolvable.calleeName() = (calleeReference as? FirNamedReference)?.name

    private fun FirResolvable.isForLoop() = calleeReference.source?.kind == KtFakeSourceElementKind.DesugaredForLoop

    // getValue/setValue in the generated accessors, provideDelegate around the delegate expression
    private fun FirResolvable.isDelegateAccessor() = calleeReference.source?.kind.let {
        it is KtFakeSourceElementKind.DelegatedPropertyAccessor || it == KtFakeSourceElementKind.WrappedDelegate
    }

    // a desugared call with no FIR call at all (a convention that resolved to nothing) is unresolved
    private fun multi(element: KtElement, names: List<Name>, calls: List<FirResolvable>): Ref {
        val targets = calls.flatMap { targetsOf(it) }
        val resolved = calls.isNotEmpty() && (targets.isNotEmpty() || calls.none { it.calleeReference is FirErrorNamedReference })
        return Ref(element, names, resolved || calls.isEmpty() && element !is KtArrayAccessExpression, targets)
    }

    private fun invoke(call: KtCallExpression): Ref? {
        val callee = call.calleeExpression ?: return null
        val invokes = (callees[callee].orEmpty() + nodes[call].orEmpty() + (call.parent as? KtQualifiedExpression)?.let { nodes[it] }.orEmpty())
            .filterIsInstance<FirImplicitInvokeCall>().distinct()
        // a failed call keeps its implicit invoke candidates on the call itself
        val failed = (callee as? KtSimpleNameExpression)?.let { invokeCandidates(it) }.orEmpty()
        // a call that is not an implicit invoke has an "empty" invoke reference: resolved, no symbols
        if (invokes.isEmpty() && failed.isEmpty()) return null
        return Ref(call, listOf(OperatorNameConventions.INVOKE), true, invokes.flatMap { targetsOf(it) } + failed)
    }

    private fun invokeCandidates(e: KtSimpleNameExpression): List<Target> {
        if (e.getReferencedNameAsName() == OperatorNameConventions.INVOKE) return emptyList()
        return callees[e].orEmpty().filter { isPlainAccess(it) && it.calleeReference is FirErrorNamedReference }
            .flatMap { access -> targetsOf(access, candidates(access).filter { (it as? FirCallableSymbol<*>)?.name == OperatorNameConventions.INVOKE }) }
    }

    private fun isPlainAccess(it: FirResolvable) = it !is FirImplicitInvokeCall && it !is FirComponentCall && !it.isForLoop() && !it.isDelegateAccessor()

    private fun simpleName(e: KtSimpleNameExpression): Ref? {
        if (e is KtLabelReferenceExpression || e is KtEnumEntrySuperclassReferenceExpression) return null
        if (e.parent is KtValueArgumentName || e.parent is KtInstanceExpressionWithLabel) return null
        if (e is KtConstructorDelegationReferenceExpression) return Ref(e, emptyList(), true, callees[e].orEmpty().flatMap { targetsOf(it) })
        if (e is KtOperationReferenceExpression) return operation(e)
        val names = listOf(e.getReferencedNameAsName())
        if (e.parent is KtUserType) return typeReference(e, names)

        val accesses = callees[e].orEmpty().filter(::isPlainAccess)
        if (accesses.isNotEmpty()) {
            if (e.parent is KtCallExpression && invokeCandidates(e).isNotEmpty()) return Ref(e, names, true, emptyList())
            return resolvedBy(e, names, accesses, contextSensitive(e, accesses.first() as? FirElement))
        }
        qualifierTargets(e)?.let { (q, targets) -> return Ref(e, names, true, targets, contextSensitive(e, q)) }
        return Ref(e, names, true, emptyList())
    }

    private fun resolvedBy(e: KtElement, names: List<Name>, calls: List<FirResolvable>, contextSensitive: Boolean = false): Ref {
        val targets = calls.flatMap { targetsOf(it) }
        return Ref(e, names, targets.isNotEmpty() || calls.any { it.calleeReference !is FirErrorNamedReference }, targets, contextSensitive)
    }

    // KaFirResolver.contextSensitiveResolutionStatus is Used or ImportCanBeRemoved: resolved by the expected type, or resolvable so
    private fun contextSensitive(e: KtSimpleNameExpression, fir: FirElement?): Boolean {
        if (!contextSensitiveResolution || fir == null) return false
        val used = when (fir) {
            is FirResolvedTypeRef -> fir.resolvedSymbolOrigin == FirResolvedSymbolOrigin.ContextSensitive
            is FirResolvedQualifier -> fir.resolvedSymbolOrigin == FirResolvedSymbolOrigin.ContextSensitive
            is FirResolvable -> (fir.calleeReference as? FirResolvedNamedReference)?.resolvedSymbolOrigin == FirResolvedSymbolOrigin.ContextSensitive
            else -> false
        }
        if (used) return true
        // the hint sits on the outermost resolved node: the `is`/`as` call around a type, or the qualified expression of a selector
        val holder = when (fir) {
            is FirResolvedTypeRef -> e.parents.firstOrNull { it is KtBinaryExpressionWithTypeRHS || it is KtIsExpression }?.let { nodes[it]?.firstOrNull { n -> n is FirTypeOperatorCall } }
            else -> (e.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == e }?.let { q -> nodes[q]?.firstOrNull { it is FirQualifiedAccessExpression || it is FirResolvedQualifier } } ?: fir
        }
        val diagnostics = when (holder) {
            is FirQualifiedAccessExpression -> holder.nonFatalDiagnostics
            is FirResolvedQualifier -> holder.nonFatalDiagnostics
            is FirTypeOperatorCall -> holder.nonFatalDiagnostics
            else -> emptyList()
        }
        return ContextSensitiveResolutionMightBeUsedInsteadOfImport in diagnostics
    }

    private val contextSensitiveResolution = session.languageVersionSettings.supportsFeature(LanguageFeature.ContextSensitiveResolutionUsingExpectedType)

    private fun operation(e: KtOperationReferenceExpression): Ref? {
        val token = e.operationSignTokenType
        // `==` resolves to a member equals, `===` to nothing: neither can need an import
        if (isUnaryOperatorOnIntLiteral(e)) return null
        if (token == KtTokens.EQEQ || token == KtTokens.EXCLEQ || token == KtTokens.EQEQEQ || token == KtTokens.EXCLEQEQEQ) return null
        if (token == null) {
            // an infix call by name (`a to b`)
            val calls = callees[e].orEmpty()
            return if (calls.isEmpty()) Ref(e, listOf(e.getReferencedNameAsName()), true, emptyList()) else resolvedBy(e, listOf(e.getReferencedNameAsName()), calls)
        }
        val names = operatorNames(e)
        if (names.isEmpty()) return null
        // a compound assignment or an increment desugars to a call whose callee is the whole expression
        val desugared = callees[e.parent].orEmpty().filter { it.calleeName() in names || it.calleeReference is FirErrorNamedReference }
        val calls = (callees[e].orEmpty() + desugared).distinct().toMutableList()
        assignedArray(e)?.let { array -> calls += callees[array].orEmpty().filter { it.calleeName() == OperatorNameConventions.SET } }
        if (calls.isEmpty()) return Ref(e, names, false, emptyList())
        return resolvedBy(e, names, calls)
    }

    // isUnaryOperatorOnIntLiteralReference: `-1` is a literal, not a call
    private fun isUnaryOperatorOnIntLiteral(e: KtOperationReferenceExpression): Boolean {
        if (e.operationSignTokenType != KtTokens.PLUS && e.operationSignTokenType != KtTokens.MINUS) return false
        val base = (e.parent as? KtUnaryExpression)?.baseExpression?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
        return base is KtConstantExpression && base.node.elementType == KtNodeTypes.INTEGER_CONSTANT
    }

    // KaSimpleNameReferenceBase.operatorNames
    private fun operatorNames(expression: KtOperationReferenceExpression): List<Name> = buildList {
        val tokenType = expression.operationSignTokenType ?: return@buildList
        val parent = expression.parent
        val name = OperatorConventions.getNameForOperationSymbol(tokenType, parent is KtUnaryExpression, parent is KtBinaryExpression)
        if (name != null) {
            add(name)
            val counterpart = OperatorConventions.ASSIGNMENT_OPERATION_COUNTERPARTS[tokenType]
            if (counterpart != null) add(OperatorConventions.getNameForOperationSymbol(counterpart, false, true)!!)
        }
        if (assignedArray(expression) != null) add(OperatorNameConventions.SET)
    }

    // the indexed expression an assignment or an increment writes, which makes `set` one of the operation's calls
    private fun assignedArray(expression: KtOperationReferenceExpression): KtArrayAccessExpression? {
        val tokenType = expression.operationSignTokenType
        val parent = expression.parent
        val modified = when {
            parent is KtBinaryExpression && tokenType in KtTokens.ALL_ASSIGNMENTS -> parent.left
            parent is KtUnaryExpression && (tokenType == KtTokens.PLUSPLUS || tokenType == KtTokens.MINUSMINUS) -> parent.baseExpression
            else -> null
        }
        return modified?.let { KtPsiUtil.safeDeparenthesize(it) } as? KtArrayAccessExpression
    }

    // --- one FIR access to its symbols (KaBaseSimpleNameReference.resolveToSymbols, then adjustSymbolIfNeeded)

    private fun candidates(access: FirResolvable): List<FirBasedSymbol<*>> = when (val ref = access.calleeReference) {
        is FirResolvedNamedReference -> listOf(ref.resolvedSymbol)
        is FirErrorNamedReference -> (ref.diagnostic as? ConeDiagnosticWithCandidates)?.candidateSymbols?.toList().orEmpty()
        else -> emptyList()
    }

    private fun targetsOf(access: FirResolvable, candidates: List<FirBasedSymbol<*>> = candidates(access)): List<Target> =
        candidates.mapNotNull { c -> adjust(c, access)?.let { Target(it, access as? FirQualifiedAccessExpression, c is FirConstructorSymbol) } }

    private fun adjust(symbol: FirBasedSymbol<*>, access: FirResolvable): FirBasedSymbol<*>? = when {
        symbol is FirConstructorSymbol -> symbol.typeAliasConstructorInfo?.typeAliasSymbol ?: run {
            val owner = (symbol.resolvedReturnType.lowerBoundIfFlexible() as? ConeClassLikeType)?.lookupTag?.toSymbol(session)
            // an inner class constructor cannot be reached through an import
            owner?.takeUnless { it is FirRegularClassSymbol && it.resolvedStatus.isInner }
        }
        symbol is FirSyntheticFunctionSymbol && symbol.origin == FirDeclarationOrigin.SamConstructor -> {
            // a SAM constructor stands for its interface, or for the typealias it was called through
            val type = (access as? FirExpression)?.resolvedType?.lowerBoundIfFlexible()
            val alias = (type?.abbreviatedTypeOrSelf as? ConeClassLikeType)?.lookupTag?.toSymbol(session)
            alias ?: (symbol.resolvedReturnType.lowerBoundIfFlexible() as? ConeClassLikeType)?.lookupTag?.toSymbol(session)
        }
        else -> symbol
    }

    // --- qualifiers (FirReferenceResolveHelper.getSymbolsForResolvedQualifier)

    private fun qualifierTargets(e: KtSimpleNameExpression): Pair<FirResolvedQualifier, List<Target>>? {
        var element: PsiElement? = e
        while (element != null) {
            nodes[element]?.firstNotNullOfOrNull { it as? FirResolvedQualifier }?.let { return it to qualifierSymbols(it, e) }
            val parent = element.parent
            element = when {
                parent is KtDotQualifiedExpression -> parent
                parent is KtCallExpression && parent.calleeExpression == element -> parent
                else -> null
            }
        }
        return null
    }

    private fun qualifierSymbols(q: FirResolvedQualifier, e: KtSimpleNameExpression): List<Target> {
        val symbol = q.qualifierSymbol ?: return emptyList() // a package
        val referenced: FirClassLikeSymbol<*> = if (symbol is FirRegularClassSymbol && q.resolvedToCompanionObject) symbol.companionObjectSymbol ?: symbol else symbol
        // `Foo.Bar` stands for its whole dotted expression only when the qualifier's own PSI is its selector (getQualifiedElement)
        val psi = q.source?.let { psiAt(it) }
        val qualified = when (psi) {
            is KtSimpleNameExpression, is KtCallExpression -> (psi.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == psi } ?: psi
            else -> psi
        } as? KtExpression
        if (qualified !is KtDotQualifiedExpression || referenced.classId.isLocal) return listOf(Target(owner(referenced, q, e), null))
        var classId: ClassId? = if (referenced is FirRegularClassSymbol && referenced.resolvedStatus.isCompanion) {
            // the deepest qualifier is the companion itself; above it, a companion reached through its class name drops its own name
            if (selectorName(qualified) == e) return listOf(Target(owner(referenced, q, e), null))
            if (q.resolvedToCompanionObject) referenced.classId.outerClassId else referenced.classId
        } else referenced.classId
        // walk the dotted segments from the selector outwards: classes while the class id has outer classes, then packages
        var access: KtExpression = qualified
        while (classId != null) {
            val dotted = access as? KtDotQualifiedExpression
            if (dotted == null) {
                if (access == e || (access as? KtCallExpression)?.calleeExpression == e) {
                    return listOfNotNull(classLike(classId)?.let { Target(it, null) })
                }
                return emptyList()
            }
            if (selectorName(dotted) == e) return listOfNotNull(classLike(classId)?.let { Target(it, null) })
            access = dotted.receiverExpression
            classId = classId.outerClassId
        }
        return emptyList() // a package segment
    }

    private fun selectorName(dotted: KtDotQualifiedExpression): KtExpression? = dotted.selectorExpression.let { if (it is KtCallExpression) it.calleeExpression else it }

    // a class name standing for its companion object (isImplicitReferenceToCompanion) refers to the class
    private fun owner(symbol: FirClassLikeSymbol<*>, q: FirResolvedQualifier, e: KtSimpleNameExpression): FirClassLikeSymbol<*> {
        if (q.resolvedToCompanionObject && symbol is FirRegularClassSymbol && symbol.resolvedStatus.isCompanion && e.getReferencedNameAsName() != symbol.name) {
            return symbol.getContainingClassSymbol() ?: symbol
        }
        return symbol
    }

    // --- types (FirReferenceResolveHelper.getSymbolsForResolvedTypeRef)

    private fun typeReference(e: KtSimpleNameExpression, names: List<Name>): Ref {
        var top = e.parent as KtUserType
        var segmentsToSkip = 0
        while (true) {
            val parent = top.parent as? KtUserType ?: break
            if (parent.qualifier != top) break
            top = parent
            segmentsToSkip++
        }
        val type = typeOf(top) ?: return Ref(e, names, true, emptyList())
        if (segmentsToSkip == 0) {
            val target = (type.abbreviatedTypeOrSelf as? ConeClassLikeType)?.lookupTag?.toSymbol(session)
            if (target != null) return Ref(e, names, true, listOf(Target(target, null)), contextSensitive(e, typeRefOf(top)))
            // unresolved, or a type argument mismatch that still names its class
            val candidate = ((type as? ConeErrorType)?.diagnostic as? ConeDiagnosticWithCandidates)?.candidateSymbols?.firstOrNull()
            return if (candidate != null) Ref(e, names, true, listOf(Target(candidate, null))) else Ref(e, names, type !is ConeErrorType, emptyList())
        }
        if (type is ConeErrorType) return Ref(e, names, true, emptyList())
        var classifier: FirClassLikeSymbol<*>? = type.toRegularClassSymbol(session)
        repeat(segmentsToSkip) { classifier = classifier?.getContainingClassSymbol() }
        return Ref(e, names, true, listOfNotNull(classifier?.let { Target(it, null) }))
    }

    private fun typeRefOf(userType: KtUserType): FirResolvedTypeRef? =
        (userType.parent as? KtTypeReference)?.let { ref -> nodes[ref]?.firstNotNullOfOrNull { it as? FirResolvedTypeRef } }

    // The cone type of a written user type: from the nearest enclosing type reference FIR kept, then down the PSI alongside it.
    private fun typeOf(userType: KtUserType): ConeKotlinType? {
        val path = ArrayList<PsiElement>()
        var element: PsiElement = userType
        while (true) {
            path += element
            if (element is KtTypeReference) {
                val fir = nodes[element]?.firstNotNullOfOrNull { it as? FirResolvedTypeRef }
                if (fir != null) {
                    var type = fir.coneType
                    val param = element.parent as? KtParameter
                    // a vararg parameter's FIR type is the array
                    if (param != null && param.isVarArg && param.typeReference == element) type = type.arrayElementType() ?: type
                    return descend(path.asReversed(), 1, type)
                }
            }
            element = element.parent ?: return null
            if (element is KtFile || element is KtClassOrObject) return null
        }
    }

    // path[0] is the type reference with a known type; follow the PSI down to path.last() alongside the cone type
    private fun descend(path: List<PsiElement>, index: Int, type: ConeKotlinType): ConeKotlinType? {
        if (index >= path.size) return type
        val t = type.lowerBoundIfFlexible()
        return when (val element = path[index]) {
            is KtUserType -> {
                if (index == path.size - 1) return t
                // user type, type argument list, projection, type reference
                val projection = path.getOrNull(index + 2) as? KtTypeProjection ?: return null
                val argIndex = element.typeArguments.indexOf(projection)
                if (argIndex < 0) return null
                val owner = (t.abbreviatedTypeOrSelf as? ConeClassLikeType) ?: return null
                val arg = owner.typeArguments.getOrNull(argIndex) as? ConeKotlinTypeProjection ?: return null
                if (path.getOrNull(index + 3) != projection.typeReference) return null
                descend(path, index + 4, arg.type)
            }
            is KtFunctionType -> {
                if (!t.isSomeFunctionType(session)) return null
                val coneArgs = (t as? ConeClassLikeType)?.typeArguments ?: return null
                val parts = buildList<KtTypeReference?> {
                    element.contextReceiverList?.typeReferences()?.forEach { add(it) }
                    element.receiverTypeReference?.let { add(it) }
                    element.parameters.forEach { add(it.typeReference) }
                    add(element.returnTypeReference)
                }
                val nextIndex = (index + 1 until path.size).firstOrNull { path[it] is KtTypeReference } ?: return null
                val at = parts.indexOf(path[nextIndex] as KtTypeReference)
                if (at < 0) return null
                val arg = coneArgs.getOrNull(at) as? ConeKotlinTypeProjection ?: return null
                descend(path, nextIndex + 1, arg.type)
            }
            is KtNullableType, is KtTypeReference -> descend(path, index + 1, t)
            is KtTypeElement -> null
            else -> descend(path, index + 1, t)
        }
    }

    // --- ReferencedSymbol.needsImportsToResolve

    private fun needsImport(target: Target, ref: Ref): Boolean {
        val symbol = target.symbol
        if (ref.contextSensitive || isLocal(symbol)) return false
        val name = when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId.shortClassName
            is FirCallableSymbol<*> -> symbol.name
            else -> return false
        }
        val notAliased = name in ref.names
        if (notAliased && accessibleAsMemberCallable(target, ref.element)) return false
        if (notAliased && accessibleAsMemberClassifier(symbol, ref.element)) return false
        return canBeResolvedViaImport(target, ref)
    }

    private fun isLocal(symbol: FirBasedSymbol<*>): Boolean = when (symbol) {
        is FirClassLikeSymbol<*> -> symbol.classId.isLocal
        is FirAnonymousObjectSymbol -> true
        is FirCallableSymbol<*> -> symbol.callableId?.isLocal != false || symbol is FirValueParameterSymbol || symbol is FirBackingFieldSymbol ||
            symbol is FirDelegateFieldSymbol || symbol is FirLocalPropertySymbol || symbol is FirErrorPropertySymbol
        else -> true
    }

    private fun containingClass(symbol: FirCallableSymbol<*>): FirClassLikeSymbol<*>? =
        symbol.callableId?.classId?.let { classLike(it) }

    private fun isJava(owner: FirClassLikeSymbol<*>?) = owner?.origin is FirDeclarationOrigin.Java

    private fun isEnumStatic(symbol: FirCallableSymbol<*>): Boolean {
        if (symbol is FirEnumEntrySymbol) return true
        val owner = containingClass(symbol) as? FirRegularClassSymbol ?: return false
        return owner.classKind == ClassKind.ENUM_CLASS && symbol.resolvedStatus.isStatic &&
            symbol.name.asString().let { it == "values" || it == "valueOf" || it == "entries" }
    }

    private fun unwrap(e: FirExpression?): FirExpression? = if (e is FirSmartCastExpression) unwrap(e.originalExpression) else e

    // an object member or Java static reached through an import has its owner as an implicit qualifier receiver
    private fun isImportedReceiver(e: FirExpression?) = e is FirResolvedQualifier && e.source?.kind == KtFakeSourceElementKind.ImplicitReceiver

    // isAccessibleAsMemberCallable
    private fun accessibleAsMemberCallable(target: Target, element: KtElement): Boolean {
        val symbol = target.symbol as? FirCallableSymbol<*> ?: return false
        val owner = containingClass(symbol) ?: return false
        if (isEnumStatic(symbol)) return lexicalStatic(owner.classId, element)
        val dispatch = unwrap(target.access?.dispatchReceiver)
        if (dispatch != null && !isImportedReceiver(dispatch)) return true
        return isJava(owner) && symbol.resolvedStatus.isStatic && lexicalStatic(owner.classId, element)
    }

    // isAccessibleAsMemberClassifier
    private fun accessibleAsMemberClassifier(symbol: FirBasedSymbol<*>, element: KtElement): Boolean {
        val outer = (symbol as? FirClassLikeSymbol<*>)?.classId?.outerClassId ?: return false
        return lexicalClassifier(outer, element)
    }

    // the classes whose bodies enclose the element, innermost first
    private fun enclosingClasses(element: PsiElement): List<FirClassSymbol<*>> = element.parents.mapNotNull { p ->
        when (p) {
            is KtClassOrObject -> nodes[p]?.firstNotNullOfOrNull { (it as? FirRegularClass)?.symbol ?: (it as? FirAnonymousObject)?.symbol }
            is KtObjectLiteralExpression -> nodes[p.objectDeclaration]?.firstNotNullOfOrNull { (it as? FirAnonymousObject)?.symbol }
            else -> null
        }
    }.toList()

    private fun supertypes(c: FirClassSymbol<*>): List<ClassId> = lookupSuperTypes(c, true, true, session).map { it.lookupTag.classId }

    // Java statics and enum statics resolve without an import inside their owner and its subclasses
    private fun lexicalStatic(owner: ClassId, element: PsiElement): Boolean =
        enclosingClasses(element).any { it.classId == owner || owner in supertypes(it) }

    // nested classifiers resolve without an import inside their owner, its subclasses, and through those classes' companions
    private fun lexicalClassifier(owner: ClassId, element: PsiElement): Boolean = enclosingClasses(element).any { c ->
        val related = listOf(c.classId) + supertypes(c)
        owner in related || related.any { id ->
            (classLike(id) as? FirRegularClassSymbol)?.companionObjectSymbol?.classId == owner
        }
    }

    // ReferencedSymbol.canBeResolvedViaImport
    private fun canBeResolvedViaImport(target: Target, ref: Ref): Boolean {
        val symbol = target.symbol
        if (symbol is FirCallableSymbol<*> && symbol.receiverParameterSymbol != null) return true
        val expression = ref.element as? KtNameReferenceExpression
        val explicitReceiver = expression?.getReceiverExpression()
            ?: (expression?.parent as? KtCallableReferenceExpression)?.takeIf { it.callableReference == expression }?.receiverExpression
        if (explicitReceiver == null) return true
        // a typealiased inner class constructor relies on the typealias being imported
        if (target.viaConstructor && symbol is FirTypeAliasSymbol && symbol.resolvedExpandedTypeRef.coneType.toRegularClassSymbol(session)?.resolvedStatus?.isInner == true) return true
        // `a.f()` on a `val f: A.() -> Unit`: the receiver belongs to the invoke, the property itself still needs an import
        if (symbol is FirPropertySymbol && symbol.resolvedReturnType.isSomeFunctionType(session) && expression?.parent is KtCallExpression) {
            val invoke = callees[expression].orEmpty().filterIsInstance<FirImplicitInvokeCall>().firstOrNull() ?: return false
            val receiver = (invoke.extensionReceiver ?: invoke.arguments.firstOrNull())?.source?.key()
            return receiver == explicitReceiver.key()
        }
        return false
    }

    // --- ReferencedSymbol.toSymbolInfo / SymbolInfo.computeImportableName

    private fun infoOf(target: Target): Info {
        val symbol = target.symbol
        if (isLocal(symbol)) return Info.Unsupported
        return when (symbol) {
            is FirClassLikeSymbol<*> -> Info.ClassLike(symbol.classId)
            is FirCallableSymbol<*> -> {
                val dispatch = unwrap(target.access?.dispatchReceiver)
                // an implicit receiver stands in for the declaring class: a member dispatched through an object is that object's (KT-60775)
                val substituted = when {
                    dispatch is FirThisReceiverExpression && dispatch.isImplicit -> dispatch.calleeReference.boundSymbol as? FirClassLikeSymbol<*>
                    isImportedReceiver(dispatch) -> (dispatch as FirResolvedQualifier).qualifierSymbol
                    else -> null
                }
                val owner = substituted ?: containingClass(symbol) ?: return symbol.callableId?.let { Info.Callable(it) } ?: Info.Unsupported
                val ownerClass = owner as? FirRegularClassSymbol
                val importable = (isJava(owner) && symbol.resolvedStatus.isStatic) ||
                    (ownerClass?.classKind == ClassKind.ENUM_CLASS && isEnumStatic(symbol)) ||
                    ownerClass?.classKind == ClassKind.OBJECT
                if (importable) Info.Callable(CallableId(owner.classId, symbol.name)) else Info.Unsupported
            }
            else -> Info.Unsupported
        }
    }

    // containingClassSymbol(symbolInfo): "o" for an object, "c" for another class, "" for none
    private fun outerKind(info: Info): String {
        val outer = when (info) {
            is Info.Callable -> info.callableId.classId
            is Info.ClassLike -> info.classId.outerClassId
            Info.Unsupported -> null
        } ?: return ""
        val symbol = classLike(outer) ?: return ""
        return if ((symbol as? FirRegularClassSymbol)?.classKind == ClassKind.OBJECT) "o" else "c"
    }

    // --- the classifier lookups of the star-import check (buildImportingScopes + HierarchicalScope.findClassifiers)

    private fun classifierFacts(used: Collection<Info>): String {
        val names = used.filterIsInstance<Info.ClassLike>().map { it.classId.shortClassName }.toSet()
        if (names.isEmpty()) return "{}"
        // every parent a star import can be generated for: the parents of used symbols and the file's own star imports
        val parents = LinkedHashMap<String, Pair<FqName, ClassId?>>()
        fun add(fq: FqName, classId: ClassId?) {
            parents.getOrPut(fq.asString()) { fq to classId }
        }
        for (info in used) {
            when (info) {
                is Info.Callable -> info.callableId.classId?.let { add(it.asSingleFqName(), it) } ?: add(info.callableId.packageName, null)
                is Info.ClassLike -> info.classId.outerClassId?.let { add(it.asSingleFqName(), it) } ?: add(info.classId.packageFqName, null)
                Info.Unsupported -> {}
            }
        }
        for (resolved in firFile.imports.filterIsInstance<FirResolvedImport>().filter { it.isAllUnder }) {
            val fq = resolved.importedFqName ?: continue
            val classId = resolved.resolvedParentClassId?.takeIf { it.asSingleFqName() == fq }
                ?: resolved.relativeParentClassName?.let { ClassId(resolved.packageFqName, it, isLocal = false) }?.takeIf { it.asSingleFqName() == fq }
            add(fq, classId)
        }
        val provider = session.defaultImportsProvider
        val high = provider.getDefaultImports(false).filter { it.isAllUnder }.map { it.fqName }
        val low = provider.defaultLowPriorityImports.filter { it.isAllUnder }.map { it.fqName }
        for (fq in high) add(fq, null)
        fun lookup(parent: FqName, classId: ClassId?, name: Name): FqName? {
            val id = classId?.createNestedClassId(name) ?: ClassId(parent, name)
            return classLike(id)?.classId?.asSingleFqName()
        }
        return obj(*names.map { name ->
            val pkg = listOfNotNull(lookup(packageFqName, null, name))
            val defaultStar = high.mapNotNull { lookup(it, null, name) }.ifEmpty { low.mapNotNull { lookup(it, null, name) } }
            val stars = parents.mapNotNull { (key, p) -> lookup(p.first, p.second, name)?.let { key to str(it.asString()) } }
            name.asString() to obj(
                "pkg" to arr(pkg.map { str(it.asString()) }),
                "dstar" to arr(defaultStar.map { str(it.asString()) }),
                "stars" to obj(*stars.toTypedArray()),
            )
        }.toTypedArray())
    }
}

private fun str(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

private fun arr(items: List<String>) = items.joinToString(",", "[", "]")

private fun obj(vararg entries: Pair<String, String>) = entries.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + v }
