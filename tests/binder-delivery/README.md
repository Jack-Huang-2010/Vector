# Module binder delivery harness

A module whose only purpose is to be handed an `IXposedService` and say so.

The daemon delivers a module app's service by forging a `ContentProvider` call: it asks
`IActivityManager.getContentProviderExternal` for the module's `<pkg>.XposedService` authority —
which starts the process and blocks until the provider is published — and then calls it with
method `SendBinder` and the binder in the extras. Nothing else in the tree exercises that path, and
none of the modules people actually install can stand in for it: a module only receives a service
if it ships libxposed-service, and most do not. This one does the minimum required to be a valid
counterparty, so the framework side can be observed against something whose behaviour is known.

## What is in it

| File | Why |
|---|---|
| `META-INF/xposed/module.prop` | `minApiVersion=100`, so the daemon treats it as a modern module and delivers to it at all. Legacy modules are never sent a binder. |
| `META-INF/xposed/java_init.list` | Names `Entry`, which is never loaded — the module is given no scope, so it is injected nowhere. It exists so the APK parses as a module. |
| `io.github.libxposed.service.XposedProvider` | The module half of the handshake, reimplemented rather than depended upon. It logs the binder it was given and whether it is alive, and returns a non-empty reply, which is what the daemon reads as success. |
| `KeepAliveProvider` (`:keep`) | A second process, so the app's uid can outlive the process holding the binder. |
| `KeepAliveService` (`:keep`) | The same, for platforms where starting a service is easier than calling a provider. Background service starts are refused from the shell on recent releases, so the provider is usually the one to use. |

Deliberately absent: an `Activity`. A process with no component at all makes the framework's
contribution to its priority unambiguous — anything holding it above `cch-empty` is the framework's
doing.

## Building and installing

```sh
./gradlew -p tests/binder-delivery assembleDebug
adb install -r tests/binder-delivery/build/outputs/apk/debug/binder-delivery-debug.apk
adb shell su -c '/data/adb/lspd/cli modules enable org.matrix.bindertest'
```

## What it is for

**Is the service delivered at all?** Start the process through its provider and read both sides.

```sh
adb shell su -c 'content call --uri content://org.matrix.bindertest.XposedService --method ping'
adb logcat -d -s BinderTestModule            # received framework binder: … alive=true
adb shell su -c 'grep "module binder" $(ls -t /data/adb/lspd/log/verbose_*.log | head -1)'
```

The module's line should precede the daemon's: `onBinderReceived` completes before `call` returns,
which is what makes it safe for the daemon to drop its provider reference immediately afterwards.

**Is the framework pinning the process?** With no components of its own, a delivered-to module app
should be reclaimable:

```sh
adb shell dumpsys activity processes | grep 'Proc #.*bindertest'
# want: cch+ … CEM … (cch-empty)
# a line reading (ext-provider) at adj 0 means an external provider reference was left outstanding
```

**Does a restarted process get a new binder?** The delivery record has to be per process, not per
uid — a uid outlives any one of its processes. Hold the uid open with `:keep`, kill the process
that holds the binder, and check that the next one is served:

```sh
adb shell su -c 'content call --uri content://org.matrix.bindertest.KeepAlive --method ping'
adb shell su -c 'content call --uri content://org.matrix.bindertest.XposedService --method ping'
adb shell su -c "kill -9 $(adb shell pidof org.matrix.bindertest)"
adb logcat -d -b events | grep am_uid_stopped      # must be silent: the uid is still alive
adb shell su -c 'content call --uri content://org.matrix.bindertest.XposedService --method ping'
# the daemon log should show a second "Sent module binder"
```

**Does a failing delivery back off?** Disabling the provider makes `getContentProviderExternal`
return nothing, without needing an app that crashes:

```sh
adb shell su -c 'pm disable org.matrix.bindertest/io.github.libxposed.service.XposedProvider'
# drive the uid repeatedly; count "No service provider" lines and watch for the ceiling warning
adb shell su -c 'pm enable org.matrix.bindertest/io.github.libxposed.service.XposedProvider'
```

## Caveats

`org.matrix.bindertest` is a real module as far as the daemon is concerned. Give it no scope unless
you mean to: `Entry` implements nothing and would fail to load in a target.

The `XposedProvider` here is not libxposed's. It implements the handshake and nothing else — no
`XposedServiceHelper`, no listener registration, no remote preferences — so it says nothing about
whether the client library works, only about whether the framework's half does.
