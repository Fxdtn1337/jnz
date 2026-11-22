package dev.jnz.runtime;

/** 应用类初始化进入 JNI 绑定的入口。 */
public final class Bootstrap {
    private Bootstrap() {}

    public static String hostTarget() {
        return PlatformTarget.hostTarget();
    }

    public static synchronized void bind(Class<?> owner, int classId) {
        try {
            BindingCache.bind(owner, classId);
        } catch (Exception error) {
            throw BindingFailure.describe(owner, error);
        }
    }
}
