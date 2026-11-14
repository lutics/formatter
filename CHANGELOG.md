# Changelog

Published to the Gradle Plugin Portal as `io.github.lutics.formatter`. The repository keeps no history of these
versions; each entry was reconstructed from that version's published artifacts.

## 0.0.5 — 2026-11-14

**Three reports become rewrites, each only where it keeps what the code does; what each relies on is stated with it.**

- `mixed-condition-operators`: an `&&` inside an `||` gets parentheses (`a || b && c` → `a || (b && c)`,
  `a && b || c` → `(a && b) || c`), the grouping Kotlin already applies; nested and multi-line conditions included. It
  no longer reports anything; before format, check lists one finding per outermost group.
- `ui-state-order` on a `when` without subject: an unbroken run of branches that each test one distinct member against
  the same local `val` or parameter (`s is T.M`, or `s == T.M` with `M` an object, alone or followed by `&& guard`) is
  reordered; the branches around the run stay where they are. A `when` whose runs cannot put it in order (another
  branch between, a call, property, `var`, delegated `val` or `it` as subject, a member tested twice, a member that is
  not final or declares `equals`, a `==` on a member that is not an object) is left whole and still reported, with the
  reason. A `==` branch, with a subject or without, also needs every member of the type final (a subclass of an open
  one, named by no branch, could bring an `equals` that matches), a subject whose declared type, where the code
  declares one, is the UiState, and objects whose first use runs no code (no `init` block or property initializer, no
  sealed-class constructor that runs code): the order decides which objects get compared. A branch or member that
  shares its line with other code is not moved, and such a `when` is reported. Not seen: a subject whose type is
  inferred (or a property's) as wider than the UiState, holding another class whose `equals` matches a member.
- `composable-order`: a CompositionLocal read alone (`val activity = LocalActivity.current`) after other statements
  moves up to its section's reads, with the comment lines right above it and in its order among the reads it moves.
  Reading a CompositionLocal runs none of the code around it and nothing in the body can change what it reads, so it
  may run before the statements it crosses. It stays, and is still reported, when it would cross a read that stays and
  uses its name, when the function declares the `Local…` name itself or the file declares it as a `var`, or when it or
  the statement it lands above shares a line with another statement. The move trusts the name: `LocalX` is a
  CompositionLocal declared as a `val` outside the function (a `var` in another file is not seen); where nothing
  provides it, its default runs (or throws) before the crossed statements. Every other
  read out of order (Koin injection, collected state, `requireNotNull(LocalActivity.current)`, a selector after
  `.current`), and a derived value or content out of order, is still reported only: it runs code (a constructor, a
  flow's initial value, a check that throws) whose effect on the statements it would cross no name analysis can see.
- `composable-order` also counts a `return` labeled with the composable's own name (`return@MainScreen`,
  `?: return@MainScreen`) as leaving the function, so it closes a section like an unlabeled one, and a local class as
  a declaration, so a read that names one declared after the reads is in its place.

Still report-only, since a fix needs a name or an intent only the author has: `condition-call-spans-lines` where a hoist
would change when the call runs, the multi-line call the record cannot map, `manifest-sections`,
`version-catalog-names` and `sql-keyword-case`.

**Build**

- Gradle wrapper 9.9.1; `com.android.tools.build:gradle-api` 9.5.1 (from 9.4.1); `com.gradle.plugin-publish` stays
  2.2.1 and Kotlin 2.4.21, the latest stable of each.
- The engine has a test source set (JUnit Jupiter 6.1.3) with before/after and must-stay-reported cases for each
  rewrite above; `./gradlew build` runs it.
- `settings.gradle.kts` applies the foojay toolchain resolver (1.0.0), so `jvmToolchain(21)` builds on a machine
  without a JDK 21. The bytecode stays at 21, so a consuming build runs Gradle on JDK 21 or newer (Gradle 9 and AGP 9
  themselves run on 17): Gradle loads the plugin into that build's own JVM.

## 0.0.4 — 2026-11-02

**`composable-order` reports only what moving the statement fixes without changing the composition.**

- A statement that can leave the function — a return or throw outside a labeled lambda return, a guard included —
  closes a section: the order starts over after it, and it is not reported itself.
- A statement is not reported when it uses what an earlier out-of-order statement declares or assigns during
  composition, directly or through the statements between them, or declares a name that statement uses. What an
  effect's lambdas and a local fun's body assign does not count.
- A statement-level assignment (`state = rememberX(…)` into a `lateinit` local) is a value, not content.

## 0.0.3 — 2026-10-29

**Formats the files around the Kotlin sources.** `format` and `check` now also take the module's `build.gradle.kts`;
on Android every source set's `AndroidManifest.xml` and the `res/` XML aapt compiles (never `res/raw`); every `*.sql`
under `src/`; and, for one project per build, `settings.gradle.kts`, the root `build.gradle.kts` and
`gradle/libs.versions.toml`. Only the formatter rules run on them, and a change to these files alone runs only those
rules.

- Android XML in Android Studio's layout; `res/values*` lays out only its containers and keeps every value's bytes.
- `AndroidManifest.xml`: `<application>` grouped into commented sections (Meta Data, Library, Provider, Service,
  Receiver, Activity Alias, Activity); permissions and meta-data sorted by `android:name`.
- TOML and SQL: LF line endings, no trailing whitespace, one final newline.
- Reported, not rewritten: `version-catalog-names` (camelCase `[versions]` keys, kebab-case aliases),
  `sql-keyword-case` (one keyword case per `.sql` file), and manifests whose sections cannot be decided.

**New Kotlin rules**

- `ui-state-order`: a sealed `*UiState`'s members, and the branches of a `when` over one, run Initial → Loading →
  Empty → Success → Error. A `when` is rewritten only when the type's declaration among the formatted files makes it
  safe; otherwise it is reported.
- `composable-blank-lines`: a Unit-returning `@Composable` body opens with a blank line, keeps its reads together and
  puts one blank line around every other statement. `composable-order` reports statements out of order.
- `property-accessor`: `get`/`set` (a visibility-only `private set` included) on their own line, except a read-only
  getter whose declaration fits on one line.
- `condition-call-spans-lines`: a call spanning lines inside an `if`/`while`/`when` condition is hoisted to a `val`
  where that keeps when it runs, and reported elsewhere.
- `room-query-keyword-case`: Room `@Query` SQL keywords in UPPERCASE.
- `sql-lowercase-keywords`: backend SQL reaching a Postgres sink (r2dbc `@Query`, `createStatement`, `JdbcTemplate`,
  `DatabaseClient`) in lowercase.
- `blank-line-after-sdk-init-guard`: one blank line after `if (!MobileAds.isInitialized) return …`.

The plugin's up-to-date state hashes the Kotlin inputs and the other files separately.

## 0.0.2 — 2026-10-29

**One task.** `format` runs prepare, the recording compile, FINISH and the verifying compiles in one go; the findings
check runs as an action of the lifecycle `check` task. The `formatRecord` and `formatCheck` tasks are gone.

- The recording compile runs in a nested Gradle build of its own, so the recorder never touches the project's normal
  compile tasks or their cache keys; upstream projects compile there unrecorded.
- `format` is skipped while nothing it depends on changed: the plugin's own jar, the build files, the record compile
  classpath and every source's path and content.
- Engine phases run in a worker whose classloader holds only the engine and `kotlin-compiler-embeddable`, apart from
  the Kotlin Gradle plugin's compiler classes.

## 0.0.1 — 2026-10-27

First release. A Gradle plugin with no settings that formats a module's Kotlin sources to one fixed standard and
checks them against it, for Android application and Kotlin JVM modules on Kotlin 2.4.21.

- A compiler plugin records every call site, so arguments are named, wrapped one per line and given a trailing comma
  only where the callee is a Kotlin function with stable parameter names; Java methods and varargs stay on one line.
- Declarations one parameter per line, expression bodies, `companion object` first, class bodies opening with a
  blank line, braces on every branch of a multi-line `if`, lambda bodies after `->`, import ordering, and the
  layout rules that Android Studio's formatter does not apply.
- Tasks: `format` (rewrites in place), `formatRecord` (the recording compile alone) and `formatCheck` (read-only,
  part of `check`).
