package org.matrix.vector.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.matrix.vector.manager.data.repository.VectorLogSource
import org.matrix.vector.manager.data.repository.VectorStoreInstallHost
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.navigation.Canary
import org.matrix.vector.manager.ui.navigation.CrashTrace
import org.matrix.vector.manager.ui.navigation.DeepLink
import org.matrix.vector.manager.ui.navigation.FrameworkUpdate
import org.matrix.vector.manager.ui.navigation.LogTrace
import org.matrix.vector.manager.ui.navigation.Scope
import org.matrix.vector.manager.ui.navigation.StoreDetail
import org.matrix.vector.manager.ui.navigation.SystemStatus
import org.matrix.vector.manager.ui.navigation.TOP_LEVEL_DESTINATIONS
import org.matrix.vector.manager.ui.navigation.TopLevel
import org.matrix.vector.manager.ui.navigation.TopLevelRoute
import org.matrix.vector.manager.ui.navigation.Troubleshoot
import org.matrix.vector.manager.ui.navigation.VectorFloatingNavSettings
import org.matrix.vector.manager.ui.navigation.VectorNavPanelStore
import org.matrix.vector.manager.ui.navigation.Web
import org.matrix.vector.manager.ui.screens.canary.CanaryScreen
import org.matrix.vector.manager.ui.screens.home.CrashTraceScreen
import org.matrix.vector.manager.ui.screens.home.HomeScreen
import org.matrix.vector.manager.ui.screens.home.SystemStatusScreen
import org.matrix.vector.manager.ui.screens.modules.ModulesScreen
import org.matrix.vector.manager.ui.screens.modules.ScopeScreen
import org.matrix.vector.manager.ui.screens.report.TroubleshootScreen
import org.matrix.vector.manager.ui.screens.update.FrameworkUpdateScreen
import org.matrix.vector.manager.ui.screens.web.WebScreen
import org.matrix.vector.manager.ui.screens.web.fetchStoreSubresource
import org.matrix.vector.manager.ui.screens.web.forWebView
import org.matrix.vector.ui.logs.LogTraceScreen
import org.matrix.vector.ui.logs.LogsScreen
import org.matrix.vector.ui.navigation.FloatingPanelNav
import org.matrix.vector.ui.navigation.LocalNavigator
import org.matrix.vector.ui.navigation.Navigator
import org.matrix.vector.ui.navigation.PanelBar
import org.matrix.vector.ui.navigation.PanelEditDone
import org.matrix.vector.ui.navigation.rememberNavigator
import org.matrix.vector.ui.store.RepoDetailsScreen
import org.matrix.vector.ui.store.RepoScreen

/** AOSP-native navigation transition duration, in milliseconds. */
private const val NATIVE_BACK_TRANSITION_MS = 300

/**
 * AOSP full-screen predictive back is a *fade-through*, not a slide: the current page shrinks to
 * 90% and fades out while the previous page — which starts a touch larger at 110% — settles to 100%
 * and fades in. No horizontal travel at all; the half-width slide reads as flipping pages.
 *
 * Both specs use `LinearEasing` on purpose. During the gesture the top screen is not yet at its
 * destination: Navigation 3 *seeks* the transition to the finger's progress
 * (`SeekableTransitionState.seekTo(progress, ...)`) rather than playing it to completion, so a
 * curved easing would read as the page lagging the hand — it would not reach the framebuffer the
 * finger is already at. WeKitThemeGenerator drives the same effect by mapping `backEvent.progress`
 * straight into a `graphicsLayer` alpha (`1f - progress`); that is linear, 1:1, and is exactly what
 * the seeked scale/fade needs to match, or the page won't feel like it is being dragged.
 */
private val PREDICTIVE_BACK_SCALE_SPEC: FiniteAnimationSpec<Float> =
    tween(NATIVE_BACK_TRANSITION_MS, easing = LinearEasing)

/** The cross-fade of the previous page during a predictive back gesture. */
private val PREDICTIVE_BACK_FADE_SPEC: FiniteAnimationSpec<Float> =
    tween(NATIVE_BACK_TRANSITION_MS, easing = LinearEasing)

