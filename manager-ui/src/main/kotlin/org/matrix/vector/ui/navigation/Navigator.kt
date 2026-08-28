package org.matrix.vector.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * The back stack, as an object with intent-revealing operations.
 *
 * Navigation 3 hands you the stack as a plain observable list, so this is a handful of operations
 * over that list rather than a graph definition.
 *
 * Switching tabs truncates to a single root entry, so the stack can never grow without bound and
 * system back leaves the app instead of retracing every tab that was visited.
 *
 * It owns the panel arrangement too, which is why every surface reaches for it as
 * `LocalNavigator.current.panels`, and why there is no second CompositionLocal and no
 * `rememberNavPanels` anywhere: a second source of truth is exactly what would let the bar, the
 * ball and the appearance sheet disagree about which panels exist while the reader is rearranging
 * them. The two writes go through [NavPanelStore] for the same reason — the encoding has one home.
 */
@Stable
class Navigator(
    val backStack: NavBackStack<NavKey>,
    private val panelsState: State<NavPanels>,
    private val store: NavPanelStore,
    /** The panel the pager is on, as the saveable MutableState that survives process death. */
    private val currentTabState: MutableState<NavKey>,
    /** The fixed foot of the stack: the container a detail is pushed above. See the rootKey param. */
    private val rootKey: NavKey,
) {

    /**
     * Which panel the pager is on.
     *
     * Deliberately not the root of [backStack]: the stack's root is the fixed [rootKey] container,
     * and a detail (scope editor, store detail, browser…) is pushed above it. Switching tabs is a
     * pager gesture and must not touch the stack — otherwise a swipe would truncate the very
     * details it is meant to be over. Keeping the panel in its own state is what lets a detail be
     * pushed and popped without the current tab changing underneath it, and what lets backing out
     * of a detail land back on the same panel.
     *
     * Delegated to [currentTabState] (rememberSaveable-backed) rather than a private mutableStateOf:
     * there must be one source of truth, and it is the one that survives process death — which the
     * old code got for free by making the panel the stack root, and a fixed container root no longer
     * does.
     */
    var currentTab: NavKey
        get() = currentTabState.value
        set(value) {
            currentTabState.value = value
        }

    /** The reader's panels. A snapshot read, so a composable that touches it recomposes. */
    val panels: NavPanels
        get() = panelsState.value

    /**
     * Whether the navigation container is being rearranged.
     *
     * Transient by construction: a plain `mutableStateOf`, deliberately neither a
     * `rememberSaveable` nor a preference. Coming back to the app days later to find it still in
     * edit mode would be a puzzle, and the arrangement itself is already written the instant it
     * changes, so there is nothing here worth restoring.
     */
    var editingPanels: Boolean by mutableStateOf(false)

    val current: NavKey?
        get() = backStack.lastOrNull()

    /**
     * Which item is highlighted — the panel the pager is standing on.
     *
     * The pager is the current tab, not the stack root: the stack root is the fixed container
     * (the rootKey param), and the whole point is that a detail above it does not change which
     * panel you were on. So this reads [currentTab] rather than [backStack]. Navigation 3's own
     * back handling is entirely in terms of the stack, so this only feeds the containers — the bar
     * and the floating ball — which are the only things that care about "which panel".
     */
    val currentTopLevel: NavKey
        get() = currentTab

    val canGoBack: Boolean
        get() = backStack.size > 1

    /** Push a detail destination on top of the top-level pager. */
    fun go(route: NavKey) {
        // A detail always sits above the fixed container root; if the stack is somehow back to empty
        // (a restored stack that lacked the root), re-seed it rather than pushing a detail as the
        // stack's only entry.
        ensureRoot()
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /**
     * Put [route] where the current screen is, so back from it returns past this one.
     *
     * For a screen whose job ends the moment it is answered -- a picker that has been picked from.
     * Leaving it on the stack would make Back from the screen it opened return to the question the
     * reader has already answered.
     */
    fun replace(route: NavKey) {
        ensureRoot()
        if (backStack.size == 1) backStack.add(route) else backStack[backStack.lastIndex] = route
    }

    /**
     * Select a bar item, discarding whatever detail screens were open.
     *
     * This moves the pager (via [currentTab]) and unwinds the stack back to its fixed container
     * root. It does not truncate the stack to a *panel* the way it once did — the root is now
     * always the container, so "back to the tab" means clearing everything above it rather than
     * re-seeding the stack with the panel. That is what lets backing out of a detail land on the
     * panel you were on, and what lets a swipe between panels leave any (now-hidden) detail history
     * alone.
     */
    fun switchTo(tab: NavKey) {
        currentTab = tab
        // Keep only the fixed root; drop any detail that was above it. Clearing on every tab change
        // (even one already current) is fine: it is the honest representation of "the user asked for
        // this tab", and it is what discards a stale scope-editor draft on a bar tap.
        backStack.clear()
        backStack.add(rootKey)
    }

    /** Returns false when there is nothing left to pop, so the caller can let the system exit. */
    fun back(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Guarantee the stack's foot is [rootKey], appending it if the stack is empty. */
    private fun ensureRoot() {
        if (backStack.isEmpty()) backStack.add(rootKey)
    }

    /** Hide or restore a panel, and persist it. [key] is a TopLevelDestination.key. */
    fun setPanelHidden(key: String, hidden: Boolean) {
        store.setEncoded(encodeNavPanels(panels.withHidden(key, hidden)))
    }

    /** Reorder, and persist it. Both indices are into [NavPanels.all]. */
    fun movePanel(from: Int, to: Int) {
        store.setEncoded(encodeNavPanels(panels.withMoved(from, to)))
    }

    /**
     * Keep the current panel visibly drawn.
     *
     * The stack root is the fixed container and no longer names a panel, so the old
     * "replace a root that names a hidden panel" correction becomes "move off a current tab that has
     * been hidden". That is the same story as before — hiding the panel you are on puts you on the
     * first visible one — but it fixes [currentTab] instead of [backStack], because the tab is where
     * the pager is, and the pager is what would otherwise show nothing.
     */
    fun reconcilePanels() {
        if (!panels.isVisible(currentTab)) currentTab = panels.start.route
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator in composition") }

@Composable
fun rememberNavigator(
    store: NavPanelStore,
    catalogue: List<TopLevelDestination>,
    rootKey: NavKey,
): Navigator {
    val stored = store.encoded.collectAsStateWithLifecycle()
    // Derived rather than decoded on every recomposition: the string changes when a panel is
    // dragged or hidden and at no other time, while everything that reads the panels reads them
    // once per frame.
    val panels = remember(stored, catalogue) { derivedStateOf { decodeNavPanels(stored.value, catalogue) } }
    // rememberNavBackStack persists across process death via SavedState, which matters here:
    // parasitically the manager's activity state is hand-managed by the zygisk hooker, so
    // anything that relies on the system restoring it needs to survive that path too.
    //
    // The seed is the fixed root container, and only it: a restored stack may already carry a
    // detail above it (SavedStateRegistry and the hooker's per-activity Bundle cache both survive
    // process death), so the seed is never a panel that would have to be reconciled away.
    val backStack = rememberNavBackStack(rootKey)
    // The current tab also has to survive process death, and it cannot live in the stack (the stack
    // root is always the container). It is saved by the panel route's string key, and a route that
    // is no longer in the catalogue (a panel deleted since) falls back to the first visible one
    // below through reconcilePanels.
    //
    // The saver is over the MutableState (as rememberSaveable expects when it wraps a mutableStateOf)
    // but saves/restores only the inner NavKey's panel key: it is the panel, not the state object,
    // that must be serialized, and the panel key is what the catalogue maps back to a route.
    val tabSaver =
        Saver<MutableState<NavKey>, String>(
            save = { state -> catalogue.firstOrNull { it.route == state.value }?.key ?: "" },
            restore = { key ->
                catalogue.firstOrNull { it.key == key }?.route?.let { mutableStateOf(it) }
            },
        )
    // The MutableState is the single source of truth: Navigator reads/writes through it (see
    // Navigator.currentTab), so a tab change while running updates the very state that is saved.
    val currentTabState =
        rememberSaveable(saver = tabSaver) { mutableStateOf(panels.value.start.route) }
    val navigator =
        remember(backStack, panels, store) {
            Navigator(backStack, panels, store, currentTabState, rootKey)
        }
    // Keep the panel and the stack in step: a restored stack's root is the container, and the saved
    // tab may have stopped being visible (or existing) while the reader was away.
    LaunchedEffect(navigator, panels.value) { navigator.reconcilePanels() }
    return navigator
}
