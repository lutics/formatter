# Changelog

Published to the Gradle Plugin Portal as `io.github.lutics.formatter`. The repository keeps no history of these
versions; each entry was reconstructed from that version's published artifacts.

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