/**
 * The AOSP full-screen back transition, shared by predictive-pop and by the plain pop a system back
 * button (or a mouse's right-click back) produces.
 *
 * Navigation 3 applies it to both directions of a pop: the outgoing screen scales 100%→90% and
 * fades out while the incoming screen — which started at 110% — settles to 100% and fades in,
 * crossing at the middle. Both halves use [PREDICTIVE_BACK_SCALE_SPEC]/[PREDICTIVE_BACK_FADE_SPEC],
 * which are linear, so the two drives read the same: predictive back seeks the transition to the
 * finger's progress (see the note on those specs), while a button pop plays it to completion on its
 * own.
 */
private fun fadeThroughBackTransition(): ContentTransform {
    val exit =
        scaleOut(
            targetScale = 0.9f,
            animationSpec = PREDICTIVE_BACK_SCALE_SPEC,
        ) + fadeOut(animationSpec = PREDICTIVE_BACK_FADE_SPEC)
    val enter =
        scaleIn(
            initialScale = 1.1f,
            animationSpec = PREDICTIVE_BACK_SCALE_SPEC,
        ) + fadeIn(animationSpec = PREDICTIVE_BACK_FADE_SPEC)
    return enter togetherWith exit
}

/**
 * A [NavigationSuiteScaffoldState] whose bar is animated with the same motion as the destination
 * transition, so the bar and the page move as one.
 *
 * The scaffold's own `rememberNavigationSuiteScaffoldState` hides/shows the bar with a fixed spring
 * — crisp, but its own tempo. A back press then animates the bar on a spring while the page it is
 * attached to fades through on the 300ms linear curve, so the two read as separate motions: the bar
 * snaps into place while the page is still settling. This replaces the spring with the same
 * `tween(NATIVE_BACK_TRANSITION_MS, LinearEasing)` the destination transitions use, so the bar
 * slides/fades in lockstep with the page rather than on its own clock.
 *
 * Everything else — [isAnimating], [targetValue], [currentValue], the Saver — mirrors the default
 * implementation, because the scaffold reads those to lay out and to decide whether to consume
 * insets. Only the animation spec differs.
 */
@Composable
private fun rememberVectorSuiteState(): NavigationSuiteScaffoldState {
    return rememberSaveable(saver = rememberVectorSuiteStateSaver()) { VectorSuiteState() }
}

private fun rememberVectorSuiteStateSaver():
    Saver<NavigationSuiteScaffoldState, NavigationSuiteScaffoldValue> =
    Saver(
        save = { it.targetValue },
        restore = { VectorSuiteState(initialValue = it) },
    )

private class VectorSuiteState(
    initialValue: NavigationSuiteScaffoldValue = NavigationSuiteScaffoldValue.Visible
) : NavigationSuiteScaffoldState {
    private val internalValue: Float =
        if (initialValue == NavigationSuiteScaffoldValue.Visible) VISIBLE else HIDDEN
    private val internalState = Animatable(internalValue, Float.VectorConverter)
    private val _currentValue = derivedStateOf {
        if (internalState.value == VISIBLE) NavigationSuiteScaffoldValue.Visible
        else NavigationSuiteScaffoldValue.Hidden
    }

    /** The same curve as the destination fade-through, so the bar tracks the page. */
    private val spec: FiniteAnimationSpec<Float> =
        tween(NATIVE_BACK_TRANSITION_MS, easing = LinearEasing)

    override val isAnimating: Boolean
        get() = internalState.isRunning

    override val targetValue: NavigationSuiteScaffoldValue
        get() =
            if (internalState.targetValue == VISIBLE) NavigationSuiteScaffoldValue.Visible
            else NavigationSuiteScaffoldValue.Hidden

    override val currentValue: NavigationSuiteScaffoldValue
        get() = _currentValue.value

    override suspend fun hide() {
        internalState.animateTo(targetValue = HIDDEN, animationSpec = spec)
    }

    override suspend fun show() {
        internalState.animateTo(targetValue = VISIBLE, animationSpec = spec)
    }

    override suspend fun toggle() {
        internalState.animateTo(
            targetValue =
                if (targetValue == NavigationSuiteScaffoldValue.Visible) HIDDEN else VISIBLE,
            animationSpec = spec,
        )
    }

    override suspend fun snapTo(targetValue: NavigationSuiteScaffoldValue) {
        internalState.snapTo(
            if (targetValue == NavigationSuiteScaffoldValue.Visible) VISIBLE else HIDDEN
        )
    }

    private companion object {
        const val HIDDEN = 0f
        const val VISIBLE = 1f
    }
}

