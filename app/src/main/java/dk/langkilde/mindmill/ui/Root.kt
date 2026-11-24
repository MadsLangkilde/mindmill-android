package dk.langkilde.mindmill.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.rememberDrawerState
import dk.langkilde.mindmill.remote.TestTarget
import dk.langkilde.mindmill.remote.string
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dk.langkilde.mindmill.Notice
import dk.langkilde.mindmill.R
import dk.langkilde.mindmill.remote.ConnectionState
import dk.langkilde.mindmill.remote.RemoteException
import dk.langkilde.mindmill.remote.bool
import dk.langkilde.mindmill.remote.jsonOf
import dk.langkilde.mindmill.remote.obj
import dk.langkilde.mindmill.remote.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The app's frame. Navigation is a [NavState] owned here (saved across rotation and process death) and drawn by
 * Navigation 3 in [MindmillNavDisplay]; see [NavState] for the back rules. [open] is a screen a notification asked
 * for: it goes on top of where the user is.
 */
@Composable
fun MindmillRoot(open: Screen? = null, onOpened: () -> Unit = {}) {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val settings by app.settings.collectAsState()
    val navigator = rememberSaveable(saver = Navigator.saver { NavState.initial(Mode.of(settings.mode)) }) {
        Navigator(NavState.initial(Mode.of(settings.mode)))
    }
    val mode = navigator.state.mode
    LaunchedEffect(mode) { if (settings.mode != mode.id) app.updateSettings { it.copy(mode = mode.id) } }
    LaunchedEffect(open) {
        if (open != null) {
            navigator.go(open)
            onOpened()
        }
    }

    val activeMac by app.activeMac.collectAsState()
    var shownMac by rememberSaveable { mutableStateOf(activeMac) }
    if (shownMac != activeMac) {
        shownMac = activeMac
        navigator.restart()
        SessionArchive.overrides.clear()
    }

    if (conn is ConnectionState.NotPaired || conn is ConnectionState.Unauthorized) {
        PairingScreen((conn as? ConnectionState.Unauthorized)?.message)
        return
    }
    key(activeMac) { MacFrame(navigator, conn) }
    ConfirmationDialog()
}

/**
 * Everything that shows one Mac's data; drawn afresh for another Mac so nothing of the last one stays on screen.
 * Navigation is a drawer: opened from the top bar on a phone, always open beside the content on a wide window.
 */
