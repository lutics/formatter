package io.github.lutics.formatter.gradle

import org.gradle.api.provider.ListProperty

abstract class FormatterExtension {
    // The Kotlin compile tasks the recording compile runs with the CallRecorder; see FormatterPlugin for the default.
    abstract val recordCompileTasks: ListProperty<String>
}
