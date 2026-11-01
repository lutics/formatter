package io.github.lutics.formatter

import io.github.lutics.formatter.steps.BodyBlankLineStep
import io.github.lutics.formatter.steps.AsFormatStep
import io.github.lutics.formatter.steps.BraceIfStep
import io.github.lutics.formatter.steps.ClassParamsStep
import io.github.lutics.formatter.steps.CommentsStep
import io.github.lutics.formatter.steps.CompanionStep
import io.github.lutics.formatter.steps.ImportsStep
import io.github.lutics.formatter.steps.JoinCallsStep
import io.github.lutics.formatter.steps.LambdaBodyStep
import io.github.lutics.formatter.steps.NameCallsStep
import io.github.lutics.formatter.steps.OpenCallsStep
import io.github.lutics.formatter.steps.ValElvisStep
import java.io.File

// What the recording compile learned about every call site, keyed by file path and the call's position at record time.
// Loaded by the naming step's owner; steps before the compile get null.
interface CallRecords

// One step of the fleet standard: a whole-file text transform. A step leaves a file it has nothing to do with
// byte-identical, and returns the same text again when run on its own output.
interface Step {
    val id: String

    fun apply(path: String, text: String, calls: CallRecords?): String
}

// The fleet standard in its fixed order. PREPARE runs before the recording compile and may move lines; FINISH runs on
// the recorded line positions first (naming, joins), then the formatter rules settle layout over every file (Main's
// --format, not FormatterStep: a file the rules cannot format keeps what FINISH wrote instead of failing the run).
object Pipeline {
    val PREPARE: List<Step> = listOf(CommentsStep, ClassParamsStep)

    // Run before each layout round (AsFormatStep, until a round changes nothing).
    val LAYOUT: List<Step> = listOf(BraceIfStep, OpenCallsStep)
    // ImportsStep changes line counts, so it runs after the steps keyed by recorded lines
    val FINISH: List<Step> = listOf(NameCallsStep, JoinCallsStep, ValElvisStep, CompanionStep, BodyBlankLineStep, LambdaBodyStep, ImportsStep)

    // Every step by id, for running one alone (--step).
    val ALL: List<Step> get() = PREPARE + LAYOUT + AsFormatStep + FINISH + FormatterStep

    // Reads the recording compile's output into CallRecords; set by the naming step's owner.
    var loadCalls: ((File) -> CallRecords)? = { io.github.lutics.formatter.steps.Calls.load(it) }

    fun run(steps: List<Step>, files: List<File>, calls: CallRecords?) {
        for (f in files) {
            val text = f.readText()
            var s = text
            for (step in steps) s = step.apply(f.path, s, calls)
            if (s != text) f.writeText(s)
        }
    }
}

// The formatter rules (Rules.kt) as the last step.
object FormatterStep : Step {
    override val id = "formatter"

    override fun apply(path: String, text: String, calls: CallRecords?): String = Engine.format(path, text, RULES)
}