/**
 * The app shell.
 *
 * [NavigationSuiteScaffold] picks the navigation container from the window size — a bottom bar on a
 * phone, a rail when there is width to spare. That is not decoration: from targetSdk 37 an app may
 * no longer lock itself to portrait or declare itself non-resizable on large screens, so the shell
 * has to work unfolded and in landscape regardless. The scaffold also owns where that container
 * sits, so the destinations below it are laid out beside or above it rather than under it.
 *
 * Which panels that container holds, in which order, is the reader's — see NavPanels — and there is
 * a third arrangement it can take, a ball floating over the content with no container at all. The
 * two are not independent: rearranging the panels needs something to rearrange, so edit mode always
 * puts the container back for as long as it lasts.
 */
@Composable
fun VectorApp() {
    val navigator = rememberNavigator(VectorNavPanelStore, TOP_LEVEL_DESTINATIONS, TopLevel)

    // Where the launch intent asked to open. The activity has no back stack to act on, so it leaves
    // the destination here and this is the first place there is one — on a cold start the splash is
    // still playing when the intent arrives.
    val pending by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val destination = DeepLink.consume() ?: return@LaunchedEffect
        // Already there, so nothing to do — and doing it anyway would not be nothing: switching
        // tabs empties the back stack and builds it again, and the scope editor's draft lives in a
        // ViewModel scoped to the entry that would be thrown away with it. The reader who taps the
        // notification of the module already open in front of them is the case this covers.
        //
        // It is not what keeps a rotation harmless. Whether an offer is a launch or a recreation
        // replaying the intent it was created with is decided in DeepLink, which knows what it last
        // applied; here there is only where the reader is standing.
        if (navigator.current == (destination.detail ?: destination.tab)) return@LaunchedEffect
        // The tab goes down first and the screen on top of it: a notification about a module opens
        // that module's scope editor, and back from there should be the module list rather than the
        // door out of the app it just opened. Switching also discards whatever detail screen was
        // already up, so the reader is not left with a stale one buried underneath.
        navigator.switchTo(destination.tab)
        destination.detail?.let { navigator.go(it) }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        val settings = ServiceLocator.settings
        val floating by settings.floatingNav.collectAsStateWithLifecycle()
        val editing = navigator.editingPanels
        // The container shows only at the root of a panel. On a detail screen none of the items is
        // the current destination, and a navigation bar highlighting nothing is worse than none.
        val atRoot = !navigator.canGoBack

        // Driving the scaffold's own state rather than dropping the items: hiding the items alone
        // leaves the container laid out, so a detail screen — the in-app browser especially —
        // keeps a dead strip of navigation-bar-sized space at the bottom.
        val suiteState = rememberVectorSuiteState()
        LaunchedEffect(atRoot) { if (atRoot) suiteState.show() else suiteState.hide() }

        // Computed rather than left to the scaffold's default, for two reasons: the floating style
        // forces None, which is what actually removes the container instead of hiding it, and
        // PanelBar has to be told which axis it is laying items along. Entering edit mode overrules
        // the floating setting for as long as it lasts — there is nothing to rearrange otherwise.
        val suiteType =
            if (floating && !editing) NavigationSuiteType.None
            else NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

        NavigationSuiteScaffold(
            navigationItems = {
                // NavigationSuite's `when` over the type has no None branch and no else, so under
                // None this slot is silently dropped along with the container. Skipping it here
                // says so out loud rather than leaving a composable that never runs.
                if (suiteType != NavigationSuiteType.None) {
                    PanelBar(
                        panels = navigator.panels,
                        current = navigator.currentTopLevel,
                        editing = editing,
                        suiteType = suiteType,
                        onSelect = { route -> navigator.switchTo(route) },
                        onEdit = { navigator.editingPanels = true },
                        onToggleHidden = { key, hidden -> navigator.setPanelHidden(key, hidden) },
                        onMove = { from, to -> navigator.movePanel(from, to) },
                    )
                }
            },
            navigationSuiteType = suiteType,
            state = suiteState,
            primaryActionContent = {
                if (editing) PanelEditDone(onDone = { navigator.editingPanels = false })
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                // One NavDisplay owns the whole stack. The root is the fixed TopLevel container,
                // which renders the finger-following pager of panels; a detail (scope editor, store
                // detail, browser…) is a second entry pushed above it by Navigator.go. Because the
                // display stays mounted instead of being swapped for a separate pager branch, a
                // system back press / mouse right-click pops the detail and plays the
                // popTransitionSpec fade-through — the previous page shows behind the shrinking
                // current one — rather than the whole thing vanishing the instant the stack is back
                // to the root.
                NavDisplay(
                    backStack = navigator.backStack,
                    onBack = { navigator.back() },
                    // Naming any decorator replaces NavDisplay's default, which is the
                    // saveable-state one alone, so it is repeated here; the scene-setup
                    // decorator NavDisplay applies internally is untouched. The ViewModel one
                    // is what this list is for: it scopes a ViewModelStore per entry, so
                    // opening the scope editor for a second module builds a second ViewModel
                    // instead of reusing the first (they would otherwise share one default key
                    // under the activity's store).
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    // Forward is the mirror of back, so it uses the same AOSP fade-through: the
                    // new page shrinks in from 110% and fades in while the page it covers shrinks
                    // to 90% and fades out. Push and pop are therefore one symmetric motion rather
                    // than opposite ones (the official sample's half-width slide is not used here,
                    // because a slide forward plus a fade-through back would feel like two
                    // different navigations).
                    transitionSpec = { fadeThroughBackTransition() },
                    popTransitionSpec = { fadeThroughBackTransition() },
                    predictivePopTransitionSpec = { _ ->
                        // AOSP full-screen predictive back, symmetric: the swipe edge is unused
                        // so the gesture reads the same from either side.
                        // `fadeThroughBackTransition`
                        // is shared with the plain button pop above, so a back-key press and a back
                        // drag land the reader in the same place — the previous page revealed
                        // behind
                        // the shrinking current one, the one difference being that the gesture
                        // tracks the finger 1:1 via seekTo(progress).
                        fadeThroughBackTransition()
                    },
                    entryProvider = entryProvider { registerRoutes(navigator) },
                )
                // Last child of the Box so it draws over the destination, and inside the app window
                // rather than in one of its own: parasitically this app is com.android.shell, which
                // must never ask for SYSTEM_ALERT_WINDOW. It follows the same rule the container
                // does — present at the root of a panel, gone on a detail screen that has its own
                // back affordance.
                if (floating && !editing && atRoot) {
                    FloatingPanelNav(
                        panels = navigator.panels,
                        current = navigator.currentTopLevel,
                        onSelect = { route -> navigator.switchTo(route) },
                        settings = VectorFloatingNavSettings,
                    )
                }
            }
        }

        // After the scaffold on purpose. Back callbacks are dispatched last-registered-first and
        // BackHandler registers from an effect, which run in composition order, so this one
        // outranks the handler NavDisplay installs and edit mode ends before the stack is touched.
        BackHandler(enabled = editing) { navigator.editingPanels = false }
    }
}

