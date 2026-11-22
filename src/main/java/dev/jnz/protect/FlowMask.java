package dev.jnz.protect;

import java.util.function.IntSupplier;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** 替换既有跳边的进入路径，原分支目标和进入目标时的栈状态保持一致。 */
public final class FlowMask {
    private FlowMask() {}

    public static int apply(MethodNode method, IntSupplier random) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != GOTO) continue;
            JumpInsnNode jump = (JumpInsnNode) insn;
            int first = random.getAsInt();
            int second = random.getAsInt();
            if (first == second) second ^= 1;
            InsnList gate = new InsnList();
            gate.add(new LdcInsnNode(first));
            gate.add(new LdcInsnNode(second));
            gate.add(new InsnNode(IXOR));
            gate.add(new JumpInsnNode(IFNE, jump.label));
            method.instructions.insertBefore(jump, gate);
            count++;
        }
        return count;
    }
}
