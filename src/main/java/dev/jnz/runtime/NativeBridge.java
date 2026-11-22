package dev.jnz.runtime;

import java.io.IOException;
import java.lang.reflect.Method;

/** 从组路由解析 JVM 需要的两个 JNI 符号。 */
final class NativeBridge {
    private final Method registration;
    private final Method verification;

    private NativeBridge(Method registration, Method verification) {
        this.registration = registration;
        this.verification = verification;
    }

    static NativeBridge resolve(int group) throws Exception {
        String[] route = PackedResources.groupBinding(group).split("\\|", -1);
        if (route.length != 3 || !route[0].matches("R[0-9a-f]{24}")
                || !route[1].matches("m[0-9a-f]{24}") || !route[2].matches("m[0-9a-f]{24}"))
            throw new IOException("无效的 JNI 桥接名称");
        String name = NativeBridge.class.getName();
        String owner = name.substring(0, name.lastIndexOf('.') + 1) + route[0];
        Class<?> bridge = Class.forName(owner, false, NativeBridge.class.getClassLoader());
        return new NativeBridge(bridge.getDeclaredMethod(route[1], Class.class, Integer.TYPE),
                bridge.getDeclaredMethod(route[2], byte[].class, byte[].class, byte[].class));
    }

    void register(Class<?> owner, int classId) throws Exception {
        registration.invoke(null, owner, classId);
    }

    boolean verify(byte[] key, byte[] payload, byte[] signature) throws Exception {
        return Boolean.TRUE.equals(verification.invoke(null, key, payload, signature));
    }
}
