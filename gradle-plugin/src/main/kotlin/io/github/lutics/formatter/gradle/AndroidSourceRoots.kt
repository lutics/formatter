package io.github.lutics.formatter.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import java.io.File

// Kept apart from FormatterPlugin so a JVM module, where AGP is not on the classpath, never loads AGP types.
internal object AndroidSourceRoots {
    // Every source set's java and kotlin directories (main, test, androidTest, the build types and flavors).
    fun of(project: Project): List<File> = project.extensions.getByType(CommonExtension::class.java).sourceSets
        .flatMap { it.java.directories + it.kotlin.directories }
        .map(project::file)

    // Every source set's res directories.
    fun res(project: Project): List<File> = project.extensions.getByType(CommonExtension::class.java).sourceSets
        .flatMap { it.res.directories }
        .map(project::file)

    // Every source set's manifest at its conventional path: the DSL's AndroidSourceFile has a setter and no getter.
    fun manifests(project: Project): List<File> = project.extensions.getByType(CommonExtension::class.java).sourceSets
        .map { project.file("src/${it.name}/AndroidManifest.xml") }
        .filter { it.isFile }
}