@Composable
private fun MacFrame(navigator: Navigator, conn: ConnectionState) {
    val mode = navigator.state.mode
    val structure = rememberStructure()
    val kb = (conn as? ConnectionState.Connected)?.hello?.sections.orEmpty()
    val sections = structure.sections(mode)
    val ui = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = WidthClass.of(maxWidth.value)
        val ctx = NavContext(mode, sections, kb, width)
        CompositionLocalProvider(LocalNavContext provides ctx) {
            if (width == WidthClass.EXPANDED) {
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    PermanentDrawerSheet(Modifier.width(300.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        DrawerContent(navigator, structure, kb, visible = true, close = {})
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        MainColumn(navigator, conn, twoPane = true, openDrawer = null)
                    }
                }
            } else {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                ModalNavigationDrawer(
                    drawerState = drawer,
                    gesturesEnabled = drawer.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(drawer, Modifier.widthIn(max = 340.dp)) {
                            DrawerContent(navigator, structure, kb, visible = drawer.isOpen, close = { ui.launch { drawer.close() } })
                        }
                    },
                ) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        MainColumn(navigator, conn, twoPane = false, openDrawer = { ui.launch { drawer.open() } })
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MainColumn(
    navigator: Navigator,
    conn: ConnectionState,
    twoPane: Boolean,
    openDrawer: (() -> Unit)?,
) {
    val nav = remember(navigator) { Nav(navigator) }
    TopBar(navigator, openDrawer)
    Column(Modifier.padding(horizontal = 16.dp)) {
        ConnectionBanner(conn)
        AppNotice(nav)
    }
    MindmillNavDisplay(navigator, twoPane, Modifier.weight(1f).fillMaxWidth()) { n, s -> ScreenContent(n, s) }
    FlashBar(nav)
}

/**
 * The windmill from the app icon, cropped out of its adaptive-icon safe zone so it follows whatever icon is installed.
 * As a tile it sits on the icon's cream, which reads in light and dark; [mono] draws the monochrome layer in [tint].
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, mono: Boolean = false, tint: Color = Color.White) {
    Box(
        modifier.clip(CircleShape).let { if (mono) it else it.background(Brand.Cream) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(if (mono) R.drawable.ic_launcher_monochrome else R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = if (mono) ColorFilter.tint(tint) else null,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
        )
    }
}

/**
 * The menu (the drawer) on the left, then back when there is something to go back to, the screen's headline, and
 * Ask Mindmill at the far right. A session's own menu sits just before Ask Mindmill.
 */
@Composable
private fun TopBar(navigator: Navigator, openDrawer: (() -> Unit)?) {
    val ctx = LocalNavContext.current
    val state = navigator.state
    val current = state.top
    val up = state.canGoUp
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (openDrawer != null) {
            IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, contentDescription = "Open navigation") }
        }
        if (up) {
            IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        val isStart = !up && current == NavState.startOf(ctx.mode)
        val title = ctx.sections.firstOrNull { !up && it.screen(ctx.mode, ctx.kbSections) == current }?.title
            ?.takeIf { current != Screen.Home } ?: current.title()
        Row(
            Modifier.weight(1f).padding(start = if (up || openDrawer != null) 4.dp else 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isStart) BrandMark(Modifier.padding(end = 10.dp).size(28.dp))
            AnimatedContent(title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { t ->
                Text(
                    t, style = if (up) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                )
            }
        }
        (current as? Screen.SessionChat)?.let { SessionMenu(it) { s -> navigator.go(s) } }
        if (current !is Screen.NewSession) {
            FilledTonalIconButton(
                onClick = { navigator.go(Screen.NewSession(ctx.mode.id)) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Icon(Icons.Default.AutoAwesome, contentDescription = "Ask Mindmill") }
        }
    }
}

/**
 * A session's own menu in the top bar: archive it or bring it back, show it on the Mac, and in Build, deliver what
 * it made to test (`test.status` for the session's project, then `test.deliver` for the picked target).
 */
@Composable
private fun SessionMenu(s: Screen.SessionChat, go: (Screen) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val (loaded, _) = rememberLoad(s.id) {
        app.call("session.get", jsonOf("id" to s.id, "since" to Int.MAX_VALUE)).obj("session")
    }
    val session = loaded.value
    val archived = SessionArchive.overrides[s.id] ?: session?.bool("archived") ?: s.archived
    val build = session?.string("space") == "build"
    val sessionScope = session?.string("scope")
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Session options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(max = 320.dp)) {
            if (build && open) {
                TestMenuItems(s.id, sessionScope, close = { open = false }, go = go)
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(if (archived) "Unarchive" else "Archive") },
                onClick = { open = false; SessionArchive.toggle(app, scope, s.id, s.title, !archived) },
            )
            DropdownMenuItem(
                text = { Text("Show on the Mac") },
                onClick = { open = false; scope.launch { app.runAndReport("app.show", jsonOf("screen" to "session", "id" to s.id)) } },
            )
        }
    }
}

/** Test, in a Build session's menu: one "Deliver …" per target of the session's project, or why it can't. */
@Composable
private fun TestMenuItems(session: String, scopeId: String?, close: () -> Unit, go: (Screen) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val (status, retry) = rememberLoad(session, scopeId) {
        TestTarget.list(app.call("test.status", jsonOf("scope" to scopeId)))
    }
    val openTest = { close(); go(Screen.Test) }
    Text("Test", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() })
    val targets = status.value
    when {
        targets == null && status.error != null -> DropdownMenuItem(
            text = { MenuText("Couldn't check testing", status.error) }, onClick = { retry() },
        )
        targets == null -> DropdownMenuItem(
            text = { MenuText("Checking what can be tested…", null) }, onClick = {}, enabled = false,
            leadingIcon = { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) },
        )
        targets.isEmpty() -> DropdownMenuItem(
            text = { MenuText("Nothing to test here yet", "This project has no test targets. Set one up on the Test screen.") },
            leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
            onClick = openTest,
        )
        else -> targets.forEach { t ->
            val why = t.whyNot
            DropdownMenuItem(
                text = { MenuText("Deliver ${t.title}", why?.let { "$it. Open Test to set it up." }) },
                leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                onClick = {
                    if (why != null) openTest() else {
                        close()
                        scope.launch {
                            app.runAndReport("test.deliver", jsonOf("target" to t.id, "scope" to scopeId, "session" to session))
                        }
                    }
                },
            )
        }
    }
    if (!targets.isNullOrEmpty()) {
        DropdownMenuItem(text = { Text("Open Test") }, onClick = openTest)
    }
}

