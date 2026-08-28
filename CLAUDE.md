# CLAUDE.md

Guidance for Claude Code working in the **Vector Framework** repository — a Zygisk module that
provides an ART hooking framework with Xposed API compatibility (formerly LSPosed, JingMatrix fork).

## What this project is

Vector is a root-level Android framework that lets "modules" hook and modify apps and the system in
memory, without touching APK files. It is a **Zygisk module**: it loads inside the Zygote process and
injects a framework into every forked app process that has an enabled module in scope.

- ART hooking engine: [LSPlant](https://github.com/JingMatrix/LSPlant) (submodule `external/lsplant`)
- Native inline hooking: [Dobby](https://github.com/JingMatrix/Dobby); PLT hooking: [LSPlt](https://github.com/JingMatrix/LSPlt)
- Two module APIs are supported:
  - **Modern** [libxposed API](https://github.com/libxposed/api) — implemented in `:xposed` (two submodules: `xposed/libxposed` = module API, `services/libxposed` = service API)
  - **Legacy** `de.robv.android.xposed` API — implemented in `:legacy`
- Supports Android 8.1 → 17 Beta. Requires Magisk/KernelSU with Zygisk (e.g. NeoZygisk).
- License: GPL-3.0.

## Repository layout (Gradle modules)

`settings.gradle.kts` is the source of truth for the module list.

| Module | Role |
| :--- | :--- |
| `:zygisk` | The framework zip + injection engine. Native C++ (`src/main/cpp/module.cpp`, `ipc_bridge.cpp`) + Kotlin loader (`org.matrix.vector.core.Main`). Produces `zygisk/release/Vector-v*-{Release,Debug}.zip`. |
| `:daemon` | Root-privileged, standalone `app_process` daemon (started by `zygisk/module/service.sh`). Central coordinator: SQLite state, IPC asset server, dex2oat hijack, logcat monitor, CLI. |
| `:xposed` | Modern libxposed API implementation: hook engine bridge (`VectorHookBuilder`, `VectorNativeHooker`, `VectorChain`), lifecycle interception, in-memory module loading. |
| `:legacy` | Legacy `de.robv.android.xposed` API surface (`XposedBridge`, `XposedHelpers`, `XC_MethodHook`, `XResources`, `XSharedPreferences`), routing execution to the modern engine via `LegacyFrameworkDelegate`. |
| `:native` | Static C++ library (`libnative.a`): `core` (Context/ConfigBridge/native_api), `elf` (symbol resolution incl. `.gnu_debugdata`), `jni` (HookBridge, ResourcesHook, NativeApiBridge). |
| `:dex2oat` | `dex2oat` wrapper + `liboat_hook.so` (LSPlt) that force-disable method inlining system-wide and spoof OAT metadata. |
| `:hiddenapi` | `:hiddenapi:stubs` (compile-time hidden-API stubs) + `:hiddenapi:bridge` (`HiddenApiBridge`, runtime reflection bypass). |
| `:manager` | Compose manager app; runs **parasitically** inside host `com.android.shell` (uid 2000). No privilege of its own — all device changes go through the daemon via Binder. |
| `:manager-ui` | Shared Compose UI library consumed by the manager. |
| `:services` | Pure AIDL/library modules: `:services:manager-service` (`IManagerService`) and `:services:daemon-service` (`IVectorDaemon`, `IFrameworkService`, `IModuleService`, `IProcessChannel`, …). |
| `:external` | Pinned git submodules: `lsplant`, `dobby`, `fmt`, `xz-embedded`, `lsplt`, `apache/commons-lang`, `axml/manifest-editor`. |

Each module has a detailed `README.md` — read the relevant one before touching that module.

## How the framework actually works (read this first)

### Process flow

1. **Zygisk load**: `VectorModule` (C++, `zygisk/src/main/cpp/module.cpp`) extends both
   `zygisk::ModuleBase` and `native::core::Context`. It filters target processes by UID
   (isolated/app-zygote/SHARED_RELRO are skipped), then fetches the framework DEX + class
   obfuscation map from the daemon and loads it in-memory (`InMemoryDexClassLoader` via
   `SharedMemory` FD — **nothing is written to /data**).
2. **Java bootstrap**: `Main.forkCommon` (Kotlin) → `Startup.initXposed` (system server) or
   `Startup.bootstrapXposed` (apps), which installs the hookers (LoadedApk, DexTrust, crash dump,
   system server process hooks).
3. **IPC without ServiceManager**: nothing registers with `ServiceManager`. Instead,
   `ipc_bridge.cpp` uses ART's `SetTableOverride` to replace `CallBooleanMethodV`, intercepting
   every `Binder.execTransact`; transactions carrying the `_VEC` code are diverted to Kotlin
   `BridgeService.execTransact`. The daemon's primary `IVectorDaemon` binder is pushed into
   `system_server` via a raw `ACTION_SEND_BINDER` transaction to the `activity` service.
   Apps rendezvous by querying the `activity` service; the daemon approves them against scope state
   and hands back an `IFrameworkService` binder. Heartbeat `BBinder` + `DeathRecipient` cleans
   up dead processes.
4. **Modules load in-memory**: `VectorModuleClassLoader` (attached to the framework's loader
   branch only, invisible to `ClassLoader.getParent()` walking) + `VectorURLStreamHandler`
   (bypasses the global JarFile cache). Entry points come from the APK's `assets/xposed_init`
   (Java) and `assets/native_init` (native libraries).
5. **Parasitic manager**: the manager APK is injected into `com.android.shell`. Its manifest is
   never registered — no ContentProvider/FileProvider/startup — so everything initializes
   explicitly from the activity. The daemon intercepts system intents (`ACTION_VIEW` etc.) in
   system_server and redirects them to the manager.

### Key invariants to not break

- **No disk footprint in target processes** — DEX/APKs are loaded from memory; do not introduce
  file writes or installed-package assumptions into injected code.
- **Class-name obfuscation on every boot** — `daemon/.../ObfuscationManager.kt` randomizes
  framework class names; native fetches the map over IPC (`kObfuscationMapTransactionCode`).
  Never hardcode a framework class name across the native/Java boundary; route through the map.
- **Manager handshake**: framework loads `<manager>.Constants` by reflection and calls
  `setBinder(IBinder)`. R8 keeps it via `manager/proguard-rules.pro`; renaming breaks the
  handshake with **no compile error anywhere**. Order is not fixed — `ServiceLocator.attach()`
  is idempotent, `bind()` is a plain `StateFlow` assignment.
- **Scope table semantics** (`daemon`): the `scope` table (PK: mid, app_pkg_name, user_id) is the
  configuration; `ConfigCache` resolves it into (process name, uid) keys for the injection path.
  Scope rows outlive their target apps and are deleted by exactly four paths — treat any new
  deletion path as a design change. Package events decide by name, not uid (a reinstalled target
  arrives under an unseen uid).
- **Binder defaults lie**: a proxy returns defaults (0/null/empty) for unimplemented transactions,
  and several daemon calls return a `boolean` refusal that callers must check — dropping it turns
  a refusal into a silent success. Read the AIDL doc comments before calling.

## Build & development

### Prerequisites

- JDK 21, Android SDK, `ninja` (CI removes Android's own cmake), `ccache` recommended.
- **Submodules are pinned but not checked out in a fresh clone** — run
  `git submodule update --init --recursive` before building. Builds compile `external/*`
  directly; the libxposed submodules are compiled into `:services:*` and `:xposed`.

### This machine (local dev environment)

Development happens against the real device via Android Studio; **the immediate work focus is the
manager UI** (`:manager` + `:manager-ui`).

- SDK: `/Volumes/SSD/Files/AS` (already set in `local.properties` → `sdk.dir`).
- **Daemon JVM must be JDK 25**: the untracked, auto-generated `gradle/gradle-daemon-jvm.properties`
  (toolchainVersion=25) makes Gradle try to auto-provision JDK 25 via foojay, which fails offline.
  Android Studio builds fine (it runs its own daemon JVM); a terminal `gradlew` needs `JAVA_HOME`
  pointing at the Zulu 25 JDK. JDK 21 at `jdk-21.0.11.jdk` is fine for the *project* toolchain but
  does not satisfy the daemon-JVM 25 requirement, so it must not be the `JAVA_HOME` for a terminal
  build until that properties file is removed.
  - Zulu 25 full path (verified working for a terminal build):
    `/Volumes/SSD/Games/Minecraft/Java/zulu25.28.85-ca-jdk25.0.0-macosx_aarch64/zulu-25.jdk/Contents/Home`.
- NDK 29.0.14206865: installed at `/Volumes/SSD/Files/AS/ndk/` — matches
  `androidCompileNdkVersion` exactly, no SDK-manager download needed for it.
- cmake 4.4.2 via Homebrew (`/opt/homebrew/bin/cmake`) satisfies the required `3.29.8+`.
- Known gaps to expect on the first build:
  - `build-tools;37.0.0` is required (`androidBuildToolsVersion`) but only 34/35/36 are
    installed — let Android Studio finish downloading it.
  - Platform `android-37` is required (compileSdk 37) but only `android-35` and
    `android-37.0` are present; verify AGP accepts the installed one or install `android-37`.
  - `ninja` is not installed anywhere (CI installs its own) — install it (e.g.
    `brew install ninja`) or the native (C++) parts of the build will fail.
  - `android-sdk-license` is accepted already.

### Commands

```sh
./gradlew zipAll                    # the whole framework: release + debug zips → zygisk/release/
./gradlew :zygisk:zipDebug          # just the debug zip (recommended for day-to-day)
./gradlew :zygisk:zipRelease
./gradlew :manager:assembleDebug    # the manager APK alone
./gradlew ktfmtFormat               # formatting is ktfmt; CI does NOT check it
./gradlew zipAll --offline          # if you want to avoid re-resolving deps
```

Convenience device tasks also exist per variant in `:zygisk` (see `zygisk/build.gradle.kts`:
`push…Module…`, `install…`, `install…AndReboot`).

### Versioning & the build stamp

- Version code = `git rev-list --count refs/remotes/origin/master`; version name = latest `v*` tag.
  A branch build and a master build can share a version code.
- Every build carries a stamp — `commit` first, then where it came from:
  CI `93d66473-JingMatrix-Vector`; local `93d66473`; local dirty tree `93d66473+hostname`.
  Do not change the shape; both the manager's "am I running this build" check and the status page
  parse it. Keep `BuildConfig.VERSION_NAME` clean.
- The daemon's `InstallerVerifier` rejects unsigned installs — CI signing uses the
  `KEY_STORE*`/secrets; forks build unsigned and publish nothing.

### Testing

**There are no test source sets anywhere. CI runs `zipAll` (plus a translations sanity check)
and nothing else.** A green tick means it compiles and packages. Everything else is verified
on-device against a real daemon. Debug zips have far more logging — always reproduce bugs against
the latest debug build before filing an issue.

## Conventions

### Manager UI pitfalls (nav3 + the shared Details screen)

The manager sits on **Navigation 3** (`NavDisplay`), not the NavHost of the WeKit project. Several
behaviors are subtle enough that they have already cost real bugs; keep these in mind before editing
`VectorApp.kt`, `Navigator.kt`, or the store screens.

- **One `NavDisplay` owns the whole stack; the root is a fixed `TopLevel` container, not a panel.**
  `NavDisplay` is composed unconditionally (no `if (atRoot) pager else NavDisplay` swap). The stack is
  `[TopLevel, detail, ...]`: `TopLevel` renders the finger-following `HorizontalPager` of panels, and a
  detail (scope editor, store detail, browser…) is pushed above it by `Navigator.go`. Because the
  display stays mounted instead of being swapped for a pager branch, a *system* back / mouse right-click
  pops the detail and plays `popTransitionSpec`'s fade-through with the previous page (the pager) as the
  entering scene — the background shows behind the shrinking current one.
  - **The current panel is NOT the stack root.** It is `Navigator.currentTab`, a saveable
    `MutableState<NavKey>` (saved by the panel route's string key), because the stack root is always
    `TopLevel`. `switchTo` only mutates `currentTab` and clears the stack back to `[TopLevel]`; it never
    re-seeds the root with a panel. `go` pushes a detail above `TopLevel`; `back` pops only the top.
  - `currentTopLevel` = `currentTab` (feeds the bar / floating ball highlight), *not* `backStack.first`.
  - `reconcilePanels` fixes `currentTab` (move off a tab that got hidden), not the stack root.
  - **Pitfall (hit once):** there must be a *single* `currentTab` state. A `rememberSaveable` value plus
    a separate `mutableStateOf` inside `Navigator` are two decoupled sources — `switchTo` mutates only
    the latter, so the saved value never updates and the panel you were on is lost across process death
    (the old design got that for free by making the panel the stack root). Delegate `currentTab` to the
    saveable-backed `MutableState`.
  - `rememberNavBackStack` is seeded with `TopLevel` only; a restored stack may already carry a detail
    above it (the hooker's per-activity Bundle cache survives process death). `registerRoutes` registers
    `entry<TopLevel>` (the pager) and the detail entries, and **not** the four `TopLevelRoute` panels —
    the pager draws each panel via `TopLevelPanelContent` directly, so the stack never holds a
    `TopLevelRoute`.
- **`transitionSpec` / `popTransitionSpec` / `predictivePopTransitionSpec` are separate.** Forward keeps
  the official sample's half-width horizontal slide; **both kinds of back** share one AOSP **fade-through**
  via a single `fadeThroughBackTransition()` helper: the outgoing page scales 100%→90% and fades out,
  the incoming page — which starts at 110% — settles to 100% and fades in, with *no* horizontal travel.
  `popTransitionSpec` (system back button + mouse right-click) and `predictivePopTransitionSpec` (gesture)
  both call it, so a back-key press and a back drag land the reader in the same place; only the gesture
  tracks the finger 1:1. Using `EnterTransition.None` (a "pop-in" reveal) or a half-width slide reads as
  page-flipping and was judged wrong on-device.
  - **Predictive-back specs must be `LinearEasing`.** nav3 *seeks* the predictive transition to the
    finger's progress (`SeekableTransitionState.seekTo(progress, ...)`) instead of playing it, so a
    curved easing makes the page lag the hand. This is the same reason WeKitThemeGenerator maps
    `backEvent.progress` straight into a `graphicsLayer` alpha (`1f - progress`).
- **The shared store `installState` is a single flow on a singleton installer.** `ServiceLocator.installer`
  is one `ModuleInstaller`, and `VectorStoreInstallHost.installState` wraps `installer.state`. Every
  opened module's host reads that *same* flow, so a leftover `Failed(A, ...)` paints module B's page.
  Each host must **filter by its own `packageName`** (map any step naming another module to `Idle`).
  Every non-`Idle` `InstallStep` carries its `packageName`; read it through a `when` helper because
  it is declared on the concrete states, not on the sealed `InstallStep` interface.
  - Each host is created per-screen via `remember(route.packageName) { VectorStoreInstallHost(...) }`,
    so the filter is per-module even though the installer is a process-wide singleton.
- **The install failure bar is reachable and must be re-usable.** A failed install still shows a
  centered error + a full-width filled `Button` with a `RestartAlt` icon (not a download icon — that
  would imply re-downloading what just failed). Resting state is the full-width filled `Button` with
  a `Download` icon.
- **Long-press menus are anchored to the pressed row, not a bottom sheet.** `PackageActionMenu`
  (a `ModalBottomSheet`) is the wide/verbose variant, used from the module list and the store. The
  scope screen's long press uses `PackageActionMenuItems`
  (`Manager → ui/components/PackageActionDropdown.kt`), which reproduces WeKit's
  `DropDownMenuWidget` pattern: a Material 3 **`DropdownMenuPopup`** (the Popup primitive below
  `DropdownMenu`) wrapping a **`DropdownMenuGroup(shapes = MenuDefaults.groupShapes())`**, whose
  items use **`MenuDefaults.itemShape(index, size)`** for the Expressive capsule shapes. It opens
  **next to the row that was pressed** because the caller composes it as a sibling of the pressed
  `ListItem`, inside the same `Box`: the popup's position provider anchors to the parent layout node
  it is declared in, so the anchor is the enclosing `Box`. Composing it at the call site keeps it
  bound to this row (a coordinate can't recreate that once a `LazyColumn` reuses rows). Don't swap in
  a plain `DropdownMenu` or a custom `Popup` + `Surface` + `Column` shell — those either lose the
  Expressive grouped item shapes or the official surface/elevation/animation. `LocalizedOverlay`
  wraps only the menu text (a popup is its own window; it otherwise wouldn't inherit the locale).
  - **Menu icons use Material Symbols Outlined**, not the legacy `material-icons` set. The manager
    consumes `icons-material-symbols-outlined-cmp` (Maven `com.composables`, version
    `composablehorizons-symbols`) through the `compose` bundle in `libs.versions.toml`. Reference
    icons as `MaterialSymbols.Outlined.X` (e.g. `Info`, `Open_in_new`, `Restart_alt`, `Stop`,
    `Bolt`). This matches WeKit and is what makes the menu look M3-Expressive rather than the
    heavy, filled look of `Icons.Rounded`.
  - The same grouped `DropdownMenuPopup` + `DropdownMenuGroup` pattern is reused for the modules
    filter/sort menu (`ModulesScreen.kt` → `ModuleFilterButton`) and the checkbox state mark. For a
    single-select group set `selected = option == current` (add a `trailingContent` check icon when
    selected, since the `selected` overload tints the item but does not draw a check itself). For a
    bottom-sheet toggle row (e.g. "ignore updates" in `PackageActionMenu.kt` → `ActionToggleRow`),
    present the state as a trailing `MaterialSymbols.Outlined.Check` when enabled and nothing when
    disabled — keep the screen-reader `Role.Switch` via `Modifier.toggleable`.
  - **The menu group corner radius must be set explicitly to `RoundedCornerShape(16.dp)`.** WeKit
    (material3 `1.5.0-alpha19`) defaults `DropdownMenuGroup`'s container shape to
    `SegmentedMenuTokens.ContainerShape` = `CornerLarge` = 16dp. Vector is pinned on `1.5.0-alpha26`,
    which re-tokens that container to `CornerExtraSmall` (4dp) — so the plain `MenuDefaults.groupShapes()`
    would render a near-square menu. To match WeKit, pass
    `MenuDefaults.groupShapes(shape = RoundedCornerShape(16.dp), inactiveShape = RoundedCornerShape(16.dp))`
    (items keep `MenuDefaults.itemShape(index, size)`). This is the exact WeKit value, not a guess.
  - Long-pressing a module in the **Modules** list (and an app in the scope list) now opens the same
    grouped capsule menu beside the row, via `ModuleActionMenuItems` / `PackageActionMenuItems`
    (`PackageActionDropdown.kt`). The reader anchors both to the pressed row because the caller
    composes them inside a `Box` that also holds the row. The old `PackageActionSheet`
    (`ModalBottomSheet`) is still defined (`PackageActionMenu.kt`) for wide/verbose flows (the store
    page and repo details), but the module list no longer uses it.

### Code style
- Kotlin/Java formatted with **ktfmt** (`./gradlew ktfmtFormat`); 4-space indent.
- The codebase is unusually comment-heavy about **why** (see root `build.gradle.kts` and every
  module README for the house style). Match it: explain the constraint the code is bending around,
  name the bug it prevents, cite the issue/PR number when one exists.
- No R8 on some modules (e.g. `:services:*` are `isMinifyEnabled = false`); the manager, daemon
  and zygisk modules minify — check `proguard-rules.pro` before adding reflection entry points.

### Logging
- Injected/manager code logs under a tag starting with `Vector` (or one of the daemon's filter
  tags: `Magisk`, `KernelSU`, `dex2oat`, `LSPosed`, `Vector`). `daemon/src/main/jni/logcat.cpp`
  routes those into the daemon's verbose stream, which reaches the manager's Logs screen and the
  zip-exported report. A file-local tag lands nowhere.

### Translations (Crowdin)
- Only **English source strings** are edited in-repo:
  `manager/src/main/res/values/strings.xml`, `manager-ui/src/main/res/values/strings_*.xml`,
  `daemon/src/main/res/values/strings.xml` (paths are pinned in `.github/workflows/crowdin.yml`).
  Never hand-edit locale files.
- Changing what a string *means* requires a **new key** — rewording in place silently desyncs the
  18 translations.
- `manager/build.gradle.kts` merges the daemon's res dir, so a string-name collision across the
  two modules is a build error.
- User-visible text must not be hardcoded in composables; identifiers that must not translate carry
  `translatable="false"`.

### Git
- Commit messages: imperative, one descriptive sentence, capitalized, optionally `(#NNN)`
  (see `git log`). Meaningful titles like "Ask the scope table by name whether an install matters".
- Release process: cut a commit titled `Release Vector …`, tag it `v*`, push. The CI tag build
  publishes the stable release; the master-push build for that commit is skipped so the same code
  never also ships as a canary. Canaries are prereleases named `canary-<versionCode>`, only the
  five most recent are kept.

## Where to look when a bug report arrives

- Logs: manager → Logs screen, or `/data/adb/lspd/log/` (rotating 4MB files) on device; the zip
  export attaches them. `logcat` tags: `Vector`, `VectorDaemon`, `LSPosed`.
- Scope/state: daemon DB at `/data/adb/lspd/config/modules_config.db`; CLI socket at
  `/data/adb/lspd/.cli_sock` (JSON over a filesystem socket, auth via compiled-in UUID token,
  implemented in `daemon/.../ipc/CliHandler.kt` + `daemon/.../env/CliSocketServer.kt`).
- Daemon base path `/data/adb/lspd` keeps the historical name — do not "fix" it; migrations and
  user tooling depend on it (there are deliberate legacy `"lspd"` preference/row names too).
