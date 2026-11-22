package dev.jnz.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** 在调用站点首次解析时恢复 JVM 字符串字面值。 */
public final class LiteralSite {
    private LiteralSite() {}

    public static String decode(String masked, int seed) {
        return StringMask.reveal(masked, seed).intern();
    }

    public static CallSite link(MethodHandles.Lookup caller, String name, MethodType type,
                                String masked, int seed) {
        if (!type.equals(MethodType.methodType(String.class))) throw new BootstrapMethodError("literal descriptor");
        return new ConstantCallSite(MethodHandles.constant(String.class, decode(masked, seed)));
    }
}