@Composable
private fun MenuText(title: String, detail: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun Nav.follow(route: Any?) {
    when (route) {
        is Screen -> go(route)
        "test" -> go(Screen.Test)
        is Function0<*> -> route.invoke()
    }
}

@Composable
private fun AppNotice(nav: Nav) {
    val app = LocalApp.current
    val notice by app.notice.collectAsState()
    val n = notice ?: return
    NoticeBar(
        n.text, Modifier.padding(vertical = 4.dp),
        actionLabel = n.action,
        onAction = n.route?.let { r -> { app.dismissNotice(); nav.follow(r) } },
        onDismiss = { app.dismissNotice() },
        error = n.error,
    )
}

/** What just finished, at the bottom of the layout above the tabs; it goes away by itself. */
@Composable
private fun FlashBar(nav: Nav) {
    val app = LocalApp.current
    val flash by app.flash.collectAsState()
    val n: Notice = flash ?: return
    LaunchedEffect(n.id) {
        delay(if (n.action != null || n.error) 7_000 else 4_000)
        app.dismissFlash(n.id)
    }
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (n.error) cs.errorContainer else cs.inverseSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.text, style = MaterialTheme.typography.bodyMedium,
                color = if (n.error) cs.onErrorContainer else cs.inverseOnSurface,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (n.action != null && n.route != null) {
                TextButton(onClick = { app.dismissFlash(n.id); nav.follow(n.route) }) {
                    Text(n.action, color = if (n.error) cs.error else cs.inversePrimary)
                }
            }
            IconButton(onClick = { app.dismissFlash(n.id) }) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = if (n.error) cs.onErrorContainer else cs.inverseOnSurface)
            }
        }
    }
}

fun Screen.title(): String = when (this) {
    Screen.Home -> "Mindmill"
    Screen.MyWork -> "Overview"
    Screen.Backlog -> "Backlog"
    is Screen.Sessions -> "Sessions"
    Screen.Voice -> "Voice"
    Screen.More -> "More"
    is Screen.NewSession -> "New session"
    is Screen.Facts -> if (proposed) "Proposed facts" else sectionTitle(section)
    is Screen.Followups -> "Follow-ups"
    Screen.Meetings -> "Meetings"
    Screen.MacMeeting -> "Meeting on the Mac"
    Screen.Factories -> "Factories"
    is Screen.FactoryEditor -> if (id == null) "New factory" else if (duplicate) "Duplicate factory" else "Edit factory"
    Screen.Test -> "Test"
    Screen.Connection -> "Connection"
    Screen.PairMac -> "Pair another Mac"
    Screen.Projects -> "Projects"
    is Screen.ProjectEditor -> if (id == null) "New project" else "Edit project"
    is Screen.AllActions -> area?.let { dk.langkilde.mindmill.remote.ActionInfo.areaTitle(it) } ?: "All actions"
    is Screen.RunAction -> dk.langkilde.mindmill.remote.humanize(name.replace('.', ' '))
    Screen.Knowledge -> "Knowledge"
    is Screen.NewEntry -> "New ${sectionTitle(section).lowercase().removeSuffix("s")}"
    Screen.PullRequests -> "Pull requests"
    is Screen.PullRequest -> "Pull request !$id"
    Screen.Discussions -> "Discussions"
    Screen.People -> "People"
    Screen.Tags -> "Tags"
    is Screen.Command -> title
    is Screen.ActionView -> title
    is Screen.WorkItemDetail -> "Work item"
    is Screen.Entry -> "Entry"
    is Screen.SessionChat -> "Session"
    is Screen.MeetingDetail -> "Meeting"
    Screen.LiveMeeting -> "Meeting on this phone"
    is Screen.Web -> title
}

