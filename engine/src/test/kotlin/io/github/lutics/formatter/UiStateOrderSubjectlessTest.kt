package io.github.lutics.formatter

import io.github.lutics.formatter.rules.layout.UiStateOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// ui-state-order on a when without subject: runs of member tests on one stable name are reordered; anything that could
// change which branch runs stays as it is and stays reported.
class UiStateOrderSubjectlessTest {
    private val id = UiStateOrder.id
    private val rule = listOf(UiStateOrder)

    @TempDir
    lateinit var dir: File

    private val declaration = """
        package app.user

        sealed interface UserUiState {
            data object Loading : UserUiState
            data object Empty : UserUiState
            data class Success(val user: User) : UserUiState
            data object Error : UserUiState
        }

        data class User(val banned: Boolean)
    """.trimIndent() + "\n"

    // Indexes the declaration (and any extra files) as the plugin indexes one module's files.
    private fun index(vararg extra: String) {
        val files = (listOf(declaration) + extra).mapIndexed { i, text -> File(dir, "F$i.kt").apply { writeText(text) } }
        UiStateOrder.index = UiStateOrder.buildIndex(files)
    }

    @AfterEach
    fun reset() {
        UiStateOrder.index = null
    }

    private fun file(body: String) = "package app.main\n\nimport app.user.UserUiState\n\n$body"

