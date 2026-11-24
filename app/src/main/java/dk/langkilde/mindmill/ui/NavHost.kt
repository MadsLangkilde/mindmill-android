package dk.langkilde.mindmill.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent

private const val META_LIST = "mindmill.list"
private const val META_SECTION = "mindmill.section"
private const val META_HINT = "mindmill.hint"

/** Set while an entry is drawn as the list beside its detail: the id of that list entry. */
private val LocalListPane = compositionLocalOf<Long?> { null }

/**
 * The screens of [navigator]'s back stack, drawn by Navigation 3: system back and the predictive back gesture pop
 * it, each entry keeps its saved state (scroll, fields) for as long as it is on any stack, and with [twoPane] a list
 * shows its detail beside it.
 */
@Composable
fun MindmillNavDisplay(
    navigator: Navigator,
    twoPane: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Nav, Screen) -> Unit,
) {
    val state = navigator.state
    val holder = rememberSaveableStateHolder()
    val decorator = remember(holder) {
        NavEntryDecorator<NavEntryKey>(decorate = { entry -> holder.SaveableStateProvider(entry.contentKey) { entry.Content() } })
    }
    val known = remember { mutableSetOf<Long>() }
    val alive = state.allIds
    LaunchedEffect(alive) {
        (known - alive).forEach { holder.removeState("e$it") }
        known.clear()
        known.addAll(alive)
    }
    val nav = remember(navigator) { Nav(navigator) }
    val strategies = remember(twoPane) {
        if (twoPane) listOf<SceneStrategy<NavEntryKey>>(ListDetailStrategy(), SinglePaneSceneStrategy()) else listOf(SinglePaneSceneStrategy())
    }
    NavDisplay(
        backStack = state.history,
        modifier = modifier,
        onBack = { navigator.back() },
        entryDecorators = listOf(decorator),
        sceneStrategies = strategies,
        transitionSpec = { forward() },
        popTransitionSpec = { backward() },
        predictivePopTransitionSpec = { edge -> predictiveBack(edge) },
        entryProvider = { key ->
            NavEntry(
                key,
                contentKey = key.contentKey,
                metadata = mapOf(
                    META_LIST to key.screen.isListScreen(),
                    META_SECTION to (navigator.state.sectionOf(key.id) ?: ""),
                    META_HINT to key.screen.pickHint(),
                ),
            ) { k ->
                val listId = LocalListPane.current
                val n = if (listId == k.id) remember(navigator, listId) { ListPaneNav(navigator, listId) } else nav
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) { content(n, k.screen) }
            }
        },
    )
}

private fun Scene<NavEntryKey>.section(): Any? = entries.lastOrNull()?.metadata?.get(META_SECTION)

/** Another section, or the other mode: a quiet fade through. Deeper in the same section: a short shared-axis slide. */
private fun AnimatedContentTransitionScope<Scene<NavEntryKey>>.forward(): ContentTransform =
    if (initialState.section() != targetState.section()) fadeThrough()
    else (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(220, 60))) togetherWith
        (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(120)))

private fun AnimatedContentTransitionScope<Scene<NavEntryKey>>.backward(): ContentTransform =
    if (initialState.section() != targetState.section()) fadeThrough()
    else (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(220, 60))) togetherWith
        (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 } + fadeOut(tween(120)))

private fun fadeThrough(): ContentTransform =
    (fadeIn(tween(210, 90, LinearOutSlowInEasing)) + scaleIn(tween(210, 90), initialScale = 0.96f)) togetherWith fadeOut(tween(90))

/** Follows the finger: the screen shrinks a little and moves toward the side it's swiped to, the one below fades in. */
private fun AnimatedContentTransitionScope<Scene<NavEntryKey>>.predictiveBack(edge: Int): ContentTransform {
    val dir = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
    return (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -dir * it / 10 }) togetherWith
        (scaleOut(tween(250), targetScale = 0.9f) + slideOutHorizontally(tween(250)) { dir * it / 6 } + fadeOut(tween(250)))
}

/**
 * On a wide window a list keeps its detail beside it: the top entry, when a list of the same section is below it,
 * or the list alone with a hint. Back closes the detail first.
 */
private class ListDetailStrategy : SceneStrategy<NavEntryKey> {
    override fun SceneStrategyScope<NavEntryKey>.calculateScene(entries: List<NavEntry<NavEntryKey>>): Scene<NavEntryKey>? {
        val top = entries.lastOrNull() ?: return null
        if (top.metadata[META_LIST] == true) {
            return ListDetailScene(top, null, entries.dropLast(1))
        }
        val section = top.metadata[META_SECTION]
        for (i in entries.lastIndex - 1 downTo 0) {
            val e = entries[i]
            if (e.metadata[META_SECTION] != section) return null
            if (e.metadata[META_LIST] == true) return ListDetailScene(e, top, entries.dropLast(1))
        }
        return null
    }
}

private class ListDetailScene(
    val list: NavEntry<NavEntryKey>,
    val detail: NavEntry<NavEntryKey>?,
    override val previousEntries: List<NavEntry<NavEntryKey>>,
) : Scene<NavEntryKey> {
    override val key: Any = list.contentKey
    override val entries: List<NavEntry<NavEntryKey>> = listOfNotNull(list, detail)
    private val listId = list.contentKey.toString().removePrefix("e").toLong()

    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth = (maxWidth * 0.42f).coerceIn(320.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) {
                    CompositionLocalProvider(LocalListPane provides listId) { list.Content() }
                }
                VerticalDivider()
                AnimatedContent(
                    targetState = detail,
                    contentKey = { it?.contentKey },
                    transitionSpec = { fadeThrough() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "detail",
                ) { d ->
                    if (d != null) d.Content()
                    else ListEmptyState(list.metadata[META_HINT] as? String ?: "", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ListDetailScene && list == other.list && detail == other.detail && previousEntries == other.previousEntries

    override fun hashCode(): Int = (list.hashCode() * 31 + detail.hashCode()) * 31 + previousEntries.hashCode()
}
