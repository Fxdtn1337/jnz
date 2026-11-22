package dev.jnz.frontend;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.security.SecureRandom;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;

/** 将支持的 JVM 指令降低到固定宽度指令，分支目标使用逻辑指令号。 */
final class Lowering implements Opcodes {
    private static final SecureRandom LINK_NAMES = new SecureRandom();
    static final class Unsupported extends Exception {
        Unsupported(String reason) { super(reason); }
    }

    static Program compile(String entry, String owner, MethodNode method, int id, int classId,
                           Set<String> occupied) throws Unsupported {
        if (method.attrs != null && !method.attrs.isEmpty()) throw new Unsupported("unknown method or Code attribute");
        method = inlineSubroutines(method);
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<BasicValue>(new BasicVerifier()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException e) {
            throw new Unsupported("bytecode verification failed: " + e.getMessage());
        }
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        Map<AbstractInsnNode, StackPermutation> shuffles = new IdentityHashMap<>();
        int size = 0;
        boolean switchUsed = false;
        int scratchCount = 0;
        int index = -1;
        for (AbstractInsnNode node : method.instructions) {
            index++;
            if (node instanceof LabelNode) labels.put((LabelNode) node, size);
            if (node.getOpcode() < 0) continue;
            if (StackPermutation.handles(node.getOpcode())) {
                StackPermutation shuffle = StackPermutation.decode(node.getOpcode(), frames[index]);
                shuffles.put(node, shuffle);
                scratchCount = Math.max(scratchCount, shuffle.scratchCount());
                size += shuffle.size();
                continue;
            }
            List<LabelNode> cases = switchLabels(node);
            if (cases == null) {
                size++;
            } else {
                switchUsed = true;
                scratchCount = Math.max(scratchCount, 1);
                size += 2 + 3 * cases.size();
            }
        }
        if (size == 0) throw new Unsupported("missing Code instructions");
        Lowering lowering = new Lowering(labels, size, method.maxLocals, owner, occupied);
        for (AbstractInsnNode node : method.instructions) {
            StackPermutation shuffle = shuffles.get(node);
            if (shuffle != null) lowering.emitShuffle(shuffle);
            else if (node.getOpcode() >= 0) lowering.emit(node);
        }
        int[] handlers = lowering.handlers(method.tryCatchBlocks);
        return new Program(id, classId, entry, owner, method.name, method.desc, (method.access & ACC_STATIC) != 0,
                method.maxLocals + scratchCount, method.maxStack + (switchUsed ? 1 : 0), lowering.code, lowering.links,
                handlers);
    }