/** Lists whose rows open a detail: on a wide screen the detail opens beside the list. */
fun Screen.isListScreen(): Boolean = when (this) {
    is Screen.Sessions, Screen.Backlog, Screen.Meetings, is Screen.Facts, Screen.PullRequests, Screen.Discussions,
    Screen.Knowledge, Screen.Tags, Screen.MyWork, Screen.Factories, Screen.Projects -> true
    else -> false
}

/** What a list pane says until something is picked. */
fun Screen.pickHint(): String = when (this) {
    is Screen.Sessions -> "Choose a session to read and answer it here."
    Screen.Backlog -> "Choose a work item to see it here."
    Screen.Meetings -> "Choose a meeting to see its notes here."
    Screen.PullRequests -> "Choose a pull request to see it here."
    Screen.Factories -> "Choose a factory to edit it here."
    Screen.Projects -> "Choose a project to edit it here."
    Screen.MyWork -> "What you open shows here."
    else -> "Choose one to see it here."
}


/** The current mode and its sections, for screens that list them (More, Home). */
data class NavContext(
    val mode: Mode = Mode.PLAN,
    val sections: List<NavSection> = emptyList(),
    val kbSections: List<String> = emptyList(),
    val width: WidthClass = WidthClass.COMPACT,
)

val LocalNavContext = staticCompositionLocalOf { NavContext() }

/** Archive changes made on this phone, shown at once while the Mac's list catches up. */
object SessionArchive {
    val overrides = mutableStateMapOf<String, Boolean>()

    /** `session.archive`, or the action of that name on a Mac that has no method for it. */
    suspend fun set(app: dk.langkilde.mindmill.MindmillApp, id: String, archived: Boolean) {
        overrides[id] = archived
        try {
            try {
                app.call("session.archive", jsonOf("id" to id, "archived" to archived))
            } catch (e: RemoteException) {
                if (!e.isUnknownMethod) throw e
                val o = app.runAction("session.archive", jsonOf("id" to id, "archived" to archived))
                if (o.status == dk.langkilde.mindmill.remote.ActionOutcome.Status.FAILED) throw RemoteException("failed", o.speech)
            }
        } catch (e: Exception) {
            overrides.remove(id)
            throw e
        }
    }

    /** Archives or restores [id] and says so at the bottom, with Undo. */
    fun toggle(app: dk.langkilde.mindmill.MindmillApp, scope: kotlinx.coroutines.CoroutineScope, id: String, title: String, archive: Boolean) {
        scope.launch {
            try {
                set(app, id, archive)
                val undo: () -> Unit = { toggle(app, scope, id, title, !archive) }
                app.done(if (archive) "Archived “$title”." else "Restored “$title”.", "Undo", undo)
            } catch (e: Exception) {
                app.done(e.userMessage(), error = true)
            }
        }
    }
}

/** `app.structure`, kept fresh; the built-in structure when the Mac is older or hasn't answered yet. */
@Composable
private fun rememberStructure(): AppStructure {
    val app = LocalApp.current
    val conn by app.connection.state.collectAsState()
    val refresh by app.refresh.collectAsState()
    val hello = (conn as? ConnectionState.Connected)?.hello
    var loaded by remember { mutableStateOf<AppStructure?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        app.events.collectLatest { e ->
            if (e.name == "session.changed" || e.name == "structure.changed") {
                delay(1_500)
                tick++
            }
        }
    }
    val scopeId = rememberCurrentScope()
    LaunchedEffect(hello != null, scopeId, refresh, tick) {
        if (hello == null) return@LaunchedEffect
        try {
            loaded = AppStructure.parse(app.call("app.structure", jsonOf("scope" to app.currentScope)))
        } catch (e: RemoteException) {
            if (e.isUnknownMethod) loaded = null
        } catch (_: Exception) {
        }
    }
    val kb = hello?.sections.orEmpty()
    return remember(loaded, kb) { (loaded ?: AppStructure.default(kb)).forPhone(kb) }
}


