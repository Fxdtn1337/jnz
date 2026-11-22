package dev.jnz.runtime;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class BridgeResolver {
    private BridgeResolver() {}

    static Method resolve(BridgeNames route) throws Exception {
        String self = BridgeResolver.class.getName();
        ClassLoader loader = BridgeResolver.class.getClassLoader();
        Class<?> bridge = Class.forName(self.substring(0, self.lastIndexOf('.') + 1) + route.owner, false, loader);
        Method method = bridge.getDeclaredMethod(route.method, Class.class, Integer.TYPE);
        int flags = method.getModifiers();
        if (bridge.getClassLoader() != loader || !Modifier.isNative(flags) || !Modifier.isStatic(flags)
                || method.getReturnType() != Void.TYPE) throw new IOException("JNI 桥接声明不匹配");
        return method;
    }
}
