package dev.jnz.frontend;

import dev.jnz.pack.Bundle;
import dev.jnz.protect.FlowMask;
import dev.jnz.protect.LiteralMask;
import java.security.GeneralSecurityException;
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
        public int literals, aesLiterals, xorLiterals, dynamicSites, jumps, dispatchers, skippedClasses;

        private void merge(Stats stage) {
            literals += stage.literals;
            aesLiterals += stage.aesLiterals;
            xorLiterals += stage.xorLiterals;
            dynamicSites += stage.dynamicSites;
            jumps += stage.jumps;
            dispatchers += stage.dispatchers;
        }
    }

    private PreObfuscator() {}

    public static Stats apply(Map<String, byte[]> entries, String includes, String excludes, Bundle.Layout layout) {
        return apply(entries, includes, excludes, layout, "balanced", "aes");
    }

    public static Stats apply(Map<String, byte[]> entries, String includes, String excludes, Bundle.Layout layout,
                              String flowProfile, String cipher) {
        if (!"off".equals(flowProfile) && !"balanced".equals(flowProfile) && !"strong".equals(flowProfile))
            throw new IllegalArgumentException("flow-profile 必须为 off、balanced 或 strong");
        if (!"off".equals(cipher) && !"xor".equals(cipher) && !"aes".equals(cipher))
            throw new IllegalArgumentException("string-cipher 必须为 off、xor 或 aes");
        Stats total = new Stats();
        if ("off".equals(flowProfile) && "off".equals(cipher)) return total;
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
                ClassReader reader = new ClassReader(input);
                reader.accept(node, ClassReader.EXPAND_FRAMES);
                if (!ConstantPoolSafety.canRebuild(reader, node)) {
                    total.skippedClasses++;
                    continue;
                }
                Stats stats = rewrite(node, selector, layout, bsm, major, random, flowProfile, cipher);
                if (stats.literals == 0 && stats.jumps == 0) continue;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                entry.setValue(writer.toByteArray());
                total.merge(stats);
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("当前 Java 环境无法执行 AES-GCM 字符串混淆", error);
            } catch (RuntimeException skipped) {
                total.skippedClasses++;
                // 前端会用原 class 诊断不可解析项。字节码接近 JVM 大小上限时也保留原 class。
            }
        }
        return total;
    }

    private static Stats rewrite(ClassNode owner, MethodSelector selector, Bundle.Layout layout, Handle bsm,
                                 int major, SecureRandom random, String flowProfile, String cipher)
            throws GeneralSecurityException {
        Stats stats = new Stats();
        for (MethodNode method : owner.methods) {
            if (!selector.accepts(owner, method)) continue;
            if (!"off".equals(cipher)) rewriteStrings(method, layout, bsm, major, random, cipher, stats);
            FlowMask.Result flow = FlowMask.apply(method, random::nextInt, flowProfile);
            stats.jumps += flow.edges;
            stats.dispatchers += flow.dispatchers;
        }
        return stats;
    }

    private static void rewriteStrings(MethodNode method, Bundle.Layout layout, Handle bsm,
                                       int major, SecureRandom random, String cipher, Stats stats)
            throws GeneralSecurityException {
        boolean aes = "aes".equals(cipher);
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof LdcInsnNode) || !(((LdcInsnNode) insn).cst instanceof String)) continue;
            String text = (String) ((LdcInsnNode) insn).cst;
            if (text.isEmpty()) continue;
            int seed = random.nextInt();
            if (seed == 0) seed = 1;
            String mask = aes ? LiteralMask.encodeAes(text, seed, random) : LiteralMask.encode(text, seed);
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
            if (aes) stats.aesLiterals++;
            else stats.xorLiterals++;
        }
    }
}
