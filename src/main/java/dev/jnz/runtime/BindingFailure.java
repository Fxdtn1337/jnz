package dev.jnz.runtime;

import java.lang.reflect.InvocationTargetException;

final class BindingFailure {
    private BindingFailure() {}

    static RuntimeException describe(Class<?> owner, Exception error) {
        Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
        if (cause instanceof Error) throw (Error) cause;
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        return new IllegalStateException("初始化原生方法失败 " + owner.getName(), cause);
    }
}