/**
 * Every destination, registered.
 *
 * All four panels keep their entry whether or not the reader has hidden them. A saved stack names
 * its keys by class, and entryProvider throws for one it was never given, so dropping the
 * registration of a hidden panel would turn a stale saved stack into a crash.
 */

/**
 * The root of the stack: the top-level panels as a finger-following pager, in the reader's own
 * order.
 *
 * The pager owns the gesture; the navigator owns the truth. A swipe that settles on a page turns
 * that panel into the current one, and whatever else moves the navigator (a bar tap, a deep link, a
 * restored stack) drives the pager to the matching page. The two effects below are the only links,
 * and each guards itself so a change it itself caused does not recurse.
 *
 * A detail is never drawn here: it is pushed *above* this entry by [Navigator.go], and NavDisplay
 * keeps this entry mounted underneath it, so backing out of the detail reveals this pager (and the
 * panel you were on) instead of remounting it. Both directions are what let the pager's own scroll
 * position and each panel's ViewModel survive a detail round-trip.
 */
@Composable
private fun TopLevelContainer(navigator: Navigator) {
    val visible = navigator.panels.visible
    // Seed the pager at the panel the reader is actually on. [rememberPagerState] reads this on
    // first composition only; when the TopLevel entry is remounted after process death it starts
    // at the panel the reader was on rather than at page zero.
    val initialPage =
        visible.indexOfFirst { it.route == navigator.currentTopLevel }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { visible.size })
    // The navigator is the authority: wherever the current panel changed, page to it.
    LaunchedEffect(navigator.currentTopLevel, visible) {
        val page = visible.indexOfFirst { it.route == navigator.currentTopLevel }
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
        }
    }
    // The gesture is the authority: once a swipe settles, make that panel current. switchTo clears
    // the stack back to the root, so a swipe never leaves a stale detail buried under a new tab.
    LaunchedEffect(pagerState, visible, navigator) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                val target = visible.getOrNull(page)?.route ?: return@collect
                if (navigator.currentTopLevel != target) navigator.switchTo(target)
            }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        // Every panel in the catalogue routes to a TopLevelRoute; the downcast is how the NavKey
        // carried by TopLevelDestination becomes one here.
        val route = visible.getOrNull(page)?.route as? TopLevelRoute ?: return@HorizontalPager
        Box(Modifier.fillMaxSize()) { TopLevelPanelContent(route, navigator) }
    }
}