    private static MethodNode inlineSubroutines(MethodNode method) throws Unsupported {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != JSR) continue;
            String[] exceptions = method.exceptions.toArray(new String[0]);
            MethodNode inlined = new MethodNode(ASM9, method.access, method.name, method.desc,
                    method.signature, exceptions);
            try {
                method.accept(new JSRInlinerAdapter(inlined, method.access, method.name, method.desc,
                        method.signature, exceptions));
                return inlined;
            } catch (IllegalArgumentException failure) {
                throw new Unsupported("legacy JSR subroutine could not be inlined: " + failure.getMessage());
            }
        }
        return method;
    }

    private final Map<LabelNode, Integer> labels;
    private final int[] code;
    private final int scratch;
    private final String owner, linkagePrefix;
    private final Set<String> occupied;
    private final List<Linkage> links = new ArrayList<>();
    private int cursor;

    private Lowering(Map<LabelNode, Integer> labels, int size, int scratch, String owner,
                     Set<String> occupied) {
        this.labels = labels;
        code = new int[size * 3];
        this.scratch = scratch;
        this.owner = owner;
        this.linkagePrefix = "$" + Long.toUnsignedString(LINK_NAMES.nextLong(), 36) + "$";
        this.occupied = occupied;
    }

    private int target(LabelNode label) throws Unsupported {
        Integer target = labels.get(label);
        if (target == null || target >= code.length / 3) throw new Unsupported("branch outside method");
        return target;
    }

    private int[] handlers(List<TryCatchBlockNode> handlers) throws Unsupported {
        int[] result = new int[handlers.size() * 4];
        int cursor = 0;
        for (TryCatchBlockNode handler : handlers) {
            int start = target(handler.start);
            Integer end = labels.get(handler.end);
            if (end == null || start >= end) throw new Unsupported("invalid exception table range");
            result[cursor++] = start;
            result[cursor++] = end;
            result[cursor++] = target(handler.handler);
            if (handler.type == null) {
                result[cursor++] = -1;
            } else {
                result[cursor++] = links.size();
                String name = linkagePrefix + links.size();
                while (occupied.contains(name)) name += "$";
                links.add(Linkage.classToken(name, handler.type, Linkage.JVM_OPERATION));
            }
        }
        return result;
    }

    private void put(int op, int a, int b) {
        code[cursor++] = op;
        code[cursor++] = a;
        code[cursor++] = b;
    }

    private static List<LabelNode> switchLabels(AbstractInsnNode node) {
        if (node instanceof LookupSwitchInsnNode) return ((LookupSwitchInsnNode) node).labels;
        if (node instanceof TableSwitchInsnNode) return ((TableSwitchInsnNode) node).labels;
        return null;
    }

    private void emitSwitch(AbstractInsnNode node) throws Unsupported {
        TableSwitchInsnNode table = node instanceof TableSwitchInsnNode ? (TableSwitchInsnNode) node : null;
        LookupSwitchInsnNode lookup = table == null ? (LookupSwitchInsnNode) node : null;
        List<LabelNode> cases = switchLabels(node);
        if (table != null && (long) table.max - table.min + 1 != cases.size())
            throw new Unsupported("inconsistent tableswitch range");
        if (lookup != null && lookup.keys.size() != cases.size())
            throw new Unsupported("inconsistent lookupswitch size");
        put(ISTORE, scratch, 0);
        for (int i = 0; i < cases.size(); i++) {
            int key = table == null ? lookup.keys.get(i) : table.min + i;
            if (lookup != null && i > 0 && lookup.keys.get(i - 1) >= key)
                throw new Unsupported("unordered lookupswitch keys");
            put(ILOAD, scratch, 0);
            put(BIPUSH, key, 0);
            put(IF_ICMPEQ, target(cases.get(i)), 0);
        }
        put(GOTO, target(table == null ? lookup.dflt : table.dflt), 0);
    }

    private void emitShuffle(StackPermutation shape) {
        if (shape.input == 0) {
            put(NOP, 0, 0);
        } else if (shape.output.length == 0) {
            for (int i = 0; i < shape.input; i++) put(POP, 0, 0);
        } else {
            // IR 的 LSTORE/LLOAD 原样复制一个 u64 值，临时槽位位于 JVM locals 之后。
            for (int i = 0; i < shape.input; i++) put(LSTORE, scratch + i, 0);
            for (int topIndex : shape.output) put(LLOAD, scratch + topIndex, 0);
        }
    }

    private void emit(AbstractInsnNode node) throws Unsupported {
        int op = node.getOpcode();
        switch (op) {
            case ICONST_M1: case ICONST_0: case ICONST_1: case ICONST_2:
            case ICONST_3: case ICONST_4: case ICONST_5:
                put(BIPUSH, op - ICONST_0, 0);
                return;
            case LCONST_0: case LCONST_1:
                put(LCONST_0, op - LCONST_0, 0);
                return;
            case FCONST_0: case FCONST_1: case FCONST_2:
                put(FCONST_0, Float.floatToRawIntBits(op - FCONST_0), 0);
                return;
            case DCONST_0: case DCONST_1:
                putWide(DCONST_0, Double.doubleToRawLongBits(op - DCONST_0));
                return;
            case BIPUSH: case SIPUSH:
                put(BIPUSH, ((IntInsnNode) node).operand, 0);
                return;
            case LDC:
                Object literal = ((LdcInsnNode) node).cst;
                if (literal instanceof Integer) {
                    put(BIPUSH, (Integer) literal, 0);
                } else if (literal instanceof Long) {
                    putWide(LCONST_0, (Long) literal);
                } else if (literal instanceof Float) {
                    put(FCONST_0, Float.floatToRawIntBits((Float) literal), 0);
                } else if (literal instanceof Double) {
                    putWide(DCONST_0, Double.doubleToRawLongBits((Double) literal));
                } else {
                    emitLink(node, "unsupported ldc constant " + literal.getClass().getSimpleName());
                }
                return;
            case ILOAD: case LLOAD: case FLOAD: case DLOAD: case ALOAD:
            case ISTORE: case LSTORE: case FSTORE: case DSTORE: case ASTORE:
                put(op, ((VarInsnNode) node).var, 0);
                return;
            case IINC:
                IincInsnNode increment = (IincInsnNode) node;
                put(op, increment.var, increment.incr);
                return;
            case IFEQ: case IFNE: case IFLT: case IFGE: case IFGT: case IFLE:
            case IF_ICMPEQ: case IF_ICMPNE: case IF_ICMPLT: case IF_ICMPGE:
            case IF_ICMPGT: case IF_ICMPLE: case IF_ACMPEQ: case IF_ACMPNE:
            case IFNULL: case IFNONNULL: case GOTO:
                put(op, target(((JumpInsnNode) node).label), 0);
                return;
            case TABLESWITCH: case LOOKUPSWITCH:
                emitSwitch(node);
                return;
            case NOP: case POP: case DUP: case ACONST_NULL:
            case IADD: case LADD: case ISUB: case LSUB: case IMUL: case LMUL:
            case IDIV: case LDIV: case IREM: case LREM: case INEG: case LNEG:
            case ISHL: case LSHL: case ISHR: case LSHR: case IUSHR: case LUSHR:
            case IAND: case LAND: case IOR: case LOR: case IXOR: case LXOR:
            case I2L: case L2I: case I2B: case I2C: case I2S: case LCMP:
            case I2F: case I2D: case L2F: case L2D:
            case F2I: case F2L: case F2D: case D2I: case D2L: case D2F:
            case FADD: case DADD: case FSUB: case DSUB: case FMUL: case DMUL:
            case FDIV: case DDIV: case FREM: case DREM: case FNEG: case DNEG:
            case FCMPL: case FCMPG: case DCMPL: case DCMPG:
            case IRETURN: case LRETURN: case FRETURN: case DRETURN: case ARETURN: case RETURN:
            case ATHROW: case MONITORENTER: case MONITOREXIT:
                put(op, 0, 0);
                return;
            default:
                emitLink(node, "unsupported JVM opcode " + op + " at IR instruction " + cursor / 3);
        }
    }

    private void emitLink(AbstractInsnNode node, String reason) throws Unsupported {
        Linkage link = Linkage.create(node, owner, linkagePrefix + links.size(), occupied);
        if (link == null) throw new Unsupported(reason);
        put(Linkage.OPCODE, links.size(), 0);
        links.add(link);
    }

    private void putWide(int opcode, long bits) {
        put(opcode, (int) bits, (int) (bits >>> 32));
    }
}
