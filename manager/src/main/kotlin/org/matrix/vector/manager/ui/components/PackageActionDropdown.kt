package org.matrix.vector.manager.ui.components

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Bolt
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Info
import com.composables.icons.materialsymbols.outlined.Notifications_off
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Restart_alt
import com.composables.icons.materialsymbols.outlined.Stop
import com.composables.icons.materialsymbols.outlined.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logW
import org.matrix.vector.manager.ui.screens.modules.ScopeViewModel.Companion.SYSTEM_FRAMEWORK_PACKAGE
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.ui.SnackbarTone

/**
 * The long-press action menu for one package, drawn as the Material 3 Expressive *grouped* dropdown.
 *
 * The caller composes this next to the row it belongs to, inside the same [Box]. The menu is a
 * [DropdownMenuPopup] — the Popup primitive underneath [DropdownMenu], used here so it can render a
 * [DropdownMenuGroup] whose items adopt the Expressive rounded item shapes. This is exactly the
 * pattern WeKit's `DropDownMenuWidget` uses (`DropdownMenuPopup` + [DropdownMenuGroup] +
 * `MenuDefaults.groupShapes()` + `MenuDefaults.itemShape(index, size)`), which is what gives the
 * menu its capsule-shaped items rather than flat rows.
 *
 * A `DropdownMenuPopup`'s position provider anchors to the parent layout node it is declared in, so
 * placing it as a sibling of the pressed row (inside the same `Box`) is what opens it beside that
 * row. `LocalizedOverlay` only applies the app's chosen language to the menu text — a popup is its
 * own window and would otherwise not inherit the composition's locale; it has no bearing on shape.
 *
 * It carries the same actions as [PackageActionMenu]'s app half (launch, app info, force stop /
 * soft reboot, re-optimize), because this screen's subject is the target. Module-only rows
 * (uninstall, store update) are deliberately absent. The soft-reboot confirmation stays a
 * [SharedAlertDialog] because it must outlive the menu it was reached from.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PackageActionMenuItems(
    expanded: Boolean,
    onDismiss: () -> Unit,
    packageName: String,
    userId: Int,
    appName: String,
    applicationInfo: ApplicationInfo,
    isModule: Boolean,
    onResult: (PackageActionResult) -> Unit,
    onOpenStore: ((String) -> Unit)? = null,
) {
    val isSystemFramework = packageName == SYSTEM_FRAMEWORK_PACKAGE

    // Asked once, when the menu opens. Most modules have neither a companion nor a launcher entry,
    // and a row that exists only to report it has nothing to do is worse than no row.
    var openable by remember(packageName, userId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(packageName, userId) {
        openable =
            ServiceLocator.daemon
                .findAppUi(packageName, userId, companionFirst = isModule)
                .onFailure { e ->
                    logW("actions: launch target lookup for $packageName u$userId failed", e)
                }
                .getOrNull() != null
    }
    var confirmSoftReboot by remember { mutableStateOf(false) }

    // Every action dismisses the menu before it works (see the `finish` note in PackageActionMenu
    // on why the scope must be the process-wide one). The soft-reboot confirmation is the exception:
    // it keeps the menu up to confirm first.
    val scope = ServiceLocator.appScope
    val daemon = ServiceLocator.daemon

    fun finish(block: suspend () -> PackageActionResult) {
        onDismiss()
        scope.launch(Dispatchers.Main) { onResult(block()) }
    }

    fun openAppInfo() {
        finish {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = daemon.startActivityAsUser(intent, userId, noUserSwitch = false)
            val code = started.getOrDefault(-1)
            if (code !in 0..99) {
                logE(
                    "actions: opening app info for $packageName as user $userId failed (code $code)",
                    started.exceptionOrNull(),
                )
            }
            PackageActionResult(R.string.action_opened_info)
        }
    }

    // The actions vary by the row: the framework is not an app (no launch/app info) and gets a soft
    // reboot instead of force stop; an app gets launch/app info/force stop/optimize. Build them as
    // a list so the DropdownMenuGroup can lay each item out with its Expressive capsule shape.
    val items =
        buildList {
            if (!isSystemFramework && openable == true) {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_launch)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Open_in_new, contentDescription = null) },
                        onClick = {
                            finish {
                                val result = daemon.openAppUi(packageName, userId, companionFirst = isModule)
                                if (result.getOrDefault(false)) {
                                    PackageActionResult(R.string.action_launched)
                                } else {
                                    logE(
                                        "actions: open of $packageName for user $userId did nothing",
                                        result.exceptionOrNull(),
                                    )
                                    PackageActionResult(
                                        R.string.action_no_launcher,
                                        tone = SnackbarTone.Failure,
                                    )
                                }
                            }
                        },
                    )
                )
            }

            if (!isSystemFramework) {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_app_info)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Info, contentDescription = null) },
                        onClick = { openAppInfo() },
                    )
                )
            }

            if (isSystemFramework) {
                add(
                    MenuAction(
                        label = {
                            Text(
                                stringResource(R.string.action_soft_reboot),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Restart_alt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { confirmSoftReboot = true },
                    )
                )
            } else {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_force_stop)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Stop, contentDescription = null) },
                        onClick = {
                            finish {
                                val result =
                                    daemon.forceStopPackage(packageName, userId).onFailure { e ->
                                        logE("actions: force stop of $packageName failed", e)
                                    }
                                val ok = result.isSuccess
                                PackageActionResult(
                                    if (ok) R.string.action_force_stopped
                                    else R.string.action_force_stop_failed,
                                    appName,
                                    tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                                )
                            }
                        },
                    )
                )
            }

            if (!isModule && !isSystemFramework) {
                add(
                    MenuAction(
                        label = {
                            // The icon is tinted primary; the label must match so the row reads as
                            // one control rather than a black word beside a blue glyph.
                            Text(
                                stringResource(R.string.action_optimize),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            finish {
                                onResult(
                                    PackageActionResult(
                                        R.string.action_optimizing,
                                        appName,
                                        tone = SnackbarTone.Working,
                                    )
                                )
                                val ok =
                                    daemon
                                        .optimizePackage(packageName)
                                        .onFailure { e ->
                                            logE("actions: re-optimize of $packageName failed", e)
                                        }
                                        .getOrDefault(false)
                                PackageActionResult(
                                    if (ok) R.string.action_optimized
                                    else R.string.action_optimize_failed,
                                    appName,
                                    tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                                )
                            }
                        },
                    )
                )
            }
        }

    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        LocalizedOverlay {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                items.forEachIndexed { index, action ->
                    DropdownMenuItem(
                        selected = action.selected,
                        onClick = action.onClick,
                        text = action.label,
                        shapes = MenuDefaults.itemShape(index, items.size),
                        leadingIcon = action.leadingIcon,
                        // The KSU-style state mark: a check in the trailing slot when this item is
                        // the active one, nothing otherwise. The selectable colors already tint the
                        // selected item; the check makes the choice explicit.
                        trailingContent = {
                            if (action.selected) {
                                Icon(
                                    MaterialSymbols.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmSoftReboot) {
        SharedAlertDialog(
            onDismissRequest = { confirmSoftReboot = false },
            icon = { Icon(MaterialSymbols.Outlined.Restart_alt, contentDescription = null) },
            title = { Text(stringResource(R.string.action_soft_reboot)) },
            text = { Text(stringResource(R.string.action_soft_reboot_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSoftReboot = false
                        onDismiss()
                        scope.launch(Dispatchers.Main) {
                            daemon.softReboot().onFailure { logE("actions: soft reboot failed", it) }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_soft_reboot),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSoftReboot = false }) {
                    Text(stringResource(UiR.string.store_cancel))
                }
            },
        )
    }
}

/** One entry in the menu: its label + leading icon + the click handler, plus an optional */
/** selected state so a grouped item can draw the KSU-style checkmark for a toggle or a selection. */
private class MenuAction(
    val label: @Composable () -> Unit,
    val leadingIcon: @Composable (() -> Unit)?,
    val onClick: () -> Unit,
    val selected: Boolean = false,
)

