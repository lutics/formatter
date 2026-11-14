# formatter

A Gradle plugin that formats a module's Kotlin sources, and the files around them (build scripts, Android manifest and
resources, version catalog, SQL), to one fixed standard and checks them against it. It has no settings: every
module that applies it ends up with the same layout, decided by the code itself and by the compiler, never by an IDE
profile or by whoever edited the file last.

It is a different kind of tool from ktlint or the IntelliJ formatter in one respect: some of its decisions need the
compiler. Whether a call's arguments can be named depends on whether the callee is a Kotlin function with stable
parameter names, a Java method or a vararg, so `format` compiles the module with a small compiler plugin that
records every call site, and the passes that wrap, name and join arguments read that record.

## Usage

```kotlin
// build.gradle.kts of the module to format
plugins {
    id("io.github.lutics.formatter") version "0.0.5"
}
```

| Task | What it does |
|---|---|
| `./gradlew format` | Rewrites the module's files in place to the standard below. |
| `./gradlew check` | Changes nothing; fails the build with `file:line:col rule detail` for every finding. |

`check` also reports what `format` cannot fix on its own (see 「Reported, not rewritten」), so a module can pass
`format` and still fail the check.

**Requirements**
- The module's Kotlin version must equal the plugin's (2.4.21): the call recorder uses the K2 compiler's FIR checker
  API, which is not stable across compiler releases.
- An Android application module (AGP) or a Kotlin JVM module.
- Gradle running on JDK 21 or newer: the plugin's bytecode targets 21, and Gradle loads it into the build's own JVM.

## The standard

The examples show the result of `format`.

### Calls and arguments

- **A Kotlin call with two or more arguments puts each on its own line, names every argument, and ends with a trailing
  comma.**
  ```kotlin
  Text(
      text = stringResource(id = title),
      style = screenTitleTextStyle,
  )
  ```
- **A call whose arguments cannot be named stays on one line**: a Java method, or a call whose first positional
  argument goes to a vararg (`listOf(a, b)`, `mapOf(…)`, `Duration.between(cachedAt, now)`).
- **A single argument stays on the call's line** (`ArrayDeque(listOfNotNull(…))`); when that argument itself spans
  lines, it is named.
- The elements of a vararg parameter stay positional, as Kotlin requires (`send(uid = it, thinPush(…), priority = priority)`).

### Declarations

- **A function with two or more parameters puts each on its own line**, with a trailing comma; one with fewer stays on
  one line.
  ```kotlin
  fun SettingsScreen(
      onBack: () -> Unit,
      modifier: Modifier = Modifier,
  ) {
  ```
