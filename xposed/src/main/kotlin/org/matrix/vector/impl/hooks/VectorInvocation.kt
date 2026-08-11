package org.matrix.vector.impl.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.matrix.vector.nativebridge.HookBridge

/**
 * What java.lang.reflect does to a receiver and an argument list before it calls anything, for the
 * callers that reach the executable through JNI instead.
 *
 * JNI reports none of it. A foreign receiver or a mistyped argument is not refused, it is executed,
 * with the callee reading fields at offsets that belong to a different layout; a boxed value of the
 * wrong width is not refused either, it is silently truncated. Method#invoke reports all of that as
 * IllegalArgumentException, and the invoker interface is specified against Method#invoke.
 *
 * The checks live here rather than in the JNI backend because a shorty cannot name the declared
 * class of a reference parameter, and they run before the hook chain is entered because everything
 * thrown inside the chain is reported wrapped - a refusal of ours is not something the call
 * produced.
 *
 * The messages are ART's own, from `art/runtime/reflection.cc`, so a module that reads one - or a
 * conformance suite that asserts on it - gets the same text here as from Method#invoke.
 * `Class#getTypeName` is the Java side of ART's PrettyDescriptor: dotted, and `int[]` for an array.
 */
object VectorInvocation {

    /**
     * The JNI shorty of [executable]: the return type first, then one character per parameter.
     * Reference types and arrays are both 'L', which is ART's own convention.
     */
    fun shortyOf(executable: Executable, parameterTypes: Array<Class<*>>): CharArray {
        val shorty = CharArray(parameterTypes.size + 1)
        shorty[0] = shortyOf(if (executable is Method) executable.returnType else Void.TYPE)
        for (i in parameterTypes.indices) {
            shorty[i + 1] = shortyOf(parameterTypes[i])
        }
        return shorty
    }

    private fun shortyOf(type: Class<*>): Char =
        when (type) {
            Int::class.javaPrimitiveType -> 'I'
            Long::class.javaPrimitiveType -> 'J'
            Float::class.javaPrimitiveType -> 'F'
            Double::class.javaPrimitiveType -> 'D'
            Boolean::class.javaPrimitiveType -> 'Z'
            Byte::class.javaPrimitiveType -> 'B'
            Char::class.javaPrimitiveType -> 'C'
            Short::class.javaPrimitiveType -> 'S'
            Void.TYPE -> 'V'
            else -> 'L'
        }

    /**
     * The receiver Method#invoke would call with: a static executable ignores it, a missing one is
     * a NullPointerException and one of a foreign class an IllegalArgumentException.
     */
    fun checkReceiver(executable: Executable, isStatic: Boolean, thisObject: Any?): Any? {
        if (isStatic) return null
        if (thisObject == null) throw NullPointerException("null receiver")
        if (!executable.declaringClass.isInstance(thisObject)) {
            throw IllegalArgumentException(
                "Expected receiver of type ${executable.declaringClass.typeName}, " +
                    "but got ${thisObject.javaClass.typeName}"
            )
        }
        return thisObject
    }

    /**
     * The argument list Method#invoke would build: one identity or widening conversion per
     * argument, and IllegalArgumentException for every other pair.
     *
     * Converting here rather than at the dispatch is what lets a hooker see through Chain#getArgs
     * the values the executable will actually receive, which is what a hooked call arriving from
     * real bytecode always carries.
     */
    fun coerceArguments(
        executable: Executable,
        parameterTypes: Array<Class<*>>,
        args: Array<out Any?>,
    ): Array<Any?> {
        if (args.size != parameterTypes.size) {
            throw IllegalArgumentException(
                "Wrong number of arguments; expected ${parameterTypes.size}, got ${args.size}"
            )
        }
        return Array(args.size) { i -> coerce(executable, parameterTypes[i], args[i], i) }
    }

