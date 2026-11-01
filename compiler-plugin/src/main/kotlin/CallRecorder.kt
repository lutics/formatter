package codes.stable.formatter.kotlin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import java.io.File

val OUT = CompilerConfigurationKey<String>("kotlin out")
val IMPORTS = CompilerConfigurationKey<String>("kotlin imports")

class KotlinCommandLineProcessor : CommandLineProcessor {

    override val pluginId = "codes.stable.formatter.kotlin"
    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = "out",
            valueDescription = "<path>",
            description = "where to append call records",
            required = true,
        ),
        CliOption(
            optionName = "imports",
            valueDescription = "<path>",
            description = "where to append per-file import records",
            required = false,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        configuration.put(
            key = if (option.optionName == "imports") IMPORTS else OUT,
            value = value,
        )
    }
}

class KotlinRegistrar : CompilerPluginRegistrar() {

    override val pluginId = "codes.stable.formatter.kotlin"
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val out = File(configuration.get(OUT) ?: return)
        val imports = configuration.get(IMPORTS)?.let(::File)
        FirExtensionRegistrarAdapter.registerExtension(
            extension = object : FirExtensionRegistrar() {
                override fun ExtensionRegistrarContext.configurePlugin() {
                    val checkers = { session: FirSession ->
                        Checkers(
                            session = session,
                            out = out,
                            imports = imports,
                        )
                    }
                    +checkers
                }
            },
        )
    }
}

class Checkers(
    session: FirSession,
    out: File,
    imports: File?,
) : FirAdditionalCheckersExtension(session) {

    override val expressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(CallRecorder(out))
    }

    override val declarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers: Set<FirDeclarationChecker<FirFile>> = setOfNotNull(imports?.let { ImportRecorder(it) })
    }
}

class CallRecorder(
    private val out: File,
) : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val file = context.containingFilePath ?: return
        val symbol = expression.calleeReference.toResolvedFunctionSymbol() ?: return
        val callee = expression.calleeReference.source ?: return
        val mapping = expression.resolvedArgumentMapping ?: return
        val java = symbol.origin is FirDeclarationOrigin.Java || !symbol.resolvedStatus.hasStableParameterNames
        val args = expression.argumentList.arguments.map { arg ->
            val unwrapped = (arg as? FirNamedArgumentExpression)?.expression ?: arg
            val param = mapping[unwrapped] ?: mapping[arg]
            val start = arg.source?.startOffset ?: -1
            """{"start":$start,"named":${arg is FirNamedArgumentExpression},"param":"${param?.name ?: ""}","vararg":${param?.isVararg ?: false}}"""
        }
        val line = """{"file":"$file","callee":${callee.startOffset},"name":"${symbol.name}","java":$java,"args":[${args.joinToString(",")}]}"""
        synchronized(CallRecorder::class.java) { out.appendText(line + "\n") }
    }
}