/**
 * The long-press menu for a *module* (opened from the Modules list), the module twin of
 * [PackageActionMenuItems].
 *
 * Same grouped Material 3 Expressive `DropdownMenuPopup` + `DropdownMenuGroup` shape as the scope
 * menu, so a long press on a module opens a capsule menu beside the row rather than a bottom sheet.
 * A module is not an app you "open", so instead of launch it gets its companion (the screen its
 * author wrote to configure it), plus the module-only rows: open in store, and the "ignore updates"
 * toggle drawn as the KSU-style checkmark (checked = muted).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModuleActionMenuItems(
    expanded: Boolean,
    onDismiss: () -> Unit,
    packageName: String,
    userId: Int,
    appName: String,
    applicationInfo: ApplicationInfo,
    onResult: (PackageActionResult) -> Unit,
    onOpenStore: ((String) -> Unit)? = null,
) {
    val isSystemFramework = packageName == SYSTEM_FRAMEWORK_PACKAGE

    // Asked once, when the menu opens. A module may have no companion and no store page; the rows
    // are only offered when there is somewhere to go, so a menu that only reports a dead end is
    // not left on screen.
    var openable by remember(packageName, userId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(packageName, userId) {
        openable =
            ServiceLocator.daemon
                .findAppUi(packageName, userId, companionFirst = true)
                .onFailure { e ->
                    logW("actions: module companion lookup for $packageName u$userId failed", e)
                }
                .getOrNull() != null
    }
    var confirmSoftReboot by remember { mutableStateOf(false) }

    val muted by ServiceLocator.settings.mutedUpdates.collectAsStateWithLifecycle()

    val scope = ServiceLocator.appScope
    val daemon = ServiceLocator.daemon

    fun finish(block: suspend () -> PackageActionResult) {
        onDismiss()
        scope.launch(Dispatchers.Main) { onResult(block()) }
    }

    fun openAppInfo() {
        finish {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = daemon.startActivityAsUser(intent, userId, noUserSwitch = false)
            val code = started.getOrDefault(-1)
            if (code !in 0..99) {
                logE(
                    "actions: opening app info for $packageName as user $userId failed (code $code)",
                    started.exceptionOrNull(),
                )
            }
            PackageActionResult(R.string.action_opened_info)
        }
    }

    val items =
        buildList {
            if (!isSystemFramework && openable == true) {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_open_companion)) },
                        leadingIcon = {
                            Icon(MaterialSymbols.Outlined.Open_in_new, contentDescription = null)
                        },
                        onClick = {
                            finish {
                                val result =
                                    daemon.openAppUi(packageName, userId, companionFirst = true)
                                if (result.getOrDefault(false)) {
                                    PackageActionResult(R.string.action_launched)
                                } else {
                                    logE(
                                        "actions: open of $packageName for user $userId did nothing",
                                        result.exceptionOrNull(),
                                    )
                                    PackageActionResult(
                                        R.string.action_no_launcher,
                                        tone = SnackbarTone.Failure,
                                    )
                                }
                            }
                        },
                    )
                )
            }

            if (onOpenStore != null) {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_open_store)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Store, contentDescription = null) },
                        onClick = {
                            onDismiss()
                            onOpenStore(packageName)
                        },
                    )
                )
            }

            add(
                MenuAction(
                    label = { Text(stringResource(UiR.string.store_mute_updates)) },
                    leadingIcon = {
                        Icon(MaterialSymbols.Outlined.Notifications_off, contentDescription = null)
                    },
                    onClick = {
                        ServiceLocator.settings.setUpdatesMuted(packageName, packageName !in muted)
                        onDismiss()
                    },
                    selected = packageName in muted,
                )
            )

            if (!isSystemFramework) {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_app_info)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Info, contentDescription = null) },
                        onClick = { openAppInfo() },
                    )
                )
            }

            if (isSystemFramework) {
                add(
                    MenuAction(
                        label = {
                            Text(
                                stringResource(R.string.action_soft_reboot),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                MaterialSymbols.Outlined.Restart_alt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { confirmSoftReboot = true },
                    )
                )
            } else {
                add(
                    MenuAction(
                        label = { Text(stringResource(R.string.action_force_stop)) },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Stop, contentDescription = null) },
                        onClick = {
                            finish {
                                val result =
                                    daemon.forceStopPackage(packageName, userId).onFailure { e ->
                                        logE("actions: force stop of $packageName failed", e)
                                    }
                                val ok = result.isSuccess
                                PackageActionResult(
                                    if (ok) R.string.action_force_stopped
                                    else R.string.action_force_stop_failed,
                                    appName,
                                    tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                                )
                            }
                        },
                    )
                )
            }

            add(
                MenuAction(
                    label = {
                        Text(
                            stringResource(R.string.action_uninstall),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            MaterialSymbols.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        finish {
                            val result = daemon.uninstallPackage(packageName, userId)
                            val ok = result.getOrDefault(false)
                            if (!ok) {
                                logE(
                                    "actions: uninstall of $packageName for user $userId failed",
                                    result.exceptionOrNull(),
                                )
                            }
                            PackageActionResult(
                                if (ok) R.string.action_uninstalled
                                else R.string.action_uninstall_failed,
                                appName,
                                tone = if (ok) SnackbarTone.Success else SnackbarTone.Failure,
                            )
                        }
                    },
                )
            )
        }

    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        LocalizedOverlay {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                items.forEachIndexed { index, action ->
                    DropdownMenuItem(
                        selected = action.selected,
                        onClick = action.onClick,
                        text = action.label,
                        shapes = MenuDefaults.itemShape(index, items.size),
                        leadingIcon = action.leadingIcon,
                        trailingContent = {
                            if (action.selected) {
                                Icon(
                                    MaterialSymbols.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmSoftReboot) {
        SharedAlertDialog(
            onDismissRequest = { confirmSoftReboot = false },
            icon = { Icon(MaterialSymbols.Outlined.Restart_alt, contentDescription = null) },
            title = { Text(stringResource(R.string.action_soft_reboot)) },
            text = { Text(stringResource(R.string.action_soft_reboot_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSoftReboot = false
                        onDismiss()
                        scope.launch(Dispatchers.Main) {
                            daemon.softReboot().onFailure { logE("actions: soft reboot failed", it) }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.action_soft_reboot),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSoftReboot = false }) {
                    Text(stringResource(UiR.string.store_cancel))
                }
            },
        )
    }
}
