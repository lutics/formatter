package io.github.lutics.formatter.gradle

import org.gradle.api.logging.Logger
import java.io.File

private const val MAX_LAYOUT_ROUNDS = 5

// format's first step. PREPARE, then LAYOUT and the layout format (as_format) on every file; after that, LAYOUT again
// and the layout format on only the files that LAYOUT round changed, until a LAYOUT round changes nothing.
internal fun prepare(files: Set<File>, phases: Phases, logger: Logger) {
    logger.lifecycle("format: prepare: PREPARE changed ${phases.run("PREPARE", files).size} of ${files.size} files")
    for (round in 1..MAX_LAYOUT_ROUNDS) {
        val changed = phases.run("LAYOUT", files).map(::File).toSet()
        logger.lifecycle("format: prepare: LAYOUT round $round changed ${changed.size} files")
        if (round > 1 && changed.isEmpty()) break
        val formatted = if (round == 1) files else changed
        logger.lifecycle("format: prepare: as_format round $round changed ${phases.run("AS_FORMAT", formatted).size} of ${formatted.size} files")
    }
}
