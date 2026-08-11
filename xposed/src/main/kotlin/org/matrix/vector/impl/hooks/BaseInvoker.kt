package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.CtorInvoker
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/**
 * Base implementation of the Invoker system. Handles the resolution of [Invoker.Type] to determine
 * whether to execute the original method directly or to construct a partial interceptor chain.
 *
 * The vararg entry points the interface declares are not here but in [InvokerEntry], which is Java
 * for the one reason given there; what arrives here is the array they normalised.
 */
internal abstract class BaseInvoker<T : Invoker<T, U>, U : Executable>(
    protected val executable: U
) : InvokerEntry<T, U> {

    protected var type: Invoker.Type = Invoker.Type.Chain.FULL

    // An invoker names one executable for its whole life, and each of these would otherwise be
    // rebuilt per call: getParameterTypes clones its array every time it is asked, and the shorty
    // is derived from that array.
    private val parameterTypes: Array<Class<*>> = executable.parameterTypes
    private val shorty: CharArray = VectorInvocation.shortyOf(executable, parameterTypes)
    private val declaringClass: Class<*> = executable.declaringClass
    private val isStatic: Boolean = Modifier.isStatic(executable.modifiers)

    @Suppress("UNCHECKED_CAST")
    override fun setType(type: Invoker.Type): T {
        this.type = type
        return this as T
    }

    /**
     * Resolves the current [type] and runs the executable, non-virtually when [nonVirtual].
     *
     * The receiver and the arguments are checked before the chain is entered, because Method#invoke
     * reports its own refusals unwrapped and reserves InvocationTargetException for what the call
     * threw - and everything thrown inside the chain is what the call threw. [onReceiver] reports
     * the receiver each dispatch actually ran against, which a hooker may have redirected.
     */
    protected fun proceedInvocation(
        thisObject: Any?,
        args: Array<out Any?>,
        nonVirtual: Boolean,
        onReceiver: (Any?) -> Unit = {},
    ): Any? {
        val receiver = VectorInvocation.checkReceiver(executable, isStatic, thisObject)
        val actualArgs = VectorInvocation.coerceArguments(executable, parameterTypes, args)

        // Reaches the body this invoker names, never through the trampoline.
        fun dispatch(tObj: Any?, tArgs: Array<Any?>): Any? {
            onReceiver(tObj)
            return HookBridge.invokeOriginal(
                executable,
                shorty,
                parameterTypes,
                declaringClass,
                isStatic,
                nonVirtual,
                tObj,
                tArgs,
            )
        }

        return when (val currentType = type) {
            is Invoker.Type.Origin -> dispatch(receiver, actualArgs)
            is Invoker.Type.Chain -> {
                val snapshots =
                    HookBridge.callbackSnapshot(VectorHookRecord::class.java, executable)
                        // The executable carries no hooks, so there is no chain to enter. Invokers
                        // default to Type.Chain.FULL, so this is the ordinary case for a module
                        // that obtains an invoker for a method it has not hooked.
                        ?: return dispatch(receiver, actualArgs)

                @Suppress("UNCHECKED_CAST")
                val allModernHooks = snapshots[0] as Array<VectorHookRecord>
                val legacyHooks = snapshots[1]

                // Filter hooks to respect the maxPriority requested by the module
                val filteredHooks =
                    allModernHooks.filter { it.priority <= currentType.maxPriority }.toTypedArray()

                // Chain#proceed is documented to throw whatever the original executable threw, so
                // the reflective wrapper comes off here rather than at the public boundary.
                val runOriginal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    try {
                        dispatch(tObj, tArgs)
                    } catch (e: InvocationTargetException) {
                        throw e.cause ?: e
                    }
                }

                val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
                    val delegate = VectorBootstrap.delegate
                    if (legacyHooks.isNotEmpty() && delegate != null) {
                        delegate.processLegacyHook(executable, tObj, tArgs, legacyHooks) {
                            runOriginal(tObj, tArgs)
                        }
                    } else {
                        runOriginal(tObj, tArgs)
                    }
                }

                val chain =
                    VectorChain(executable, receiver, actualArgs, filteredHooks, 0, terminal)
                try {
                    chain.proceed()
                } catch (t: Throwable) {
                    // The terminal took the wrapper off, so whatever arrives here is what the call
                    // produced - the executable's exception or a hooker's - and Method#invoke
                    // reports that wrapped, including an InvocationTargetException of its own.
                    throw InvocationTargetException(t)
                }
            }
        }
    }
}

/** Invoker implementation specifically for [Method] types. */
internal class VectorMethodInvoker(method: Method) :
    BaseInvoker<VectorMethodInvoker, Method>(method) {

    override fun invokeWith(thisObject: Any?, args: Array<Any?>): Any? =
        proceedInvocation(thisObject, args, nonVirtual = false)

    override fun invokeSpecialWith(thisObject: Any?, args: Array<Any?>): Any? =
        proceedInvocation(thisObject, args, nonVirtual = true)
}

/**
 * Invoker implementation specifically for [Constructor] types. Extends capabilities to allocate and
 * initialize objects safely.
 */
internal class VectorCtorInvoker<T : Any>(constructor: Constructor<T>) :
    BaseInvoker<CtorInvoker<T>, Constructor<T>>(constructor), InvokerEntry.Ctor<T> {

    // A constructor is a direct method: it has no vtable slot for a receiver's class to override,
    // so every way of calling one is non-virtual.
    override fun invokeWith(thisObject: Any?, args: Array<Any?>): Any? {
        // Invoking a constructor as a method returns nothing (void/null)
        proceedInvocation(thisObject, args, nonVirtual = true)
        return null
    }

    override fun invokeSpecialWith(thisObject: Any?, args: Array<Any?>): Any? {
        proceedInvocation(thisObject, args, nonVirtual = true)
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun newInstanceWith(args: Array<Any?>): T {
        // Allocate memory without invoking <init>
        val allocated = HookBridge.allocateObject(executable.declaringClass)
        // A hooker may redirect the construction with Chain#proceedWith, and newInstance is
        // documented to return the instance the constructor initialized, not the one allocated.
        // Whichever object the chain settled on is a T: the declaring class here is the type asked
        // for, and no receiver that is not an instance of it reaches the constructor.
        var initialized: Any? = allocated
        proceedInvocation(allocated, args, nonVirtual = true) { initialized = it }
        return initialized as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> newInstanceSpecialWith(subClass: Class<V>, args: Array<Any?>): V {
        if (!executable.declaringClass.isAssignableFrom(subClass)) {
            throw IllegalArgumentException(
                "$subClass is not inherited from ${executable.declaringClass}"
            )
        }
        val allocated = HookBridge.allocateObject(subClass)
        var initialized: Any? = allocated
        proceedInvocation(allocated, args, nonVirtual = true) { initialized = it }
        // Here the type asked for is not the one the chain has to keep: a hooker's proceedWith only
        // owes the constructor an instance of its declaring class, the parent. Handing that back
        // would return something that is not a V, and the caller would find out at its own
        // checkcast, nowhere near the hooker that caused it.
        return (if (subClass.isInstance(initialized)) initialized else allocated) as V
    }
}
