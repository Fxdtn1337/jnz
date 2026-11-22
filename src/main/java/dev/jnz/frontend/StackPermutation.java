package dev.jnz.frontend;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/** JVM 的操作数宽度按 slot 计数，VM 每个值固定占一格。输出编号从原栈顶开始。 */
final class StackPermutation implements Opcodes {
    final int input;
    final int[] output;

    private StackPermutation(int input, int[] output) {
        this.input = input;
        this.output = output;
    }

    static boolean handles(int op) {
        return op == POP2 || (op >= DUP_X1 && op <= SWAP);
    }

    int size() {
        return input == 0 ? 1 : input + output.length;
    }

    int scratchCount() {
        return output.length == 0 ? 0 : input;
    }

    static StackPermutation decode(int op, Frame<BasicValue> frame) throws Lowering.Unsupported {
        // Analyzer 对控制流不可达的指令返回 null，该位置保留一个 NOP 供标签定位。
        if (frame == null) return new StackPermutation(0, new int[0]);
        if (op == POP2) return new StackPermutation(valuesForSlots(frame, 0, 2), new int[0]);
        if (op == SWAP) return new StackPermutation(2, new int[]{0, 1});
        int duplicateSlots = op < DUP2 ? 1 : 2;
        int insertionSlots = (op - DUP) % 3;
        int duplicate = valuesForSlots(frame, 0, duplicateSlots);
        int insert = valuesForSlots(frame, duplicate, insertionSlots);
        int[] order = new int[2 * duplicate + insert];
        int pos = 0;
        for (int i = duplicate - 1; i >= 0; i--) order[pos++] = i;
        for (int i = duplicate + insert - 1; i >= duplicate; i--) order[pos++] = i;
        for (int i = duplicate - 1; i >= 0; i--) order[pos++] = i;
        return new StackPermutation(duplicate + insert, order);
    }

    private static int valuesForSlots(Frame<BasicValue> frame, int offset, int slots) throws Lowering.Unsupported {
        int count = 0;
        while (slots > 0) {
            int index = frame.getStackSize() - offset - count - 1;
            if (index < 0) throw new Lowering.Unsupported("stack shuffle underflow");
            slots -= frame.getStack(index).getSize();
            count++;
        }
        if (slots != 0) throw new Lowering.Unsupported("stack shuffle splits category-2 value");
        return count;
    }
}
