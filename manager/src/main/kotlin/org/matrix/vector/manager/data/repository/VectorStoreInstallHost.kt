package org.matrix.vector.manager.data.repository

import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.store.InstallStep
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreInstallHost

/**
 * Vector's implementation of the shared Details screen's install capability: the module installer,
 * the post-install bookkeeping, and the device APK inspection that used to live in the details view
 * model. Created per opened module (it needs the package name). LSPatch has no installer and passes
 * null instead, collapsing the shared screen to open-in-browser links.
 */
class VectorStoreInstallHost(private val packageName: String) : StoreInstallHost {

    private val installer = ServiceLocator.installer

    /**
     * This module's install progress — not the installer's.
     *
     * [ModuleInstaller] is a singleton, so its [installer.state] is shared across every opened
     * Details page. Left as-is, a failure (or a download) for one module would paint over another
     * module's resting Install button: install *A*, watch it fail, then open *B* and B would claim
     * A's failure. The installer can only run one install at a time, but the result it holds still
     * names the module it happened to, and each page must only surface its own.
     *
     * Every non-[InstallStep.Idle] state carries its [InstallStep.packageName], so this is a
     * filter, not a re-derivation: keep the step when it belongs to this host, and treat anything
     * naming another module as idle. Done-and-forgotten for this module (acknowledged) is also
     * [InstallStep.Idle].
     */
    override val installState: StateFlow<InstallStep> =
        installer.state
            .map { step ->
                val owner = step.ownerPackage()
                // Idle (owner null) or a step that belongs to this host stays; anything naming
                // another module is silently idle for this page.
                if (owner == null || owner == packageName) step else InstallStep.Idle
            }
            .stateIn(
                ServiceLocator.appScope,
                SharingStarted.Eagerly,
                InstallStep.Idle,
            )

    override val silentInstall: Boolean
        get() =
            ServiceLocator.context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) ==
                PackageManager.PERMISSION_GRANTED

    private val _installedScope = MutableStateFlow<List<String>>(emptyList())
    override val installedScope: StateFlow<List<String>> = _installedScope.asStateFlow()

    private val _installedIsLegacy = MutableStateFlow(false)
    override val installedIsLegacy: StateFlow<Boolean> = _installedIsLegacy.asStateFlow()

    init {
        refreshScope()
    }

    private fun refreshScope() {
        ServiceLocator.appScope.launch {
            runCatching {
                val pm = ServiceLocator.context.packageManager
                val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                val appInfo = info.applicationInfo ?: return@runCatching
                val manifest =
                    ServiceLocator.moduleDetection.inspect(
                        appInfo,
                        pm,
                        info.versionCodeCompat,
                        info.lastUpdateTime,
                    )
                _installedScope.value = manifest.scope
                _installedIsLegacy.value = manifest.isLegacy
            }
        }
    }

    override fun install(asset: ReleaseAsset, releaseVersion: RepoVersion?) {
        ServiceLocator.appScope.launch {
            installer.install(packageName, asset)
            // Record what was installed so a satisfied update stops being offered.
            val installed = ServiceLocator.store.readInstalled()[packageName]
            if (releaseVersion != null && installed != null) {
                ServiceLocator.settings.noteStoreInstall(
                    packageName,
                    StoreInstall(releaseVersion, installed),
                )
            }
            refreshScope()
        }
    }

    override fun acknowledge() = installer.acknowledge()
}

/**
 * Which module an install step belongs to.
 *
 * `InstallStep.packageName` is declared on each concrete state rather than on the sealed
 * [InstallStep] interface, so a single property access needs a `when`. Returning null for
 * [InstallStep.Idle] keeps the filter in [VectorStoreInstallHost.installState] simple: the empty
 * step has no owner, so it is shown as-is, and any step naming a different package is silently
 * dropped instead of painting over the page's resting Install button.
 */
private fun InstallStep.ownerPackage(): String? =
    when (this) {
        is InstallStep.Idle -> null
        is InstallStep.Downloading -> packageName
        is InstallStep.Installing -> packageName
        is InstallStep.Confirming -> packageName
        is InstallStep.Done -> packageName
        is InstallStep.Failed -> packageName
    }