- **A class constructor puts its parameters one per line, even a single one.**
- A function whose body only returns or throws one expression becomes `= expression`.
- **`companion object` is the first member of its class** and is never `private` (in an `enum class` it comes right
  after the entries' `;`, where Kotlin requires the entries to be).
- A class, named `object` or `interface` body opens with a blank line after its `{`; an `enum class` body, a
  `companion object` and an anonymous object do not.
- A `val`/`var` whose initializer uses an elvis `?:` stays on one line.

### Control flow and lambdas

- **When any branch of an `if` spans lines, every branch of that `if`/`else if`/`else` chain gets braces.** Multi-line
  `if` expressions and loops get braces too.
- **An `&&` inside an `||` gets parentheses**, spelling out the grouping Kotlin already applies (`&&` binds tighter), so
  nothing about what runs or when changes, nested and multi-line conditions included:
  ```kotlin
  if (e is ContentConvertException || (e is ClientRequestException && e.response.status.value !in setOf(401, 403)))
  val visible = (loaded && !empty) || refreshing
  ```
- A call that spans lines inside an `if` condition moves to a `val` above the `if`, only where the `if` is a statement
  and the call is its whole condition (or its negation), so the `val` runs exactly when the condition did.
- A lambda that declares parameters (`a ->`, `(a, b) ->`) starts its body on the line after `->` and closes with `}` on
  its own line.
- Lambda parameters stay on the `{` line; a redundant `->` is removed.
- No blank line between `when` entries, even after a multi-line one.

### UiState

- **A sealed `*UiState` lists its members Initial → Loading → Empty → Success → Error, and a `when` over one tests them
  in that order.** A member with another name (`SignedOut`) keeps its place; only the canonical ones move, among the
  positions they hold. A `when` is reordered only where the order cannot decide which branch runs, judged from the
  type's declaration among the module's files: every member it tests is final and extends no other member.
- A branch that compares with `==` (`s == T.Member`, or `T.Member ->` with a subject) calls the subject's `equals` and
  first uses the object. With one, every member of the type must be final (a subclass of an open one could bring its
  own `equals`) with no `equals` but the generated one; the subject's type, where the code declares it, must be the
  UiState (not `Any`); and neither an object compared nor a sealed class's constructor may run code when first used
  (an `init` block, a property initializer), since the order decides which objects get compared.
- With a subject (`when (state)`), every branch must test one distinct member (`is T.Member` or `T.Member`).
- Without a subject, a run of adjacent branches is reordered when each tests one distinct member against the same local
  `val` or parameter (never a call, a property, a `var` or a delegated `val`), as `s is T.Member` or `s == T.Member`
  (an `object`), alone or followed by `&& guard`. At most one member test of the run can hold and a guard runs only
  after its own test held, so the same branch runs in any order. Every other branch keeps its place, and a run never
  extends past one:
  ```kotlin
  when {
      !booted -> Unit
      uiState == null -> Unit
      uiState == UserUiState.Loading -> wait()
      uiState is UserUiState.Success && uiState.user.banned -> block()
      uiState == UserUiState.Error -> finish()
      else -> unlock()
  }
  ```
- A branch or member that shares its line with other code is never moved.

### Composable bodies

- **A Unit-returning `@Composable` body opens with a blank line, puts its reads (CompositionLocals, collected state,
  Koin injection) first, one per line with no blank line between them, and one blank line around every other
  statement.** Then come derived values, effects, content, and the effects the content drives.
- A CompositionLocal read alone (`val activity = LocalActivity.current`) that comes after other statements moves up to
  the reads, with the comment lines right above it. Reading a CompositionLocal runs none of the code around it, and no
  statement of the body can change what it reads (only an enclosing provider sets it), so it may run before them. It
  stays, and is reported, when it would cross a read that stays and that uses its name, when the function itself
  declares the `Local…` name or the file declares it as a `var`, or when it or the statement it lands above shares a
  line with another statement. A
  `return` or `throw` (a guard such as `if (locked == null) return`, or `?: return@MainScreen`) starts the order over
  below it, and nothing moves across it.
  ```kotlin
  @Composable
  fun MainScreen() {

      // the activity the screen finishes
      val activity = LocalActivity.current

      val backStack = rememberNavBackStack(AppRoute.Splash())
  ```
- The move trusts the name: `LocalX` is a CompositionLocal, declared as a `val` outside the function (a `var` in
  another file is not seen). Where nothing provides it, its default runs (or throws) before the statements the read
  crossed instead of after them.

### Comments

- **Comments are `//` lines.** A KDoc or block comment that opens its line becomes one `//` line per text line, at the
  comment's indent. A `/*` after code on the same line is left alone.

### Imports

Imports follow IntelliJ's Kotlin "Optimize Imports" with its IDE defaults, decided from what the compiler resolves
each name to:

- Unused imports are removed, and so are imports the file gets by default (`kotlin.*`, `kotlin.collections.*`, …).
- Five or more names used from one package become `package.*`; three or more members of one class become `Class.*`.
  Members of an `object` are always imported one by one.
- `java.util` and everything under `io.ktor` and `kotlinx.android.synthetic` is always imported with `*`.
- Fewer names than that from a `*` import are expanded back to one import per name.
- When a `*` would make a name resolve to something else, that name keeps its explicit import.
- One block, no blank lines, in the order: everything else, then `java.*`, `javax.*`, `kotlin.*`, then aliased
  imports in that same order among themselves, sorted within each group.

### Whitespace

- 4-space indent, no tabs, LF line endings, one final newline, no trailing whitespace.
- **No line-length limit**: nothing is wrapped for length alone.
- No consecutive blank lines, no blank line before a closing `}`, no blank lines inside a chained call.
- No semicolons, no empty class body `{}`, no space inside parentheses.
- `${name}` becomes `$name` where the next character cannot continue an identifier.

### Other files

Besides the Kotlin sources, `format` and `check` take the project's `build.gradle.kts`, on Android every source set's
`AndroidManifest.xml` and the `res/` XML aapt compiles (the directory types in `XmlLayout.RES_TYPES`, with any
qualifiers; never `res/raw`, which the app reads as bytes), and every `*.sql` under `src/`. The root build's own files —
`settings.gradle.kts`, the root `build.gradle.kts` and `gradle/libs.versions.toml` — belong to one project: the root
project when it applies the plugin, otherwise the first project in path order that does (`:app` in a build that applies
it only there). Only the formatter rules run on them (step 4's last part and step 5's formatting below), and a change to
these files alone runs only those rules, over them: `*.kts` takes the Kotlin rules; the rest take these:

- **Android XML**, in Android Studio's layout: LF line endings; 4-space indent by depth; an element with two or more
  attributes puts each on its own line, except that the root's first `xmlns` stays on the element's line; one attribute
  stays on the line; a self-closing tag ends in ` />`; whitespace-only content over lines becomes the end tag on its
  own line at the element's indent; a comment on a start tag's line stays on it. `res/values*` files keep every
  element's attributes on one line and lay out only the containers (`Xml.VALUES_CONTAINERS`): a value element's
  content (`string`, `item`, `dimen`, …) keeps its bytes. Comments, CDATA, text and attribute values keep their bytes
  apart from line endings, and attributes their order.
- **`AndroidManifest.xml`**: inside `<application>`, the sections `<!-- Meta Data -->`, `<!-- Library -->`
  (`<uses-library>`), `<!-- Provider -->`, `<!-- Service -->`, `<!-- Receiver -->`, `<!-- Activity Alias -->`,
  `<!-- Activity -->`, in that order, each after one blank line; a section with no element has no header.
  `<uses-permission>`, the Meta Data section and the `InitializationProvider`'s `<meta-data>` sort by `android:name`,
  each element into the position (and the blank line before it) of another. A comment after an element on its line,
  or right above it, moves with it; a comment right under a header with a blank line below it stays under that header.
- **`*.toml`, `*.sql`**: LF line endings, no trailing whitespace, one final newline. A TOML multi-line string keeps
  the spaces before its line breaks; an SQL string literal, quoted name or dollar-quoted body keeps every byte, line
  endings included.

### Reported, not rewritten

`check` lists these for a person to fix, because fixing them needs a name or an intent only the author has, or a move
the formatter cannot prove keeps what the code does:

- A call that spans lines inside a condition where hoisting it to a `val` would change when or whether it runs: a loop
  condition, a `when` subject, inside `&&`/`||` or a lambda, an else-if, a brace-less branch, an expression-position
  `if`, a comment in the condition, a generic callee whose type comes from the condition, or a call no plain name fits
  (condition-call-spans-lines). The `val` needs a name and the call's place needs the author.
- A multi-line Kotlin call whose arguments the compiler record cannot map to parameters.
- A `when` over a UiState out of order that the rules under 「UiState」 do not allow to reorder (ui-state-order): a
  subject that is a call, a property, a `var`, a delegated `val` or `it`; two branches on the same member; a branch
  between that tests something else (`log() ->`, `x == null ->`, another type) or an `||`; a member that is open,
  extends another, declares its own `equals`, or that the module does not declare; with `==`, a subject declared wider
  than the UiState or an object whose first use runs code; a branch sharing its line with other code. The finding
  names the reason.
- In a `@Composable` body (composable-order): a read other than a CompositionLocal read alone (Koin injection,
  collected state, `requireNotNull(LocalActivity.current)`, `LocalConfiguration.current.screenWidthDp`), which runs
  code whose effects on the statements it would cross the formatter cannot see; a CompositionLocal read that would
  cross a read that stays and uses its name, whose `Local…` name the function declares or the file has as a `var`, or
  that shares its line with another statement; a derived value after an effect or content; content after an effect
  that follows the content. A derived value or content would cross effects and content whose composition the formatter
  cannot see into, so it is never moved. A read that uses what a statement above it declares or assigns, or declares a
  name one of them uses, is in its place and not reported.
- A manifest whose `<application>` holds an element no section names, or a comment a blank line separates from every
  element (manifest-sections); its sections are then left as they are. Also a comment right under a header and right
  above an element that is not the first of that header's section: it may introduce the section or explain the element.
  And a comment right above the first element of a run that sorting by `android:name` would reorder, or above the
  element that would come first once sorted: it may explain the run or only that element, so the run is left unsorted.
  Put a blank line under it (the run's), move it after the element on its line (the element's), or move it directly
  above the element it explains. A run of one element, or one already in order, is never reported.
- A `[versions]` key that is not camelCase, or a `[libraries]`/`[plugins]`/`[bundles]` alias that is not kebab-case
  (version-catalog-names): renaming one breaks the build scripts that reference it.
- A `.sql` file whose Postgres reserved keywords mix UPPER and lower case outside comments, strings and dollar-quoted
  bodies (sql-keyword-case): either case is fine, one per file.

## How `format` runs

The order is fixed; each step assumes the ones before it ran. The steps are not tasks: the plugin adds `format` and
an action on the existing `check`, nothing else to call.

1. **Prepare**: comments to `//`, constructor parameters one per line.
2. **Layout**: braces on multi-line `if`s, call arguments opened onto their own lines, then the layout round, repeated
   until a round changes nothing.
3. **Record**: one compile of the module with the call recorder (`:compiler-plugin`), in a nested Gradle build,
   which writes every call site: its callee, whether it is Java, and which parameter each argument goes to, and every
   file's import record: the symbols its references resolve to and the classifier lookups a `*` import decision needs.
   Only these nested builds load the recorder; a failed record compile fails `format`.
4. **Finish**: name arguments, join the calls that cannot be named, then the declaration, `companion object`, body
   blank-line and lambda passes, the imports, and last the formatter rules (`:engine`, `Rules.kt`), which repeat until
   a pass changes nothing and settle wrapping, trailing commas, signatures, imports, blank lines and every line's indent.
5. **Verify**: compile again with the recorder, in another nested build (one build runs a compile task once), and
   rerun the imports step with every import record so far; repeat until the step changes nothing, at most five times,
   formatting after each change. This is IntelliJ's conflict check: a reference that resolves to something else under
   the new imports keeps its explicit import. Only the last compile's exit fails `format`.

Naming and joining read the record by line, so nothing between the record compile and those two passes may change a
file's line count.

## Modules

| Module | |
|---|---|
| `engine` | The passes and formatter rules, on the Kotlin compiler's PSI, and the text rules for the other files (`Files.kt`). Also a command-line `formatter` for running one step (`--step`). Its before/after cases (`src/test`, JUnit Jupiter) run in `./gradlew build`. |
| `compiler-plugin` | The call recorder (K2). |
| `gradle-plugin` | The `format` task and the check behind `check`; runs the engine in an isolated worker classloader. |

## License

Apache License 2.0 (`LICENSE`). The formatter rules derive from ktlint (MIT) and the layout formatter and import optimizer port IntelliJ code (Apache 2.0); see `NOTICE`.
