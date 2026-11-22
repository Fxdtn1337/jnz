package dev.jnz.frontend;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** 匹配 JVM 内部名称，保留 Unicode 字符和大小写。 */
public final class MethodSelector {
    private final List<String> includes, excludes;

    MethodSelector(String include, String exclude) {
        includes = split(include);
        excludes = split(exclude);
    }

    boolean accepts(ClassNode owner, MethodNode method) {
        return (includes.isEmpty() || matches(includes, owner, method))
                && !matches(excludes, owner, method);
    }

    /** 部分 VM 模式使用与前端相同的名称通配符，按已经选取的方法选择 VM 后端。 */
    public static boolean matchesProgram(String expression, Program method) {
        return matchesProgram(expression, method.owner, method.name, method.desc);
    }

    public static boolean matchesProgram(String expression, String owner, String name, String descriptor) {
        for (String pattern : split(expression)) {
            if (pattern.startsWith("@")) throw new IllegalArgumentException("--vm-include 只接受类名、方法名或描述符");
            String candidate = owner;
            if (pattern.indexOf('#') >= 0) {
                candidate += "#" + name;
                if (pattern.indexOf('(') >= 0) candidate += descriptor;
            }
            if (glob(pattern, candidate)) return true;
        }
        return false;
    }

    private static List<String> split(String expression) {
        List<String> patterns = new ArrayList<>();
        if (expression != null) {
            for (String part : expression.split(",")) {
                String pattern = part.trim();
                if (!pattern.isEmpty()) patterns.add(pattern);
            }
        }
        return patterns;
    }

    private static boolean matches(List<String> patterns, ClassNode owner, MethodNode method) {
        for (String pattern : patterns) {
            if (pattern.charAt(0) == '@') {
                String ann = pattern.substring(1);
                if (annotation(ann, owner.visibleAnnotations) || annotation(ann, owner.invisibleAnnotations)
                        || annotation(ann, method.visibleAnnotations) || annotation(ann, method.invisibleAnnotations)) return true;
            } else {
                String candidate = owner.name;
                if (pattern.indexOf('#') >= 0) {
                    candidate += "#" + method.name;
                    if (pattern.indexOf('(') >= 0) candidate += method.desc;
                }
                if (glob(pattern, candidate)) return true;
            }
        }
        return false;
    }

    private static boolean annotation(String pattern, List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            String desc = annotation.desc;
            if (desc.length() >= 2 && glob(pattern, desc.substring(1, desc.length() - 1))) return true;
        }
        return false;
    }

    private static boolean glob(String pattern, String value) {
        int p = 0, v = 0, star = -1, retry = 0;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                retry = v;
            } else if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (star >= 0) {
                p = star + 1;
                v = ++retry;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
