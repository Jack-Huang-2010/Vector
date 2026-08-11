package org.matrix.vxtarget.fix;

/**
 * One echo per declared parameter type.
 *
 * <p>Echoing rather than answering a constant is what makes a silent narrowing visible: a
 * framework that hands {@code Integer(300)} to {@code echoByte} without complaining answers 44,
 * and a framework that refuses it throws. Both are readable from the caller; a method that
 * ignored its argument would tell us nothing.
 *
 * <p>They are instance methods because invokeSpecial needs a receiver, and every case that drives
 * invoke drives invokeSpecial over the same fixtures.
 */
public class Prims {

    public boolean echoBoolean(boolean v) {
        return v;
    }

    public byte echoByte(byte v) {
        return v;
    }

    public char echoChar(char v) {
        return v;
    }

    public short echoShort(short v) {
        return v;
    }

    public int echoInt(int v) {
        return v;
    }

    public long echoLong(long v) {
        return v;
    }

    public float echoFloat(float v) {
        return v;
    }

    public double echoDouble(double v) {
        return v;
    }

    public void echoVoid() {}

    public String echoRef(CharSequence v) {
        return "ref:" + v;
    }

    public int[] echoArray(int[] v) {
        return v;
    }

    public String all(boolean z, byte b, char c, short s, int i, long j, float f, double d) {
        return z + "/" + b + "/" + c + "/" + s + "/" + i + "/" + j + "/" + f + "/" + d;
    }

    /** Method#invoke ignores the receiver for a static method; an invoker is specified against it. */
    public static String staticEcho(int v) {
        return "static:" + v;
    }
}
