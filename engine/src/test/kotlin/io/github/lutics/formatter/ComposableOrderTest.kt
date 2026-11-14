package io.github.lutics.formatter

import io.github.lutics.formatter.rules.layout.ComposableBlankLines
import io.github.lutics.formatter.rules.layout.ComposableOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// composable-order: a CompositionLocal read alone out of order moves up to its section's reads; what cannot move stays
// and stays reported.
class ComposableOrderTest {
    private val id = ComposableOrder.id

    // the blank lines are composable-blank-lines', which runs with it
    private val rules = listOf(ComposableBlankLines, ComposableOrder)

    private fun screen(body: String) =
        "@Composable\nfun MainScreen() {\n\n" + body.trimIndent().lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" } + "\n}\n"

    // Untouched by format, and check reports nothing for composable-order.
    private fun assertLeftAlone(source: String) {
        assertEquals(source, Fixtures.format(source, rules))
        assertEquals(emptyList<String>(), Fixtures.reported(source, id))
    }

    @Test
    fun `Call shape - a CompositionLocal read after derived values moves to the top with its comment`() = Fixtures.assertFormats(
        id,
        screen(
            """
            val backStack = rememberNavBackStack(AppRoute.Splash())

            // A restored stack goes back behind the splash.
            val navigator = remember(backStack) {
                Navigator(backStack)
            }

            // the activity the screen finishes
            val activity = LocalActivity.current

            var booted by remember {
                mutableStateOf(false)
            }

            MainContent(navigator = navigator, activity = activity, booted = booted)
            """,
        ),
        screen(
            """
            // the activity the screen finishes
            val activity = LocalActivity.current

            val backStack = rememberNavBackStack(AppRoute.Splash())

            // A restored stack goes back behind the splash.
            val navigator = remember(backStack) {
                Navigator(backStack)
            }

            var booted by remember {
                mutableStateOf(false)
            }

            MainContent(navigator = navigator, activity = activity, booted = booted)
            """,
        ),
        rules,
    )

    @Test
    fun `Health shape - the read that depends on a later value stays, the independent one moves`() = Fixtures.assertFormats(
        id,
        screen(
            """
            val backStack = rememberNavBackStack(AppRoute.Splash())

            var booted by remember {
                mutableStateOf(false)
            }

            // MainViewModel exists only once the splash has signed in.
            val mainViewModel = if (booted) koinViewModel<MainViewModel>() else null

            val uiState = mainViewModel?.uiState?.collectAsStateWithLifecycle()?.value
            val activity = LocalActivity.current

            LaunchedEffect(uiState) {
                if (uiState == null) activity?.finish()
            }
            """,
        ),
        screen(
            """
            val activity = LocalActivity.current

            val backStack = rememberNavBackStack(AppRoute.Splash())

            var booted by remember {
                mutableStateOf(false)
            }

            // MainViewModel exists only once the splash has signed in.
            val mainViewModel = if (booted) koinViewModel<MainViewModel>() else null

            val uiState = mainViewModel?.uiState?.collectAsStateWithLifecycle()?.value

            LaunchedEffect(uiState) {
                if (uiState == null) activity?.finish()
            }
            """,
        ),
        rules,
    )

    @Test
    fun `several CompositionLocal reads join the leading reads in their order`() = Fixtures.assertFormats(
        id,
        screen(
            """
            val context = LocalContext.current

            val start = remember { Start() }

            val density = LocalDensity.current

            Header(start = start)

            val activity = LocalActivity.current

            Body(context = context, density = density, activity = activity)
            """,
        ),
        screen(
            """
            val context = LocalContext.current
            val density = LocalDensity.current
            val activity = LocalActivity.current

            val start = remember { Start() }

            Header(start = start)

            Body(context = context, density = density, activity = activity)
            """,
        ),
        rules,
    )

    @Test
    fun `a read after a guard moves within the guard's section only`() = Fixtures.assertFormats(
        id,
        screen(
            """
            val locked = remember { lockState() }

            if (locked == null) return

            val gate = remember { Gate() }

            val activity = LocalActivity.current

            Content(gate = gate, activity = activity)
            """,
        ),
        screen(
            """
            val locked = remember { lockState() }

            if (locked == null) return

            val activity = LocalActivity.current

            val gate = remember { Gate() }

            Content(gate = gate, activity = activity)
            """,
        ),
        rules,
    )

    @Test
    fun `a return labeled with a lambda's name leaves only the lambda, so the read moves over it`() = Fixtures.assertFormats(
        id,
        screen(
            """
            val start = remember { Start() }

            items.forEach {
                if (it == null) return@forEach
                Row(it)
            }

            val activity = LocalActivity.current

            Content(start = start, activity = activity)
            """,
        ),
        screen(
            """
            val activity = LocalActivity.current

            val start = remember { Start() }

            items.forEach {
                if (it == null) return@forEach
                Row(it)
            }

            Content(start = start, activity = activity)
            """,
        ),
        rules,
    )

    @Test
    fun `a CompositionLocal read moves over a statement that throws, since running it first changes nothing`() = Fixtures.assertFormats(
        id,
        screen(
            """
            Text("header")

            checkNotNull(id) { "no id" }

            val activity = LocalActivity.current

            Content(activity = activity)
            """,
        ),
        screen(
            """
            val activity = LocalActivity.current

            Text("header")

            checkNotNull(id) { "no id" }

            Content(activity = activity)
            """,
        ),
        rules,
    )

    @Test
    fun `check points at the moved read's line and where it lands`() {
        val source = screen(
            """
            val start = remember { Start() }

            val activity = LocalActivity.current

            Content(start = start, activity = activity)
            """,
        )
        val offsets = Engine.check("x.kt", source, rules).filter { it.first == id }.map { it.second }
        assertEquals(listOf(source.indexOf("val start"), source.indexOf("    val activity")), offsets.sorted())
    }

    // A return labeled with the composable's own name leaves the function, as an unlabeled one does (c10).

    @Test
    fun `a guard returning with the function's label closes the section`() = assertLeftAlone(
        screen(
            """
            val context = LocalContext.current

            Text("header")

            if (!show) return@MainScreen

            val activity = LocalActivity.current

            Text("body ${'$'}activity")
            """,
        ),
    )

    @Test
    fun `an elvis returning with the function's label closes the section`() = assertLeftAlone(
        screen(
            """
            val context = LocalContext.current

            Text("header")

            val id = route.id ?: return@MainScreen

            val activity = LocalActivity.current

            Text("body ${'$'}id ${'$'}activity")
            """,
        ),
    )

    @Test
    fun `Verifier c10 - a requireNotNull read below a function-labeled guard stays`() = assertLeftAlone(
        screen(
            """
            val context = LocalContext.current

            Text("header")

            if (!show) return@MainScreen

            val activity = requireNotNull(LocalActivity.current)

            Text("body ${'$'}activity")
            """,
        ),
    )

    // Every read but a CompositionLocal read alone runs code the rule cannot see into, so it is reported, never moved.

    @Test
    fun `Verifier c11 - an injection below a throwing check stays and stays reported`() = Fixtures.assertReported(
        id,
        screen(
            """
            val context = LocalContext.current

            Text("header")

            checkNotNull(id) { "no id" }

            val vm = koinViewModel<VM>()

            Text("body ${'$'}vm")
            """,
        ),
        rules,
        "A read",
    )

    @Test
    fun `Verifier c12 - collected state whose initial value a call between changes stays and stays reported`() = Fixtures.assertReported(
        id,
        "@Composable\nfun Screen(vm: VM) {\n\n    val context = LocalContext.current\n\n    Text(\"header\")\n\n    bump()\n\n    val count = vm.flow.collectAsState(initial = Global.n)\n\n    Text(\"count \${count.value}\")\n}\n",
        rules,
        "A read",
    )

    @Test
    fun `Verifier c13 - collected state that an implicit receiver's call changes stays and stays reported`() = Fixtures.assertReported(
        id,
        "class Holder(val vm: VM) {\n\n    var shown = 0\n\n    fun markShown() {\n        shown = 7\n    }\n\n    @Composable\n    fun Screen() {\n\n" +
            "        val context = LocalContext.current\n\n        Text(\"header\")\n\n        markShown()\n\n        val count = vm.flow.collectAsState(initial = shown)\n\n" +
            "        Text(\"count \${count.value}\")\n    }\n}\n",
        rules,
        "A read",
    )

    @Test
    fun `Verifier c14 - an injection whose constructor changes what content shows stays and stays reported`() = Fixtures.assertReported(
        id,
        screen(
            """
            val context = LocalContext.current

            Text("before vm: ${'$'}{Global.n}")

            val vm = koinViewModel<VM>()

            Text("after vm: ${'$'}{Global.n}")
            """,
        ),
        rules,
        "A read",
    )

    @Test
    fun `Verifier c15 - a read that uses a local class declared after the reads is neither moved nor reported`() = assertLeftAlone(
        screen(
            """
            val context = LocalContext.current

            Text("header")

            class Cfg {
                val name = "local"
            }

            val label = LocalContext.current.let { Cfg().name }

            Text(label)
            """,
        ),
    )

    @Test
    fun `a wrapped or chained CompositionLocal read stays and stays reported`() {
        for (read in listOf("requireNotNull(LocalActivity.current)", "LocalConfiguration.current.screenWidthDp", "LocalActivity.current!!")) {
            Fixtures.assertReported(
                id,
                screen(
                    """
                    val start = remember { Start() }

                    val value = $read

                    Content(start = start, value = value)
                    """,
                ),
                rules,
                "A read",
            )
        }
    }

    // What keeps even a CompositionLocal read alone in place.

    @Test
    fun `a CompositionLocal whose name the function declares stays and stays reported`() = Fixtures.assertReported(
        id,
        screen(
            """
            var LocalTheme = LocalLightTheme

            fun useDark() {
                LocalTheme = LocalDarkTheme
            }

            if (dark == null) return

            Header()

            useDark()

            val theme = LocalTheme.current

            Content(theme = theme)
            """,
        ),
        rules,
        "A read",
    )

    @Test
    fun `a CompositionLocal named by a var of the file that a call between reassigns stays and stays reported`() = Fixtures.assertReported(
        id,
        "class Holder {\n\n    var LocalTheme = LocalLightTheme\n\n    fun useDark() {\n        LocalTheme = LocalDarkTheme\n    }\n\n    @Composable\n    fun Screen() {\n\n" +
            "        Header()\n\n        useDark()\n\n        val theme = LocalTheme.current\n\n        Content(theme = theme)\n    }\n}\n",
        rules,
        "A read",
    )

    @Test
    fun `a read crossing a read that stays and uses its name stays and stays reported`() = Fixtures.assertReported(
        id,
        "@Composable\nfun MainScreen(activity: Activity?) {\n\n    val start = remember { Start() }\n\n" +
            "    val vm = koinViewModel<HomeViewModel>(key = activity?.localClassName)\n    val activity = LocalActivity.current\n\n" +
            "    Content(start = start, vm = vm, activity = activity)\n}\n",
        rules,
        "A read",
    )

    @Test
    fun `a read sharing its line with another statement stays and stays reported`() = Fixtures.assertReported(
        id,
        screen(
            """
            val start = remember { Start() }

            Header(start = start); val color = LocalColor.current

            Content(color = color)
            """,
        ),
        rules,
        "A read",
    )

    @Test
    fun `a read whose landing statement shares its line stays and stays reported`() = Fixtures.assertReported(
        id,
        screen(
            """
            val context = LocalContext.current; val start = remember { Start() }

            Header(start = start)

            val color = LocalColor.current

            Content(context = context, color = color)
            """,
        ),
        rules,
        "A read",
    )

    // Negative cases that 0.0.4 already decided: untouched by format, and check reports what 0.0.4 reported.

    @Test
    fun `a read that uses a value declared after the reads is neither moved nor reported`() = assertLeftAlone(
        screen(
            """
            val backStack = rememberNavBackStack(AppRoute.Splash())

            val mainViewModel = if (backStack.isEmpty()) null else koinViewModel<MainViewModel>()

            val uiState = mainViewModel?.uiState?.collectAsStateWithLifecycle()?.value

            Content(uiState = uiState)
            """,
        ),
    )

    @Test
    fun `a read whose name an earlier statement already uses is neither moved nor reported`() =
        // `title` reads the parameter `activity`; moving the local `activity` above it would capture the local instead
        assertLeftAlone(
            "@Composable\nfun MainScreen(activity: Activity?) {\n\n    val title = remember { activity?.title }\n\n    val activity = LocalActivity.current\n\n    Content(title = title, activity = activity)\n}\n",
        )

    @Test
    fun `a read crossing a call on the object it reads stays and stays reported`() = Fixtures.assertReported(
        id,
        "@Composable\nfun Screen(viewModel: HomeViewModel) {\n\n    viewModel.refresh()\n\n    val state by viewModel.state.collectAsStateWithLifecycle()\n\n    Content(state = state)\n}\n",
        rules,
        "A read",
    )

    @Test
    fun `a derived value after an effect is reported, not moved`() = Fixtures.assertReported(
        id,
        screen(
            """
            LaunchedEffect(Unit) {
                load()
            }

            val start = remember { Start() }

            Content(start = start)
            """,
        ),
        rules,
        "A derived value after an effect",
    )

    @Test
    fun `reads already first are left alone`() = assertLeftAlone(
        screen(
            """
            val activity = LocalActivity.current
            val state by koinViewModel<HomeViewModel>().uiState.collectAsStateWithLifecycle()

            val start = remember { Start() }

            Content(activity = activity, state = state, start = start)
            """,
        ),
    )
}
