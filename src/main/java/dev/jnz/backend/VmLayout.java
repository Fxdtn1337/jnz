package dev.jnz.backend;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.objectweb.asm.Opcodes.GOTO;

/** 重排 VM 基本块和异常区间，保持原有跳边与异常优先级。 */
final class VmLayout {
    static final int SEED_BYTES = Long.BYTES;

    static final class Layout {
        final int[] instructions;
        final int[] handlers;

        Layout(int[] instructions, int[] handlers) {
            this.instructions = instructions;
            this.handlers = handlers;
        }
    }

    private VmLayout() {}

    static Layout relocate(int[] code, int[] handlers, SecureRandom random) throws IOException {
        checkInput(code, handlers);
        List<Integer> leaders = BasicBlocks.leaders(code, handlers);
        int blockCount = leaders.size() - 1;
        if (blockCount <= 1) return new Layout(code, handlers);

        int[] from = new int[handlers.length / 4];
        int[] to = new int[from.length];
        long maxSplits = 0;
        for (int entry = 0; entry < from.length; entry++) {
            from[entry] = Collections.binarySearch(leaders, handlers[entry * 4]);
            to[entry] = Collections.binarySearch(leaders, handlers[entry * 4 + 1]);
            maxSplits += to[entry] - from[entry];
        }
        // JVM 异常表可以让多个长区间重叠。避免打散这种输入时将原本较小的表扩张到平方级。
        if (maxSplits > 8L * (blockCount + (long) from.length)) return new Layout(code, handlers);

        List<Integer> order = shuffledOrder(blockCount, random);
        int[] positions = new int[code.length / 3 + 1];
        int[] starts = new int[blockCount + 1];
        int[] physicalOrder = new int[blockCount];
        boolean[] jumps = new boolean[blockCount];
        int length = 1; // 入口 GOTO 始终位于物理位置 0。
        for (int offset = 0; offset < blockCount; offset++) {
            int block = order.get(offset);
            int start = leaders.get(block), end = leaders.get(block+1);
            starts[offset] = length;
            positions[start] = length;
            physicalOrder[block] = offset;
            boolean nextIsOriginal = block + 1 < blockCount && offset + 1 < blockCount && order.get(offset+1) == block + 1;
            jumps[block] = hasFallthrough(code, end) && !nextIsOriginal;
            length += end - start + (jumps[block] ? 1 : 0);
            if (length > Integer.MAX_VALUE / 9) throw new IOException("VM instruction layout too large");
        }
        starts[blockCount] = length;
        positions[positions.length-1] = length;

        int[] result = new int[length * 3];
        result[0] = GOTO;
        result[1] = positions[0];
        for (int block : order) {
            int start = leaders.get(block), end = leaders.get(block+1);
            int cursor = positions[start] * 3;
            System.arraycopy(code, start * 3, result, cursor, (end-start) * 3);
            for (int pc = start; pc < end; pc++, cursor += 3) {
                if (BasicBlocks.isJump(result[cursor])) result[cursor+1] = positions[result[cursor+1]];
            }
            if (jumps[block]) {
                result[cursor] = GOTO;
                result[cursor+1] = positions[end];
            }
        }
        int[] relocatedHandlers = relocateHandlers(handlers, from, to, positions, starts, physicalOrder, maxSplits);
        return new Layout(result, relocatedHandlers);
    }

    private static int[] relocateHandlers(int[] handlers, int[] from, int[] to, int[] positions,
                                          int[] starts, int[] physicalOrder, long maxSplits) throws IOException {
        if (handlers.length == 0) return handlers;
        if (maxSplits > Integer.MAX_VALUE / 16) throw new IOException("VM exception layout too large");
        int[] result = new int[(int) maxSplits * 4];
        int[] covered = new int[physicalOrder.length];
        int written = 0;
        // 原始记录按优先级依次展开。同一原始记录的片段互不相交。
        for (int entry = 0; entry < from.length; entry++) {
            int count = to[entry] - from[entry];
            for (int i = 0; i < count; i++) covered[i] = physicalOrder[from[entry] + i];
            Arrays.sort(covered, 0, count);
            for (int i = 0; i < count; i++) {
                int first = covered[i];
                int last = first;
                while (i+1 < count && covered[i+1] == last+1) last = covered[++i];
                result[written++] = starts[first];
                result[written++] = starts[last+1];
                result[written++] = positions[handlers[entry*4+2]];
                result[written++] = handlers[entry*4+3];
            }
        }
        return Arrays.copyOf(result, written);
    }

    private static List<Integer> shuffledOrder(int blockCount, SecureRandom random) {
        List<Integer> order = new ArrayList<>(blockCount);
        for (int block = 0; block < blockCount; block++) order.add(block);
        Collections.shuffle(order, random);
        for (int block = 0; block < blockCount; block++) {
            if (order.get(block) != block) return order;
        }
        Collections.swap(order, 0, blockCount - 1);
        return order;
    }

    private static void checkInput(int[] code, int[] handlers) throws IOException {
        if (code.length == 0 || code.length % 3 != 0 || handlers.length % 4 != 0)
            throw new IOException("Malformed VM layout input");
        int count = code.length / 3;
        for (int i = 0; i < code.length; i += 3) {
            if (BasicBlocks.isJump(code[i]) && (code[i+1] < 0 || code[i+1] >= count))
                throw new IOException("Invalid VM branch destination at " + i / 3);
        }
        for (int i = 0; i < handlers.length; i += 4) {
            if (handlers[i] < 0 || handlers[i] >= handlers[i+1] || handlers[i+1] > count
                    || handlers[i+2] < 0 || handlers[i+2] >= count || handlers[i+3] < -1) {
                throw new IOException("Invalid VM exception interval at " + i / 4);
            }
        }
    }

    private static boolean hasFallthrough(int[] code, int end) {
        int last = code[(end-1)*3];
        return last != GOTO && !BasicBlocks.isReturn(last);
    }

    static long instructionMask(long seed, int pc) {
        long mixed = seed + 0x9e3779b97f4a7c15L * (pc + 1L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }
}