/**
 * The four top-level panels, composed for a given route.
 *
 * The pager draws one page per visible panel, in the reader's own order; the fixed [TopLevel] root
 * stores that pager in the back stack, so a detail pushed above it does not disturb which panel is
 * on display. A panel's wiring — what a tap on a row opens, which service supplies the data — has
 * one home here rather than copies at every place that might draw it.
 */
@Composable
private fun TopLevelPanelContent(route: TopLevelRoute, navigator: Navigator) {
    when (route) {
        TopLevelRoute.Home ->
            HomeScreen(
                onOpenStatus = { navigator.go(SystemStatus) },
                onOpenUrl = { url -> navigator.go(Web(url)) },
                onOpenCanary = { navigator.go(Canary) },
                onOpenReport = { navigator.go(Troubleshoot) },
                onOpenUpdate = { navigator.go(FrameworkUpdate()) },
            )

        TopLevelRoute.Modules ->
            ModulesScreen(
                onModuleClick = { packageName, userId -> navigator.go(Scope(packageName, userId)) },
                onOpenStore = { packageName -> navigator.go(StoreDetail(packageName)) },
            )

        TopLevelRoute.Store ->
            RepoScreen(
                onModuleClick = { packageName -> navigator.go(StoreDetail(packageName)) },
                dataSource = ServiceLocator.store,
                settings = ServiceLocator.settings,
            )

        TopLevelRoute.Logs -> {
            val logSource = remember { VectorLogSource() }
            LogsScreen(source = logSource, onOpenTrace = { text -> navigator.go(LogTrace(text)) })
        }
    }
}

private fun EntryProviderScope<NavKey>.registerRoutes(navigator: Navigator) {
    // The stack's only ever-present entry: the pager of panels. A detail is pushed above it. The
    // four TopLevelRoute entries are not registered as destinations — the pager draws each panel's
    // content directly, so the stack never contains a TopLevelRoute and entryProvider never has to
    // construct one for a saved stack.
    entry<TopLevel> { TopLevelContainer(navigator) }

    entry<Scope> { route ->
        ScopeScreen(
            packageName = route.packageName,
            userId = route.userId,
            onNavigateBack = { navigator.back() },
        )
    }
    entry<StoreDetail> { route ->
        RepoDetailsScreen(
            packageName = route.packageName,
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            dataSource = ServiceLocator.store,
            settings = ServiceLocator.settings,
            host = remember(route.packageName) { VectorStoreInstallHost(route.packageName) },
            fetchSubresource = { fetchStoreSubresource(ServiceLocator.http, it) },
            contextForWebView = { ctx, dark -> ctx.forWebView(dark) },
        )
    }
    entry<SystemStatus> {
        SystemStatusScreen(
            onNavigateBack = { navigator.back() },
            onOpenCrash = { navigator.go(CrashTrace) },
        )
    }
    entry<CrashTrace> { CrashTraceScreen(onNavigateBack = { navigator.back() }) }
    entry<LogTrace> { route ->
        LogTraceScreen(text = route.text, onNavigateBack = { navigator.back() })
    }
    entry<Troubleshoot> {
        TroubleshootScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onOpenCanary = { navigator.go(Canary) },
        )
    }
    entry<Canary> {
        CanaryScreen(
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
            onInstall = { versionCode -> navigator.go(FrameworkUpdate(versionCode)) },
            onOpenReport = { navigator.go(Troubleshoot) },
        )
    }
    entry<FrameworkUpdate> { route ->
        FrameworkUpdateScreen(
            openOnVersionCode = route.versionCode.takeIf { it > 0 },
            onNavigateBack = { navigator.back() },
            onOpenUrl = { url -> navigator.go(Web(url)) },
        )
    }
    entry<Web> { route -> WebScreen(url = route.url, onNavigateBack = { navigator.back() }) }
}