/** How the connection is, in the layout: quiet while connecting, a countdown while retrying, reasons and tips when stuck. */
@Composable
fun ConnectionBanner(conn: ConnectionState) {
    val app = LocalApp.current
    when (conn) {
        is ConnectionState.Connecting -> Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                when {
                    conn.attempt == 0 && !conn.wasConnected -> "Connecting to ${conn.macName}…"
                    conn.attempt == 0 -> "Reconnecting to ${conn.macName}…"
                    else -> "Trying ${conn.macName} again…"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        is ConnectionState.Reconnecting -> {
            val left = secondsUntil(conn.retryAtMillis)
            NoticeBar(
                conn.problem.message(conn.macName) + if (left > 0) " Retrying in ${left}s." else " Retrying…",
                Modifier.padding(vertical = 4.dp), actionLabel = "Retry now", onAction = { app.connection.retryNow() },
            )
        }
        is ConnectionState.Unreachable -> UnreachableCard(conn)
        else -> Unit
    }
}

@Composable
private fun UnreachableCard(conn: ConnectionState.Unreachable) {
    val app = LocalApp.current
    var open by remember(conn.problem.kind) { mutableStateOf(true) }
    val left = secondsUntil(conn.retryAtMillis)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Can't reach ${conn.macName}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "Less" else "Why?") }
            }
            Text(conn.problem.message(conn.macName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (open) {
                conn.problem.tips(conn.macName).forEach { tip ->
                    Text("• $tip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { app.connection.retryNow() }) { Text("Retry") }
                if (left > 0) {
                    Text("Trying again in ${left}s", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}


@Composable
private fun ScreenContent(nav: Nav, screen: Screen) {
    when (val s = screen) {
        Screen.Home -> HomeScreen(nav)
        Screen.MyWork -> MyWorkScreen(nav)
        Screen.Backlog -> BacklogScreen(nav)
        is Screen.Sessions -> SessionsScreen(nav, Mode.of(s.space))
        Screen.Voice -> VoiceScreen(nav)
        Screen.More -> MoreScreen(nav)
        is Screen.NewSession -> NewSessionScreen(nav, Mode.of(s.space))
        is Screen.Facts -> FactsScreen(nav, s.proposed, s.section)
        is Screen.Followups -> FollowupsScreen(s.filter)
        Screen.Meetings -> MeetingsScreen(nav)
        Screen.MacMeeting -> MacMeetingScreen(nav)
        Screen.Factories -> FactoriesScreen(nav)
        is Screen.FactoryEditor -> FactoryEditorScreen(nav, s.id, s.duplicate)
        Screen.Test -> TestScreen(nav)
        Screen.Connection -> ConnectionScreen(nav)
        Screen.PairMac -> PairingScreen(reason = null, embedded = true, onPaired = { nav.back() })
        Screen.Projects -> ProjectsScreen(nav)
        is Screen.ProjectEditor -> ProjectEditorScreen(nav, s.id)
        is Screen.AllActions -> AllActionsScreen(nav, s.area)
        is Screen.RunAction -> RunActionScreen(nav, s.name, s.defaults)
        Screen.Knowledge -> KnowledgeScreen(nav)
        is Screen.NewEntry -> NewEntryScreen(nav, s.section)
        Screen.PullRequests -> PullRequestsScreen(nav)
        is Screen.PullRequest -> PullRequestScreen(nav, s.id, s.title)
        Screen.Discussions -> DiscussionsScreen(nav)
        Screen.People -> PeopleScreen()
        Screen.Tags -> TagsScreen(nav)
        is Screen.Command -> CommandScreen(s.args, s.about)
        is Screen.ActionView -> ActionViewScreen(nav, s.action, s.area, s.fallbackArgs, s.about)
        is Screen.WorkItemDetail -> WorkItemScreen(s.item)
        is Screen.Entry -> EntryScreen(nav, s.id)
        is Screen.SessionChat -> SessionChatScreen(nav, s.id, s.title)
        is Screen.MeetingDetail -> MeetingDetailScreen(s.id, s.title)
        Screen.LiveMeeting -> LiveMeetingScreen(nav)
        is Screen.Web -> WebScreen(s.title, s.url, s.preview)
    }
}
