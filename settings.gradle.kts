// jvmToolchain(21) needs a JDK 21; the resolver downloads one where the machine has none
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "formatter"
include("engine", "compiler-plugin", "gradle-plugin")
