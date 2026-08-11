# libxposed API 102 conformance harness

A two-app harness that checks, from `adb` only, what the framework actually does against what the
API says it must. Every case is written against the vendored spec at
`xposed/libxposed/api/src/main/java/io/github/libxposed/api/XposedInterface.java` and its
`package-info.java`, cross-checked against `java.lang.reflect` semantics for everything the Invoker
is documented against (`@see Method#invoke`). The assertions are the interface's words, not Vector's
current behaviour — a case that encodes a *reading* of the spec rather than a quote from it says so,
and reports under its own status.

The case names come from a third-party conformance app whose source is not public, so that a run of
this harness can be compared row for row with a run of that one. Nothing else about it is relied on:
where it and the interface disagree, the interface wins.

**Read [what a green run means](#what-a-green-run-means) before reading anything into one.** 37
cases; two of them were once green against a completely unmodified hooking engine, which is the
reason that section exists.

It is a standalone Gradle build. It is not part of the root project and must not be added to it.

## Layout

| Module       | Package               | Role |
| ------------ | --------------------- | ---- |
| `target`     | `org.matrix.vxtarget` | The hooked app. Carries the fixtures, a JNI library, and the receiver that runs one case per broadcast in its `:suite` process |
| `module`     | `org.matrix.vxmodule` | The Xposed module. Carries the suite itself and a native module library |
| `legacystub` | —                     | Compile-only stubs of the legacy API. Never packaged |

The two apps share no types. The module publishes its suite object into
`org.matrix.vxtarget.Bridge` from `onPackageLoaded`, and the app calls back into it reflectively.
Nothing on that path is hooked, so the trigger keeps working even while a case is breaking hooking.

## Build

```sh
cd tests/xposed-conformance
./gradlew :target:assembleDebug :module:assembleDebug
```

Needs NDK `29.0.14206865` and build-tools `37.0.0` (the versions the root project pins), plus the
SDK's CMake 3.22.1. Both APKs are signed with the standard `~/.android/debug.keystore`.

Both apps build for `arm64-v8a` and `x86_64` only. `libvxmodhook.so` is loaded into the hooked
app's process, so if the suite ever has to run in a 32-bit process, add the ABI to **both**
`build.gradle.kts` files.

Keep them **debug** builds — with one case's worth of cost, spelled out under
[the debuggable trade](#the-debuggable-trade). A debuggable app runs with CheckJNI on, which is what
turns the unchecked JNI preconditions in `invokeSpecial` into a clean abort instead of silent memory
corruption — the reported "test service process crashed" is that abort, and a release build would
hide it.

## Install, enable, scope

```sh
adb install -r target/build/outputs/apk/debug/target-debug.apk
adb install -r module/build/outputs/apk/debug/module-debug.apk

V=/data/adb/modules/zygisk_vector/cli
adb shell "su -c '$V modules enable org.matrix.vxmodule'"
adb shell "su -c '$V scope set org.matrix.vxmodule org.matrix.vxtarget/0'"
```

`run.sh --enable` does the last two for you. Scope is declared in `META-INF/xposed/scope.list`,
which is where the manager reads it from for a module targeting 101 or later — the legacy
`xposedscope` meta-data is only read on the legacy branch, so this module does not carry it.

Launch both apps once after installing. A freshly installed package is in the stopped state and
receives no broadcast until something starts it, which otherwise reads as a framework failure;
`run.sh` does this too.

## Run

```sh
./run.sh                     # install, run everything, print the table
./run.sh --build             # build first
./run.sh --no-install        # use whatever is on the device
./run.sh --only invoker      # only the cases whose id or name contains "invoker"
./run.sh --full              # do not truncate the failure detail
./run.sh -s <serial>         # pick a device
```

```
STATUS  CASE                                           DETAIL
------- ---------------------------------------------- ------
PASS    proxy generated method
PASS    class initializer
FAIL    dynamically registered native method           registered before the hook: expected jav...
CRASH   invokeSpecial non-virtual dispatch             the process died after: STEP special-dis...
READING handle replacement                             a reading of "this hook handle is no lon...
SETUP   Native API/function hook                       the app could not load its own JNI libra...

37 cases: 20 passed, 12 failed, 1 crashed, 1 reading, 3 not run
full detail in run-results.txt
```

`run-results.txt` holds the same rows, tab separated and untruncated, which is what to diff between
two builds of the framework.

### What a status means

There are more of them than PASS and FAIL because "the framework did the wrong thing", "the spec
does not say what we assumed" and "the harness could not set the case up" are three different
answers, and only the first is a defect.

| Status | Meaning |
| ------ | ------- |
| `PASS` | The case asserted what the spec says and got it |
| `FAIL` | An assertion failed. The detail is the whole story: what was expected, what came back, and the frame it came from when the exception was a surprise rather than an assertion |
| `READING` | An assertion failed that is *our reading* of the spec rather than its words — see [where the assertions are inferred](#where-the-assertions-are-inferred-rather-than-quoted). A row here is a question about the interface, not a defect, and it does not fail the run. It does end its own case, so the checks below it in the same case did not run |
| `SETUP` | The case never ran: a missing fixture, a JNI library the app would not load, a broadcast that never arrived, **or a device on which the case could not have failed**. It says nothing about the framework either way, and it *does* fail the run, because an inconclusive row must not read as a clean one |
| `SKIP` | The module was never loaded into the process. Same as `SETUP`: enable it and scope it |
| `TIMEOUT` | The case was still running after 20s inside the app and was abandoned |
| `CRASH` | The process died without leaving a result. The detail carries the last `STEP` breadcrumb and the abort line from the log |
| `HUNG` | No result after `--timeout` seconds, and the process is still there |

### How a run survives a crash

Every case is one broadcast to a receiver in the target's `:suite` process, and the driver
force-stops the app before each one. So:

* a case that aborts the runtime loses **its own** result and nothing else. The driver notices the
  process disappearing rather than waiting out the timeout, records `CRASH` with the last
  breadcrumb and the abort line, force-stops what is left of the app, and carries on; the next
  broadcast starts a fresh process, and the module is loaded into it again from scratch;
* no case can inherit a hook, a resolved JNI binding or an initialized class from another one,
  which matters more than it sounds: unhooking does not remove a trampoline, so a leftover hook
  changes what the next case measures. `--keep-process` turns the restart off if you want speed and
  are willing to lose that — a crashed case still restarts the process, because there is nothing
  left to keep.

  `resolved Java native method` and `unresolved Java native method` are two cases rather than one
  because a fixture can only be unresolved once, not because of the restart: they are two distinct
  methods (`Natives.resolvedNative` / `Natives.unresolvedNative`) and neither case touches the
  other's. No case spends either of them any more — `dynamically registered native method` used to,
  and under `--keep-process` that quietly made `unresolved Java native method` vacuous whenever the
  order put it second.

A case that hangs is abandoned after 20 seconds inside the app and reported as `TIMEOUT`; the
driver gives up on its own after `--timeout` seconds and reports `HUNG`.

Results travel through a file (`/data/data/org.matrix.vxtarget/files/results/<id>`) rather than
through the log, because a device with a global `log.tag=E` drops every INFO line and would make a
healthy run look silent. The log carries the same lines plus a `STEP` breadcrumb before each check
that can take the runtime down, and the driver prints the last breadcrumb for a crashed case. If
the breadcrumbs are missing on a `CRASH` row, check `adb shell getprop log.tag`.

Read the raw channels by hand with:

```sh
adb logcat -d -s 'VXConf:*'
adb shell "su -c 'ls /data/data/org.matrix.vxtarget/files/results'"
```

## The cases

37 of the report's 46. First the 14 failing rows this harness reproduces:

| Case | What it asserts |
| ---- | --------------- |
| `dynamically registered native method` | A method bound by `RegisterNatives` still reaches its implementation once hooked — registered before the hook (from `JNI_OnLoad`) and after it, reached through the chain and through the `Type.ORIGIN` backup, re-registered over a hooked binding **to a second implementation**, and still bound after `unhook`. It has no `Java_` symbol, so a lost binding cannot re-resolve itself and surfaces as `UnsatisfiedLinkError`; `twinNative` beside it carries both a symbol and a registration to a *different* string, so the same lost binding reads there as the wrong answer instead |
| `framework intrinsic method` | A hook on `Integer.reverse` fires, and the answer matches one computed without `Integer.reverse` at all, including from a caller driven hot enough to have been compiled — which is where an ART intrinsic gets substituted past the hook. Only runs where substitution is possible at all; see [the debuggable trade](#the-debuggable-trade) |
| `Invoker bypasses method access checks` | "Invocations through invokers will bypass access checks" for a private method, a package-private method, and a public method of a non-public class — each next to the plain reflective call that is refused with `IllegalAccessException` |
| `invokeSpecial non-virtual dispatch` | `invokeSpecial` runs the body of the class the executable came from while `invoke` dispatches virtually, **hooked as well as unhooked**; a null receiver is a `NullPointerException` and a foreign receiver an `IllegalArgumentException`, not a process abort |
| `invokeSpecial conversions/errors` | The `Method#invoke` conversion matrix and its refusals through `invokeSpecial` |
| `constructor invoker` | `newInstance`, `newInstanceSpecial` (which must leave the subclass uninitialized), the arity and conversion refusals, `InstantiationException` for an abstract class, and the constructor invoked as a method |
| `Invoker primitive widening matrix` | The accepted half of the matrix through every entry point, plus `Type.ORIGIN` bypassing an installed hook that reflection still sees |
| `Invoker rejected conversion matrix` | Every refused pair is refused exactly as `Method#invoke` refuses it, on hooked executables as well as unhooked ones |
| `Invoker reflection-compatible validation` | Receiver rules, arity, a null argument array meaning no arguments, and a static method ignoring its receiver — every line decided by the reflective call rather than by a value we chose |
| `method Invoker target exception wrapping` | Exactly one `InvocationTargetException` around what the target threw, on all four of unhooked/hooked × Chain/Origin, including a target that throws an `InvocationTargetException` itself (`Method#invoke` has no special case for that and reports `ITE(ITE)`) |
| `constructor Invoker target exception wrapping` | The same for `newInstance` |
| `Invoker filtered hook chain ordering` | The trace notation: `H>M>L>O<L<M<H` for `Chain.FULL`, `M>L>O<L<M` for `Chain(PRIORITY_DEFAULT)`, `L>O<L` for `Chain(PRIORITY_LOWEST)`, `O` for `ORIGIN` — through `invoke` and through `invokeSpecial` |
| `constructor Invoker modes and ordering` | The same through `newInstance` and `newInstanceSpecial` |
| `ResHook/layout replacement` | A layout in the app inflates to the module's replacement instead |

And the 22 passing rows kept beside them, so a fix cannot trade one for the other. They are the ones
standing next to a failure — same entry point, same object, often the same fixture — and not a
sample of the report's passes as a whole: `proxy generated method`, `class initializer`,
`resolved Java native method`, `unresolved Java native method`, `unhook is idempotent`,
three of the four `protective:` cases, `passthrough exception`, `invoker full chain and origin`,
`CtorInvoker bypasses constructor access checks`, `invoke Origin all primitive types`,
`invoke Origin target exception`, `invoke widening conversions`,
`invoke rejects invalid arguments`, `invokeSpecial all primitive types`,
`invokeSpecial target exception`, `invokeSpecial bypasses access checks`, `hook id replacement`,
`handle replacement`, `Legacy API/method hook`, `Native API/function hook`. That is 36 of the 37;
the last is `protective: failed then successful proceed`, which is a name the report uses and whose
status on it nobody here has read, so it is counted as neither.

Four rows carry a marked reading and can come back as `READING` rather than `PASS`/`FAIL` without
anything being wrong in the framework: `hook id replacement` and `handle replacement` both assert
the superseded-handle behaviour, `constructor invoker` asserts what `newInstanceSpecial` does with a
class that is not a subclass, and `protective: failed then successful proceed` asserts everything
that turns on a second `proceed()` inside one `intercept`.

### The differential oracle

`Invoker#invoke` is documented `@see Method#invoke`, `CtorInvoker#newInstance` `@see
Constructor#newInstance`. So for every conversion, every refusal and every receiver rule, the
harness makes the identical call twice — once through the invoker and once through a
`setAccessible(true)` reflective copy of the same executable — and asserts the invoker answered what
reflection answered, or failed with the same exception class **and the same cause chain**. That is
`Conversions.reflectively` / `Conversions.agrees`, and it is what those rows assert rather than a
table of values somebody typed.

The table is still there beside it, and it is a second, independent statement: a row where
reflection disagrees with the table reports *the table* as wrong rather than the framework. The
`setAccessible(true)` copy is taken fresh and never applied to an executable handed to the
framework — `Fix` deliberately hands out untouched ones, because a suppressed access check would
quietly satisfy the very thing the invoker is supposed to do on its own.

**The oracle is an addition and never a replacement, and on a hooked executable it is not
independent.** A reflective call on a hooked method travels through the same chain as the invoker,
so a framework that corrupts the answer corrupts both readings identically and the row still goes
green. What the differential is worth there is the *disagreement* — the framework's own entry point
answering differently from the reflective one. So every hooked row states the literal too, and the
literal is what decides it. The rows this matters most on are `Invoker reflection-compatible
validation` (a hooked static returning `static:3`) and `method Invoker target exception wrapping`,
where the differential is deliberately made only *after* the hooks are installed, because above them
both sides are plain ART and the comparison could not fail.

Where the two **differ**, the difference is asserted instead of the agreement — one of these is the
interface's and one is ours:

* the interface's: the invoker bypasses access checks, so every "bypasses access checks" row asserts
  that the plain reflective call is refused with `IllegalAccessException` first. Without that half,
  "the invoker reached it" would also be satisfied by a runtime that checks nothing at all. And
  `Type.ORIGIN` "invokes the original executable, skipping all hooks", which
  `Invoker primitive widening matrix` asserts against an installed hook;
* ours: that a hook installed through `hook(Executable)` is visible to a *plain reflective call* is
  nowhere in `XposedInterface.java` or `package-info.java` — it is what hooking means here, not a
  sentence in the spec. The same row asserts it anyway, because without it the `Type.ORIGIN` half
  would pass on a framework that never installed the hook, and because every case that reads a hook
  through `Fix.call` depends on it.

## What a green run means

A green row is only worth what the assertion behind it could have caught, and some of these are
worth less than others. This section is the honest ranking; keep it current, because a row that
quietly stops discriminating is worse than a missing one.

### Rows that cannot fail on this configuration

* **`framework intrinsic method`** does not run at all where ART would substitute nothing, and
  reports `SETUP` instead of `PASS`. What its guard establishes is narrower than the case name
  suggests, and it is spelled out under [the debuggable trade](#the-debuggable-trade): getting past
  the guard means the hook fired somewhere substitution was *possible*, not that a substitution was
  attempted and beaten. Nothing observable from Java says which.

### Rows with a weaker control than they look like they have

* **The `ExceptionMode.DEFAULT` rows** — `protective: hooker throws before proceed`,
  `protective: hooker throws after proceed`, `protective: failed then successful proceed` and
  `protective: exception from proceed propagates` — all pass `ExceptionMode.DEFAULT` and then
  assert `PROTECTIVE` behaviour. That is only correct because this module's `module.prop` sets no
  exception mode, and `DEFAULT` is documented to "follow the global exception mode configured in
  `module.prop`. Defaults to `PROTECTIVE` if not specified." **The other configuration is
  untested**: a `module.prop` naming `PASSTHROUGH` would have to make the same four rows behave the
  other way, and there is nowhere to put a second `module.prop` without a second module APK. Adding
  one is the fix; nothing in the harness covers it today.
* **`class initializer`** runs over three fixtures — a plain class, an enum (which carries the
  synthetic `$VALUES` field and the generated `values()`/`valueOf()`), and a class whose nest D8 had
  to desugar into synthetic accessors — because a framework that locates `<clinit>` by finding the
  hole in a class's ArtMethod array finds a different hole in each. Each fixture now proves its own
  shape before the case leans on it (`HookCases.requireShape`), so a compiler change that flattens
  one into a copy of the plain class reports `SETUP` rather than a third green row that says
  nothing. Three shapes is better than one; it is still not every shape.

### The debuggable trade

The target APK is debuggable: `run.sh` installs the debug APK, and `target/build.gradle.kts`
configures only the release variant, so debug keeps AGP's default `isDebuggable = true`.

That is deliberate — CheckJNI is what makes `invokeSpecial`'s unchecked JNI preconditions abort
cleanly instead of corrupting memory silently, and reproducing the reported crash needs it. But
**ART performs no intrinsic substitution in a Java-debuggable process**, so a hook installed on an
ART intrinsic in this target fires because nothing inlined it, whatever the framework did. Every
such case is vacuous here. The two cannot be had in one APK.

So `framework intrinsic method` refuses to run and reports `SETUP`. It refuses on **both** inputs it
can read: `ApplicationInfo.FLAG_DEBUGGABLE` on the target, and `Build.TYPE` — because an `eng` or
`userdebug` build sets `ro.debuggable`, which makes every process on it Java-debuggable whatever the
APK says, and the emulator images this suite otherwise runs on are `userdebug`. (`ro.debuggable` is
what actually decides it; `Build.IS_DEBUGGABLE` is not public API, so `Build.TYPE` is the reading a
module can take.)

Getting past that guard is not the same as the case having discriminated. It establishes only that
neither readable input made the process Java-debuggable — nothing observable from Java says whether
ART actually substituted the intrinsic in a given run, so a green row means the hook fired where
substitution was possible. The body behind the check is written for the day
someone builds a non-debuggable target: it hooks `java.lang.Integer.reverse`, which is a real ART
intrinsic — `V(IntegerReverse, kStatic, …, "Ljava/lang/Integer;", "reverse", "(I)I")` in AOSP's
`art/runtime/intrinsics_list.h` — chosen over `StringBuilder.toString` because nothing in the
platform or in the framework's own dispatch path calls it, so hooking it cannot recurse into
itself. It then drives its caller the way lsplant's own `t07_intrinsicMethod` does: two phases of
5000 calls with a sleep between them, so the JIT has both the loop back-edges and the time it needs
to compile the caller, and the second phase enters compiled code from the top. The accumulator and
the varying argument are load-bearing: an intrinsic declared with no side effects and no throw is
one the compiler deletes outright when nothing reads its result, and hoists out of the loop when
its argument does not change. The answer is checked against a sum computed without calling
`Integer.reverse` at all — not against the same caller run twice, which would compare a value to
itself and hold for any framework — so a hook that returns a wrong-but-consistent value is red.

### What this harness does not cover at all

The report totals **46 cases, 14 of them failing**, and that comes out of arithmetic rather than out
of a reading of the screenshot: the header reads `32/46 passed`, so 46 − 32 = 14 failures, and the
visible window holds exactly 14 `FAIL` rows. Every failure is therefore inside the window, and the
~10 rows above it are passes. None of that turns on what the `16` in the header means.

What we have of the report is a screen that starts at `proxy generated method` and runs to the end.

So 37 of 46 are reproduced here, and a fully green run of this harness is still **not** a green run
of the report. Concretely, it proves nothing about:

* **the ~9 passing rows above the window.** Their names were never in view, so there is nothing to
  write down and no case to add. Asking the reporter for the same list scrolled to the top is the
  cheapest way to close that gap.
* **API surface no case touches at all.** `deoptimize`, `getFrameworkProperties`,
  `getRemotePreferences` / `listRemoteFiles` / `openRemoteFile`, `log(...)`, and on the `Chain`:
  `getArg(int)`, `proceed(Object[])`, `proceedWith(Object)`, `proceedWith(Object, Object[])`. Every
  case here proceeds with the arguments it was given.
* **lifecycle beyond `onModuleLoaded` and `onPackageLoaded`.** `onPackageReady`,
  `onSystemServerStarting` and the hot-reload callbacks are never exercised; hot reload has its own
  harness (see the notes at the end).

### Where the assertions are inferred rather than quoted

The report gives names and truncated messages, not source. Four places needed a judgement call, and
a run that disagrees with the report on one of these is as likely to mean the reading was wrong as
that the framework is:

* **`framework intrinsic method`** is read as "hook an ART intrinsic and it must fire". The other
  reading — that hooking a framework-internal method must be *refused* — would report the refusal
  through `IllegalArgumentException` from `intercept`, not through the `AssertionError` the report
  shows, so the case reports the refusal message in its detail if that is what happens.
* **Three of the four `protective:` names are invented.** Only
  `protective: failed then successful proceed` is the report's; the other three are named for the
  three sentences of `ExceptionMode.PROTECTIVE` (throw before proceed, throw after proceed, an
  exception from proceed always propagates) and a name-level diff will not line up on them.
  `protective: failed then successful proceed` is read against those same sentences, and split
  where the reading starts: the hooker throws nothing, so nothing PROTECTIVE catches engages, and
  what is left as a hard assertion is that the first proceed's exception reaches the hooker
  unchanged and unwrapped after the interceptor below and the origin have run. Whether a *second*
  proceed inside the same `intercept` is allowed at all, and whether it re-enters the interceptors
  below or only the origin, is nowhere in the interface — it forbids reuse only *after*
  `Hooker#intercept(Chain)` ends and `proceed` says only "proceeds to the next interceptor in the
  chain" — so the value, the second half of the trace and the origin's call count are all wrapped in
  `Check.reading` and come back as `READING`.
* **`Legacy API/method hook`** is read as the API 102 rule the spec does state: a module targeting
  102 cannot reach `de.robv.android.xposed`. The probe is a *type reference* through `legacystub`,
  not a `Class.forName` with a literal: the framework rewrites those package names per boot, so a
  literal resolves to nothing whether or not the rule is enforced, while a type reference is
  rewritten along with everything else and asks the loader for the name a module would really use.
  The linkage failure alone would also pass on a mis-rewritten dex or a packaging mistake, so the
  case additionally requires the refusal to name the rule — `VectorModuleClassLoader` throws
  `ClassNotFoundException("... is unavailable to modules targeting Xposed API 102 or higher")` and
  ART carries it through as the cause of the `NoClassDefFoundError`. If that sentence ever changes,
  this case has to change with it; a green row that proves nothing is worse than a red one.
* **`ResHook/layout replacement`** has no counterpart in the API 102 spec at all — libxposed 102
  has no resource surface — so it is written against the legacy `XResources` API, reached the same
  way. Vector currently refuses `android.content.res.XRes*` to modules targeting 102 along with
  `de.robv`, so this case is expected to fail with a linkage error until that policy is decided;
  the failure detail carries the reason the registration gave.

Three assertions elsewhere are readings of the interface rather than quotations from it. All are
wrapped in `Check.reading`, so a run reports them as `READING` and not as `FAIL`: a row there is an
argument about what the interface means, and calling it a defect would be an overreach. A reading
also ends its case, so the quotable checks after it did not run and say nothing either. Where a
reading could be put last in its case it was (`constructor invoker`, `protective: failed then
successful proceed`). In the two replacement cases it could not: the checks below it read the state
the reading leaves behind, and after them the handle would be invalid for a second reason, which is
not what the reading is asking about.

* **a superseded hook handle** (replaced by id or by `replaceHook`) refuses `replaceHook` with
  `IllegalStateException` and its `unhook()` does **not** cancel the replacement. The interface says
  the old handle "will be invalid" and that `replaceHook` throws `IllegalStateException` "if this
  hook handle is no longer valid"; cancelling the hook that replaced it is the only other reading
  and it makes atomic replacement useless — but that is an argument, not a sentence in the spec;
* **`newInstanceSpecial` with a class that is not a subclass** is refused with exactly
  `IllegalArgumentException`. The javadoc says only "Creates a new instance of the given subclass"
  and never says what a class which is not one costs. `IllegalArgumentException` is what
  `Method#invoke` answers for a receiver of the wrong type and the invoker is documented against
  `Method#invoke`, but `ClassCastException` and `InstantiationException` are both defensible;
* **a second `proceed()` inside one `intercept`** — that it is allowed at all, that its value is the
  caller's result, that it re-enters the interceptors below rather than only the origin, and that
  each proceed runs the origin exactly once. See the `protective: failed then successful proceed`
  bullet above; the quotable half of that case sits above the reading and is a hard `FAIL`.

`newInstance` on an abstract class throwing `InstantiationException` is *not* in that list, even
though it is an inference about behaviour: `CtorInvoker#newInstance` declares
`throws InstantiationException`, there is nothing else in its signature for an abstract class to
mean, and `Constructor#newInstance` answers the same thing beside it.

One assertion is deliberately loose rather than exact. On a **hooked** `Base.name` with a `Derived`
receiver, `invoke` is asserted to *reach the override* — the answer has to contain `DERIVED` —
rather than to equal a particular string. Whether the chain installed on `Base.name` also wraps that
answer is a reading either way; that the override ran at all is not, and that is the half a
non-virtual backup would get wrong.

### The trace notation

`H>M>L>O<L<M<H` is a High priority interceptor entered, then Medium, then Low, then the origin,
then the unwind. Interceptors write `X>` on entry and `<X` on exit; the **origin writes its own
`O`**, from inside the fixture, which is the only way the trace can prove the executable really ran
between the two halves of the chain rather than an interceptor claiming it did. The buffer lives in
the app (`org.matrix.vxtarget.fix.Trace`) for the same reason. `protective: failed then successful
proceed` extends the notation with the origin's own call number, so `R>M>O1<M|M>O2<M<R` reads as one
interceptor proceeding twice around a `|` with the whole rest of the chain re-entered each time. It
asserts that trace in two pieces: everything up to and including the `|` is quotable and hard, and
the tail after it is the [reading](#where-the-assertions-are-inferred-rather-than-quoted) about what
a repeat proceed re-enters.

## Notes

* The module declares `minApiVersion`/`targetApiVersion` 102 and exactly one Java entry class, so
  it is also a well-formed hot-reload subject; hot reload itself is not covered here — that is what
  `tests/api102-hot-reload` on the `api102-harness` branch is for.
* `META-INF/xposed/native_init.list` names `libvxmodhook.so`. The module loads it from Java, which
  is what makes the framework notice the name and call `native_init`; the case then inline-hooks one
  C function in the app and reads the answer back through the app's own JNI, so the whole native API
  case is observable from Java.
* The suite installs no hooks of its own outside a case, and every case undoes its hooks when it
  ends, so a case only ever measures what it asked for.
* `Prims` echoes its argument rather than answering a constant on purpose: a framework that quietly
  narrows `Integer(300)` into a `byte` parameter answers 44, and one that refuses throws. A fixture
  that ignored its argument would tell us nothing.