    @Test
    fun `Health shape - the run between the guards and else is reordered`() {
        index()
        val before = file(
            """
            fun gate(booted: Boolean, uiState: UserUiState?) {
                when {
                    !booted -> Unit
                    uiState == null -> Unit
                    // a lost session closes the app
                    uiState == UserUiState.Error -> finish()
                    uiState == UserUiState.Loading -> wait()
                    uiState is UserUiState.Success && uiState.user.banned -> block()
                    else -> unlock()
                }
            }
            """.trimIndent() + "\n",
        )
        val after = file(
            """
            fun gate(booted: Boolean, uiState: UserUiState?) {
                when {
                    !booted -> Unit
                    uiState == null -> Unit
                    uiState == UserUiState.Loading -> wait()
                    uiState is UserUiState.Success && uiState.user.banned -> block()
                    // a lost session closes the app
                    uiState == UserUiState.Error -> finish()
                    else -> unlock()
                }
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertFormats(id, before, after, rule)
    }

    @Test
    fun `Camera shape - a local val captured by an effect lambda`() {
        index()
        val before = file(
            """
            fun screen(read: () -> UserUiState?) {
                val uiState = read()
                effect {
                    when {
                        uiState == null -> Unit
                        uiState is UserUiState.Error -> finish()
                        uiState is UserUiState.Success && uiState.user.banned && confirm(uiState) -> block()
                        else -> unlock()
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        val after = file(
            """
            fun screen(read: () -> UserUiState?) {
                val uiState = read()
                effect {
                    when {
                        uiState == null -> Unit
                        uiState is UserUiState.Success && uiState.user.banned && confirm(uiState) -> block()
                        uiState is UserUiState.Error -> finish()
                        else -> unlock()
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertFormats(id, before, after, rule)
    }

    // Every scope isStableLocal accepts: (prefix, suffix) around the when, which sits at 8 spaces.
    private val stableScopes = listOf(
        "    states.forEach { s ->\n" to "    }\n",
        "    for (s in states) {\n" to "    }\n",
        "    pairs.forEach { (s, _) ->\n" to "    }\n",
        "    val (s, _) = pairs.first()\n    run {\n" to "    }\n",
        "    try {\n        states.first()\n    } catch (s: IllegalStateException) {\n" to "    }\n",
        "    when (val s = states.firstOrNull()) {\n        null -> Unit\n        else -> run {\n" to "        }\n    }\n",
    )

    @Test
    fun `lambda, for, destructured, catch and when-subject names are stable subjects`() {
        index()
        for ((prefix, suffix) in stableScopes) {
            fun source(first: String, second: String) = file(
                "fun f(states: List<UserUiState>, pairs: List<Pair<UserUiState, Int>>) {\n" + prefix +
                    "        when {\n            $first\n            $second\n        }\n" + suffix + "}\n",
            )
            Fixtures.assertFormats(
                id,
                source("s is UserUiState.Error -> a()", "s is UserUiState.Empty -> b()"),
                source("s is UserUiState.Empty -> b()", "s is UserUiState.Error -> a()"),
                rule,
            )
        }
    }

    @Test
    fun `a non-canonical member keeps its place inside the run`() {
        index(
            """
            package app.gate

            sealed interface GateUiState {
                data object Loading : GateUiState
                data object SignedOut : GateUiState
                data object Error : GateUiState
            }
            """.trimIndent() + "\n",
        )
        val before = "package app.main\n\nimport app.gate.GateUiState\n\nfun f(s: GateUiState) {\n    when {\n        s == GateUiState.Error -> a()\n        s == GateUiState.SignedOut -> b()\n        s == GateUiState.Loading -> c()\n    }\n}\n"
        val after = "package app.main\n\nimport app.gate.GateUiState\n\nfun f(s: GateUiState) {\n    when {\n        s == GateUiState.Loading -> c()\n        s == GateUiState.SignedOut -> b()\n        s == GateUiState.Error -> a()\n    }\n}\n"
        Fixtures.assertFormats(id, before, after, rule)
    }

    @Test
    fun `a when with a subject is still reordered as before`() {
        index()
        val before = file("fun f(s: UserUiState) {\n    when (s) {\n        is UserUiState.Error -> a()\n        is UserUiState.Loading -> b()\n    }\n}\n")
        val after = file("fun f(s: UserUiState) {\n    when (s) {\n        is UserUiState.Loading -> b()\n        is UserUiState.Error -> a()\n    }\n}\n")
        Fixtures.assertFormats(id, before, after, rule)
    }

    // Negative cases: untouched, and reported. Each differs from a positive case above in one property.

    private fun negative(body: String, reason: String) {
        index()
        Fixtures.assertReported(id, file(body), rule, reason)
    }

    private val straddle = "the branches out of order are not one unbroken run"

    @Test
    fun `different subjects`() = negative(
        "fun f(a: UserUiState, b: UserUiState) {\n    when {\n        a is UserUiState.Error -> x()\n        b is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a nullable is test also matches null`() = negative(
        "fun f(s: UserUiState?) {\n    when {\n        s is UserUiState.Error? -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a call as subject`() = negative(
        "fun f() {\n    when {\n        state() is UserUiState.Error -> x()\n        state() is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a property as subject`() = negative(
        "class Holder(val s: UserUiState) {\n\n    fun f() {\n        when {\n            s is UserUiState.Error -> x()\n            s is UserUiState.Loading -> y()\n        }\n    }\n}\n",
        "is not a local val or a parameter",
    )

    @Test
    fun `a var subject`() = negative(
        "fun f(read: () -> UserUiState) {\n    var s = read()\n    when {\n        s is UserUiState.Error -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        "is not a local val or a parameter",
    )

    @Test
    fun `a delegated val subject`() = negative(
        "fun f(state: State<UserUiState>) {\n    val s by state\n    when {\n        s is UserUiState.Error -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        "is not a local val or a parameter",
    )

    @Test
    fun `an implicit it subject`() = negative(
        "fun f(states: List<UserUiState>) {\n    states.forEach {\n        when {\n            it is UserUiState.Error -> x()\n            it is UserUiState.Loading -> y()\n        }\n    }\n}\n",
        "is not a local val or a parameter",
    )

    @Test
    fun `the same member twice`() = negative(
        "fun f(s: UserUiState, g: Boolean) {\n    when {\n        s is UserUiState.Error && g -> x()\n        s is UserUiState.Loading -> y()\n        s is UserUiState.Error -> z()\n    }\n}\n",
        "two branches test the same member",
    )

    @Test
    fun `a side-effecting branch between the member tests`() = negative(
        "fun f(s: UserUiState) {\n    when {\n        s is UserUiState.Error -> x()\n        log(s) -> y()\n        s is UserUiState.Loading -> z()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a null test inside the run`() = negative(
        "fun f(s: UserUiState?) {\n    when {\n        s is UserUiState.Error -> x()\n        s == null -> y()\n        s is UserUiState.Loading -> z()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a branch on another UiState type inside the run`() {
        index(
            """
            package app.user

            sealed interface PointUiState {
                data object Loading : PointUiState
                data object Error : PointUiState
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.user.PointUiState\nimport app.user.UserUiState\n\nfun f(s: UserUiState, p: PointUiState) {\n    when {\n        s is UserUiState.Error -> x()\n        p is PointUiState.Loading -> y()\n        s is UserUiState.Loading -> z()\n    }\n}\n",
            rule,
            straddle,
        )
    }

    @Test
    fun `a partly fixable when is left whole`() = negative(
        "fun f(s: UserUiState) {\n    when {\n        s is UserUiState.Error -> a()\n        s is UserUiState.Loading -> b()\n        log() -> c()\n        s is UserUiState.Empty -> d()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `an || condition`() = negative(
        "fun f(s: UserUiState, g: Boolean) {\n    when {\n        s == UserUiState.Error || g -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a guard that names another member`() = negative(
        "fun f(s: UserUiState, t: UserUiState) {\n    when {\n        s is UserUiState.Error && t == UserUiState.Empty -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `Stock shape - an as-cast chain and an || null test`() = negative(
        "fun f(accountState: UserUiState?) {\n    when {\n        accountState == null || accountState == UserUiState.Loading -> Unit\n        accountState == UserUiState.Error -> finish()\n        (accountState as? UserUiState.Success)?.user?.banned == true -> block()\n        else -> unlock()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a member compared with == that is not an object`() {
        index(
            """
            package app.user

            sealed interface CardUiState {
                class Loading : CardUiState
                data object Error : CardUiState
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.user.CardUiState\n\nfun f(s: CardUiState) {\n    when {\n        s == CardUiState.Error -> x()\n        s == CardUiState.Loading -> y()\n    }\n}\n",
            rule,
            "is compared with '==' but is not an object",
        )
    }

    @Test
    fun `a member that declares equals`() {
        index(
            """
            package app.user

            sealed interface FeedUiState {
                data object Loading : FeedUiState
                class Error : FeedUiState {
                    override fun equals(other: Any?) = true
                }
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.user.FeedUiState\n\nfun f(s: FeedUiState) {\n    when {\n        s is FeedUiState.Error -> x()\n        s == FeedUiState.Loading -> y()\n    }\n}\n",
            rule,
            "declares equals",
        )
    }

    @Test
    fun `an open member`() {
        index(
            """
            package app.user

            sealed interface ListUiState {
                data object Loading : ListUiState
                open class Error : ListUiState
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.user.ListUiState\n\nfun f(s: ListUiState) {\n    when {\n        s is ListUiState.Error -> x()\n        s is ListUiState.Loading -> y()\n    }\n}\n",
            rule,
            "is open, abstract, sealed or an interface",
        )
    }

    @Test
    fun `a type the index does not hold`() {
        UiStateOrder.index = UiStateOrder.buildIndex(emptyList())
        Fixtures.assertReported(
            id,
            file("fun f(s: UserUiState) {\n    when {\n        s is UserUiState.Error -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n"),
            rule,
            "no sealed UserUiState is declared",
        )
    }

    @Test
    fun `a negated is test is not a member test`() = negative(
        "fun f(s: UserUiState) {\n    when {\n        s !is UserUiState.Error -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        straddle,
    )

    @Test
    fun `a parameter shadowed by a local var`() = negative(
        "fun f(s: UserUiState, read: () -> UserUiState) {\n    var s = read()\n    when {\n        s is UserUiState.Error -> x()\n        s is UserUiState.Loading -> y()\n    }\n}\n",
        "is not a local val or a parameter",
    )

    // Verifier c4: an open member no branch names may have a subclass whose equals matches the object a branch compares
    // with, so `==` needs every member final; with the subclass in the files and without it.

    private val openMember = """
        package app.screen

        sealed interface ScreenUiState {
            data object Loading : ScreenUiState
            data object Error : ScreenUiState
            open class Content : ScreenUiState
        }
    """.trimIndent() + "\n"

    private val weird = "package app.screen\n\nclass Weird : ScreenUiState.Content() {\n    override fun equals(other: Any?) = true\n\n    override fun hashCode() = 0\n}\n"

    private fun screenFile(body: String) = "package app.main\n\nimport app.screen.ScreenUiState\n\n$body"

    @Test
    fun `Verifier c4 - == on a type with an open member, its subclass in the files`() {
        index(openMember, weird)
        Fixtures.assertReported(
            id,
            screenFile("fun label(s: ScreenUiState): String = when {\n    s == ScreenUiState.Error -> \"error\"\n    s == ScreenUiState.Loading -> \"loading\"\n    else -> \"other\"\n}\n"),
            rule,
            "ScreenUiState.Content is open",
        )
    }

    @Test
    fun `Verifier c4 - == on a type with an open member, its subclass elsewhere`() {
        index(openMember)
        Fixtures.assertReported(
            id,
            screenFile("fun label(s: ScreenUiState): String = when {\n    s == ScreenUiState.Error -> \"error\"\n    s == ScreenUiState.Loading -> \"loading\"\n    else -> \"other\"\n}\n"),
            rule,
            "ScreenUiState.Content is open",
        )
    }

    @Test
    fun `Verifier c4 - a when with a subject that leaves the open member unnamed`() {
        index(openMember)
        Fixtures.assertReported(
            id,
            screenFile("fun f(s: ScreenUiState) {\n    when (s) {\n        ScreenUiState.Error -> a()\n        ScreenUiState.Loading -> b()\n    }\n}\n"),
            rule,
            "ScreenUiState.Content is open",
        )
    }

    @Test
    fun `is tests on a type with an open member are still reordered`() {
        index(openMember)
        Fixtures.assertFormats(
            id,
            screenFile("fun f(s: ScreenUiState) {\n    when {\n        s is ScreenUiState.Error -> a()\n        s is ScreenUiState.Loading -> b()\n        else -> c()\n    }\n}\n"),
            screenFile("fun f(s: ScreenUiState) {\n    when {\n        s is ScreenUiState.Loading -> b()\n        s is ScreenUiState.Error -> a()\n        else -> c()\n    }\n}\n"),
            rule,
        )
    }

    // Verifier c6b: comparing with an object first uses it, so an object whose first use runs code (its init, or the
    // sealed class's constructor) must not change which objects a run compares with.

    @Test
    fun `Verifier c6b - == on objects with init blocks`() {
        index(
            """
            package app.screen

            val log = mutableListOf<String>()

            sealed interface InitUiState {
                object Loading : InitUiState {
                    init {
                        log += "Loading init"
                    }
                }
                object Error : InitUiState {
                    init {
                        log += "Error init"
                    }
                }
                data class Success(val d: Int) : InitUiState
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.screen.InitUiState\n\nfun label(s: InitUiState): String = when {\n    s == InitUiState.Error -> \"error\"\n    s == InitUiState.Loading -> \"loading\"\n    else -> \"other\"\n}\n",
            rule,
            "InitUiState.Error runs code when it is first used",
        )
    }

    @Test
    fun `== on an object with a property initializer`() {
        index(
            """
            package app.screen

            sealed interface TagUiState {
                data object Loading : TagUiState
                data object Error : TagUiState {
                    val tag = register("error")
                }
            }
            """.trimIndent() + "\n",
        )
        Fixtures.assertReported(
            id,
            "package app.main\n\nimport app.screen.TagUiState\n\nfun f(s: TagUiState) {\n    when {\n        s == TagUiState.Error -> a()\n        s == TagUiState.Loading -> b()\n    }\n}\n",
            rule,
            "TagUiState.Error runs code when it is first used",
        )
    }

    private fun bootType(body: String) = "package app.screen\n\nval log = mutableListOf<String>()\n\nsealed class BootUiState {\n$body    data object Loading : BootUiState()\n    data object Error : BootUiState()\n}\n"

    private val bootWhen = "package app.main\n\nimport app.screen.BootUiState\n\nfun f(s: BootUiState) {\n    when {\n        s == BootUiState.Error -> a()\n        s == BootUiState.Loading -> b()\n    }\n}\n"

    @Test
    fun `== on objects of a sealed class whose constructor runs code`() {
        index(bootType("    init {\n        log += \"constructed\"\n    }\n\n"))
        Fixtures.assertReported(id, bootWhen, rule, "BootUiState runs code when a member object is first used")
    }

    @Test
    fun `== on objects of a sealed class whose constructor runs nothing is still reordered`() {
        index(bootType(""))
        Fixtures.assertFormats(
            id,
            bootWhen,
            "package app.main\n\nimport app.screen.BootUiState\n\nfun f(s: BootUiState) {\n    when {\n        s == BootUiState.Loading -> b()\n        s == BootUiState.Error -> a()\n    }\n}\n",
            rule,
        )
    }

    // Verifier c1: a subject declared wider than the UiState may hold another class whose equals matches a member.

    @Test
    fun `Verifier c1 - == on a subject declared as Any`() {
        for (type in listOf("Any", "Any?")) {
            negative(
                "fun label(s: $type): String = when {\n    s == UserUiState.Error -> \"error\"\n    s == UserUiState.Loading -> \"loading\"\n    else -> \"other\"\n}\n",
                "is declared as $type",
            )
        }
    }

    @Test
    fun `a when with a subject declared as Any`() = negative(
        "fun f(s: Any) {\n    when (s) {\n        UserUiState.Error -> a()\n        UserUiState.Loading -> b()\n    }\n}\n",
        "is declared as Any",
    )

    // Verifier c5: a branch sharing its line with another cannot move without carrying the other's code or comment.

    private val oneLine = "fun label(s: UserUiState): String = when {\n    s == UserUiState.Error -> \"error\"; s == UserUiState.Loading -> \"loading\" // note\n    else -> \"other\"\n}\n"

    @Test
    fun `Verifier c5 - branches on one line are reported by the rule alone`() = negative(oneLine, "shares its line")

    @Test
    fun `Verifier c5 - the full rule set splits the line first and then reorders, the comment intact`() {
        index()
        val once = Fixtures.format(file(oneLine), RULES)
        assertEquals(
            file("fun label(s: UserUiState): String = when {\n    s == UserUiState.Loading -> \"loading\" // note\n    s == UserUiState.Error -> \"error\"\n    else -> \"other\"\n}\n"),
            once,
        )
        Fixtures.assertSettles(id, file(oneLine))
    }

    @Test
    fun `branches of a when with a subject on one line are reported by the rule alone`() = negative(
        "fun f(s: UserUiState) {\n    when (s) {\n        is UserUiState.Error -> a(); is UserUiState.Loading -> b()\n    }\n}\n",
        "shares its line",
    )

    @Test
    fun `members on one line are not reordered`() {
        val source = "package app.user\n\nsealed interface LineUiState {\n    data object Error : LineUiState; data object Loading : LineUiState // note\n}\n"
        assertEquals(source, Fixtures.format(source, rule))
    }

    @Test
    fun `an in-order when is neither rewritten nor reported`() {
        index()
        val source = file("fun f(s: UserUiState) {\n    when {\n        s is UserUiState.Loading -> x()\n        log() -> y()\n        s is UserUiState.Error -> z()\n    }\n}\n")
        assertEquals(source, Fixtures.format(source, rule))
        assertEquals(emptyList<String>(), Fixtures.reported(source, id))
    }
}