    private fun coerce(executable: Executable, type: Class<*>, value: Any?, index: Int): Any? {
        if (!type.isPrimitive) {
            // Class#isInstance is the whole rule: it answers for interfaces, for arrays and their
            // covariance, and false for null, which is why null is short-circuited first.
            if (value != null && !type.isInstance(value)) {
                throw mismatch(executable, index, type, value)
            }
            return value
        }
        // A null where a primitive is declared is refused with the same message: ART routes it
        // through the same test as a mistyped reference and prints "null" for what it got.
        if (value == null) throw mismatch(executable, index, type, null)
        // Identity plus the widening primitive conversions of JLS 5.1.2, and nothing else. The
        // tests below are exact-wrapper tests, which is what reflection does: a Number that is not
        // one of the eight wrappers converts to nothing, and neither does a Character to a short.
        val widened: Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> value as? Boolean
                Char::class.javaPrimitiveType -> value as? Char
                Byte::class.javaPrimitiveType -> value as? Byte
                Short::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toShort()
                        is Short -> value
                        else -> null
                    }
                Int::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toInt()
                        is Short -> value.toInt()
                        is Char -> value.code
                        is Int -> value
                        else -> null
                    }
                Long::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toLong()
                        is Short -> value.toLong()
                        is Char -> value.code.toLong()
                        is Int -> value.toLong()
                        is Long -> value
                        else -> null
                    }
                Float::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toFloat()
                        is Short -> value.toFloat()
                        is Char -> value.code.toFloat()
                        is Int -> value.toFloat()
                        is Long -> value.toFloat()
                        is Float -> value
                        else -> null
                    }
                Double::class.javaPrimitiveType ->
                    when (value) {
                        is Byte -> value.toDouble()
                        is Short -> value.toDouble()
                        is Char -> value.code.toDouble()
                        is Int -> value.toDouble()
                        is Long -> value.toDouble()
                        is Float -> value.toDouble()
                        is Double -> value
                        else -> null
                    }
                else -> null
            }
        return widened ?: throw mismatch(executable, index, type, value)
    }

    private fun mismatch(
        executable: Executable,
        index: Int,
        type: Class<*>,
        value: Any?,
    ): IllegalArgumentException =
        IllegalArgumentException(
            "method ${prettyMethod(executable)} argument ${index + 1} has type " +
                "${type.typeName}, got ${value?.javaClass?.typeName ?: "null"}"
        )

    /**
     * ART's PrettyMethod without the signature: the declaring class and the name the runtime knows
     * the member by, which for a constructor is `<init>` and not the class name Constructor#getName
     * reports. Arguments are numbered from one for the same reason ART numbers them from one.
     */
    private fun prettyMethod(executable: Executable): String {
        val name = if (executable is Constructor<*>) "<init>" else executable.name
        return "${executable.declaringClass.typeName}.$name"
    }

    /**
     * The whole of the legacy bridge's invocation. `XposedBridge.invokeOriginalMethod` is
     * documented as Method#invoke without the access check and is handed an Executable of either
     * kind, so it needs what an invoker needs; it holds no invoker, so nothing here is cached.
     *
     * A constructor is dispatched non-virtually because it is a direct method either way, and a
     * method virtually because that is what the Method#invoke it is documented against does.
     *
     * IllegalAccessException is not among the outcomes: neither branch of the dispatch runs an
     * access check, which is the whole point of the legacy bridge's "access permissions are not
     * checked".
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class, InvocationTargetException::class)
    fun invokeOriginal(executable: Executable, thisObject: Any?, args: Array<Any?>): Any? {
        val parameterTypes = executable.parameterTypes
        val isStatic = Modifier.isStatic(executable.modifiers)
        return HookBridge.invokeOriginal(
            executable,
            shortyOf(executable, parameterTypes),
            parameterTypes,
            executable.declaringClass,
            isStatic,
            executable is Constructor<*>,
            checkReceiver(executable, isStatic, thisObject),
            coerceArguments(executable, parameterTypes, args),
        )
    }
}
