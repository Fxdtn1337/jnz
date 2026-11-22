package dev.jnz.frontend;

import dev.jnz.pack.Bundle;
import dev.jnz.protect.FlowMask;
import dev.jnz.protect.LiteralMask;
import java.security.SecureRandom;
import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** 对选中方法先处理原始 class，再交给 Java→native 前端。 */
public final class PreObfuscator {
    private static final String STRING = "()Ljava/lang/String;";
    private static final String DECODE = "(Ljava/lang/String;I)Ljava/lang/String;";
    private static final String BOOTSTRAP =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;";

    public static final class Stats {
        public int literals, dynamicSites, jumps;
    }

    private PreObfuscator() {}

    public static Stats apply(Map<String, byte[]> entries, String includes, String excludes, Bundle.Layout layout) {
        Stats total = new Stats();
        SecureRandom random = new SecureRandom();
        MethodSelector selector = new MethodSelector(includes, excludes);
        Handle bsm = new Handle(H_INVOKESTATIC, layout.literalOwner(), layout.literalBootstrap(), BOOTSTRAP, false);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            byte[] input = entry.getValue();
            if (!entry.getKey().endsWith(".class") || input.length < 8) continue;
            int major = (input[6] & 255) << 8 | input[7] & 255;
            if (major < V1_5 || major > V27) continue;
            try {
                ClassNode node = new ClassNode(ASM9);
                new ClassReader(input).accept(node, 0);
                if (opaqueAttributes(node)) continue;
                Stats stats = rewrite(node, selector, layout, bsm, major, random);
                if (stats.literals == 0 && stats.jumps == 0) continue;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                entry.setValue(writer.toByteArray());
                total.literals += stats.literals;
                total.dynamicSites += stats.dynamicSites;
                total.jumps += stats.jumps;
            } catch (RuntimeException skipped) {
                // 前端会用原 class 诊断不可解析项。字节码接近 JVM 大小上限时也保留原 class。
            }
        }
        return total;
    }

    private static boolean opaqueAttributes(ClassNode node) {
        if (node.attrs != null && !node.attrs.isEmpty()) return true;
        for (FieldNode f : node.fields) if (f.attrs != null && !f.attrs.isEmpty()) return true;
        for (MethodNode m : node.methods) if (m.attrs != null && !m.attrs.isEmpty()) return true;
        if (node.recordComponents != null) {
            for (RecordComponentNode r : node.recordComponents) if (r.attrs != null && !r.attrs.isEmpty()) return true;
        }
        return false;
    }

    private static Stats rewrite(ClassNode owner, MethodSelector selector, Bundle.Layout layout, Handle bsm,
                                 int major, SecureRandom random) {
        Stats stats = new Stats();
        for (MethodNode method : owner.methods) {
            if (!selector.accepts(owner, method)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof LdcInsnNode) || !(((LdcInsnNode) insn).cst instanceof String)) continue;
                String text = (String) ((LdcInsnNode) insn).cst;
                if (text.isEmpty()) continue;
                int seed = random.nextInt();
                if (seed == 0) seed = 1;
                String mask = LiteralMask.encode(text, seed);
                if (mask.length() > 65535) continue;
                if (major >= V1_7) {
                    method.instructions.set(insn, new InvokeDynamicInsnNode("s" + Integer.toUnsignedString(random.nextInt(), 36),
                            STRING, bsm, mask, seed));
                    stats.dynamicSites++;
                } else {
                    InsnList replacement = new InsnList();
                    replacement.add(new LdcInsnNode(mask));
                    replacement.add(new LdcInsnNode(seed));
                    replacement.add(new MethodInsnNode(INVOKESTATIC, layout.literalOwner(), layout.literalDecode(), DECODE, false));
                    method.instructions.insertBefore(insn, replacement);
                    method.instructions.remove(insn);
                }
                stats.literals++;
            }
            stats.jumps += FlowMask.apply(method, random::nextInt);
        }
        return stats;
    }
}
