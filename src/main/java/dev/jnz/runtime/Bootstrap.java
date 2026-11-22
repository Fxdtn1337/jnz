package dev.jnz.runtime;

import java.lang.reflect.InvocationTargetException;

/** 应用类初始化进入 JNI 绑定的入口。 */
public final class Bootstrap {
    private Bootstrap() {}

    public static String hostTarget() {
        return PlatformTarget.hostTarget();
    }

    public static synchronized void bind(Class<?> owner, int classId) {
        try {
            BindingCache.bind(owner, classId);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("JNI 注册失败 " + owner.getName(), cause);
        } catch (Exception error) {
            throw new IllegalStateException("初始化原生方法失败 " + owner.getName(), error);
        }
    }
}
