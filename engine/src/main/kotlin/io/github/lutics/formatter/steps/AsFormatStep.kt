package io.github.lutics.formatter.steps

import io.github.lutics.formatter.CallRecords
import io.github.lutics.formatter.Engine
import io.github.lutics.formatter.Step
import io.github.lutics.formatter.ide.FormatProcessor
import io.github.lutics.formatter.ide.Indent
import io.github.lutics.formatter.ide.KotlinBlock
import io.github.lutics.formatter.ide.KotlinSpacing

// The IDE's "Reformat Code" with the fleet scheme (the code style kotlin.sh passed to the IDE formatter): the IntelliJ
// formatter with the Kotlin plugin's blocks (ide/), which chops every argument and parameter list of two or more onto
// their own lines, puts `(` and `)` of a list that spans lines on their own lines, and re-indents the whole file.
object AsFormatStep : Step {
    override val id = "as_format"

    private val spacing = KotlinSpacing()

    override fun apply(path: String, text: String, calls: CallRecords?): String {
        val root = KotlinBlock(Engine.parse(path, text).node, Indent.NONE, null, spacing)
        return FormatProcessor(text, root).run {
            process()
            result()
        }
    }
}
