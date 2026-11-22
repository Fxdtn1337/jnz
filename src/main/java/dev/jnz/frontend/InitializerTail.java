package dev.jnz.frontend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** 保留 JVM 规定的构造和 final 写入边界，将栈与局部变量可独立验证的后段移入 native。 */
final class InitializerTail implements Opcodes {
    final MethodNode wrapper;
    final List<Part> parts;
    final int retainedInstructions;

    static final class Part {
        final MethodNode helper;
        final int loweredInstructions;

        Part(MethodNode helper, int loweredInstructions) {
            this.helper = helper;
            this.loweredInstructions = loweredInstructions;
        }
    }

    private InitializerTail(MethodNode wrapper, List<Part> parts, int retainedInstructions) {
        this.wrapper = wrapper;
        this.parts = parts;
        this.retainedInstructions = retainedInstructions;
    }

    static InitializerTail plan(ClassNode owner, MethodNode method, String helperName, Set<String> occupied,
                                boolean moveFinalValues) throws Lowering.Unsupported {
        if (method.attrs != null && !method.attrs.isEmpty())
            throw new Lowering.Unsupported("initializer has an unknown method or Code attribute");

        boolean constructor = method.name.equals("<init>");
        AbstractInsnNode[] code = method.instructions.toArray();
        Frame<SourceValue>[] frames = frames(owner, method);
        int boundary = constructor ? initializingCall(owner, code, frames) + 1 : 0;
        List<Integer> stores = ownFinalStores(owner, code, boundary);
        if (moveFinalValues && !stores.isEmpty())
            return planFinalValues(owner, method, helperName, occupied, constructor, code, frames, boundary, stores);
        // JVM 入口必须自己写入 final 字段。安全切分失败时，保留截至最后一次 final 写入的全部代码。
        if (!stores.isEmpty()) boundary = stores.get(stores.size() - 1) + 1;

        if (!hasComputation(code, boundary))
            throw new Lowering.Unsupported("initializer has no movable computation after required JVM initialization");
        requireEntry(code, frames, boundary, method, constructor);
        Map<LabelNode, Integer> labelPositions = labels(code);
        requireClosedControlFlow(code, Collections.singletonList(boundary), labelPositions, method);
        MethodNode wrapper = copyMetadata(method);
        MethodNode helper = helper(method, constructor, helperName, Type.VOID_TYPE);
        copyCode(method, 0, boundary, code, labelPositions, wrapper, true);
        copyCode(method, boundary, code.length, code, labelPositions, helper, false);
        int local = appendCall(owner, method, wrapper, helper, constructor);
        wrapper.instructions.add(new InsnNode(RETURN));
        finishWrapper(method, wrapper, local);
        Part part = new Part(helper, count(code, boundary, code.length));
        return new InitializerTail(wrapper, Collections.singletonList(part), count(code, 0, boundary));
    }

    private static InitializerTail planFinalValues(ClassNode owner, MethodNode method, String name, Set<String> occupied,
                                                   boolean constructor, AbstractInsnNode[] code,
                                                   Frame<SourceValue>[] frames, int boundary, List<Integer> stores)
            throws Lowering.Unsupported {
        requireEntry(code, frames, boundary, method, constructor);
        List<Integer> cuts = new ArrayList<>();
        cuts.add(boundary);
        for (int store : stores) {
            cuts.add(store);
            cuts.add(store + 1);
        }
        Map<LabelNode, Integer> positions = labels(code);
        requireClosedControlFlow(code, cuts, positions, method);

        MethodNode wrapper = copyMetadata(method);
        copyCode(method, 0, boundary, code, positions, wrapper, true);
        List<Part> parts = new ArrayList<>();
        int start = boundary;
        int maxStack = 0;
        int lowered = 0;
        for (int index : stores) {
            FieldInsnNode store = (FieldInsnNode) code[index];
            boolean instance = store.getOpcode() == PUTFIELD;
            Type value = Type.getType(store.desc);
            Frame<SourceValue> entry = frames[start];
            Frame<SourceValue> atStore = frames[index];
            Frame<SourceValue> afterStore = index + 1 < frames.length ? frames[index + 1] : null;
            if (entry == null || entry.getStackSize() != 0 || atStore == null || afterStore == null
                    || afterStore.getStackSize() != 0 || atStore.getStackSize() != (instance ? 2 : 1)
                    || (instance && !fromThis(atStore.getStack(0))))
                throw new Lowering.Unsupported("final field computation is not an independent operand-stack region");
            for (int i = start; i < index; i++) {
                if (writtenLocal(code[i]) >= 0)
                    throw new Lowering.Unsupported("final field computation carries local writes across its boundary");
            }

            String generated = uniqueName(name, parts.size(), occupied);
            MethodNode helper = helper(method, constructor, generated, value);
            copyCode(method, start, index, code, positions, helper, false);
            if (instance && value.getSize() == 2) {
                helper.instructions.add(new InsnNode(DUP2_X1));
                helper.instructions.add(new InsnNode(POP2));
                helper.instructions.add(new InsnNode(POP));
                helper.maxStack = Math.max(helper.maxStack, 5);
            } else if (instance) {
                helper.instructions.add(new InsnNode(SWAP));
                helper.instructions.add(new InsnNode(POP));
            }
            helper.instructions.add(new InsnNode(value.getOpcode(IRETURN)));

            if (instance) wrapper.instructions.add(new VarInsnNode(ALOAD, 0));
            int callStack = appendCall(owner, method, wrapper, helper, constructor);
            maxStack = Math.max(maxStack, (instance ? 1 : 0) + Math.max(callStack, value.getSize()));
            wrapper.instructions.add(store.clone(Collections.<LabelNode, LabelNode>emptyMap()));
            int moved = count(code, start, index);
            parts.add(new Part(helper, moved));
            lowered += moved;
            start = index + 1;
        }
        if (hasComputation(code, start)) {
            MethodNode tail = helper(method, constructor, uniqueName(name, parts.size(), occupied), Type.VOID_TYPE);
            copyCode(method, start, code.length, code, positions, tail, false);
            maxStack = Math.max(maxStack, appendCall(owner, method, wrapper, tail, constructor));
            int moved = count(code, start, code.length);
            parts.add(new Part(tail, moved));
            lowered += moved;
        }
        wrapper.instructions.add(new InsnNode(RETURN));
        finishWrapper(method, wrapper, maxStack);
        return new InitializerTail(wrapper, parts, count(code, 0, code.length) - lowered);
    }

    private static void requireEntry(AbstractInsnNode[] code, Frame<SourceValue>[] frames, int boundary,
                                     MethodNode method, boolean constructor) throws Lowering.Unsupported {
        Frame<SourceValue> entry = boundary < frames.length ? frames[boundary] : null;
        if (entry == null || entry.getStackSize() != 0)
            throw new Lowering.Unsupported("initializer tail does not start with an empty operand stack");
        int arguments = arguments(method.desc, constructor);
        for (int i = 0; i < boundary; i++) {
            int written = writtenLocal(code[i]);
            if (written >= 0 && written < arguments)
                throw new Lowering.Unsupported("initializer prefix changes a receiver or parameter local");
        }
    }

    private static MethodNode helper(MethodNode original, boolean receiver, String name, Type returnType) {
        MethodNode helper = new MethodNode(ASM9, ACC_PRIVATE | ACC_SYNTHETIC | (receiver ? 0 : ACC_STATIC),
                name, Type.getMethodDescriptor(returnType, Type.getArgumentTypes(original.desc)), null, null);
        helper.maxStack = original.maxStack;
        helper.maxLocals = original.maxLocals;
        return helper;
    }

    private static String uniqueName(String name, int part, Set<String> occupied) {
        String generated = name + "$" + part;
        while (occupied.contains(generated)) generated += "$";
        return generated;
    }

    private static int appendCall(ClassNode owner, MethodNode source, MethodNode wrapper, MethodNode helper,
                                  boolean constructor) {
        int local = 0;
        if (constructor) wrapper.instructions.add(new VarInsnNode(ALOAD, local++));
        for (Type argument : Type.getArgumentTypes(source.desc)) {
            wrapper.instructions.add(new VarInsnNode(argument.getOpcode(ILOAD), local));
            local += argument.getSize();
        }
        wrapper.instructions.add(new MethodInsnNode(constructor ? INVOKESPECIAL : INVOKESTATIC,
                owner.name, helper.name, helper.desc, false));
        return local;
    }

    private static void finishWrapper(MethodNode method, MethodNode wrapper, int maxStack) {
        wrapper.maxStack = Math.max(method.maxStack, maxStack);
        wrapper.maxLocals = method.maxLocals;
    }

    private static Frame<SourceValue>[] frames(ClassNode owner, MethodNode method) throws Lowering.Unsupported {
        try {
            return new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException error) {
            throw new Lowering.Unsupported("initializer analysis failed: " + error.getMessage());
        }
    }

    private static int initializingCall(ClassNode owner, AbstractInsnNode[] code, Frame<SourceValue>[] frames)
            throws Lowering.Unsupported {
        int selected = -1;
        for (int i = 0; i < code.length; i++) {
            if (code[i].getOpcode() != INVOKESPECIAL || frames[i] == null) continue;
            MethodInsnNode invoke = (MethodInsnNode) code[i];
            if (!invoke.name.equals("<init>") || !(invoke.owner.equals(owner.name) || invoke.owner.equals(owner.superName)))
                continue;
            Frame<SourceValue> frame = frames[i];
            int receiver = frame.getStackSize() - Type.getArgumentTypes(invoke.desc).length - 1;
            if (receiver < 0 || !fromThis(frame.getStack(receiver))) continue;
            if (selected >= 0) throw new Lowering.Unsupported("initializer has multiple receiver initialization paths");
            selected = i;
        }
        if (selected < 0) throw new Lowering.Unsupported("initializer receiver cannot be traced to a unique this/super call");
        return selected;
    }

    private static boolean fromThis(SourceValue value) {
        if (value.insns.isEmpty()) return false;
        for (AbstractInsnNode insn : value.insns)
            if (insn.getOpcode() != ALOAD || ((VarInsnNode) insn).var != 0) return false;
        return true;
    }

    private static List<Integer> ownFinalStores(ClassNode owner, AbstractInsnNode[] code, int start) {
        Set<String> finalFields = new HashSet<>();
        for (FieldNode field : owner.fields)
            if ((field.access & ACC_FINAL) != 0) finalFields.add(field.name + '\0' + field.desc);
        List<Integer> stores = new ArrayList<>();
        for (int i = start; i < code.length && !finalFields.isEmpty(); i++) {
            int opcode = code[i].getOpcode();
            if (opcode != PUTFIELD && opcode != PUTSTATIC) continue;
            FieldInsnNode field = (FieldInsnNode) code[i];
            if (field.owner.equals(owner.name) && finalFields.contains(field.name + '\0' + field.desc)) stores.add(i);
        }
        return stores;
    }

    private static boolean hasComputation(AbstractInsnNode[] code, int start) {
        for (int i = start; i < code.length; i++) {
            int opcode = code[i].getOpcode();
            if (opcode >= 0 && opcode != NOP && opcode != RETURN) return true;
        }
        return false;
    }

    private static int arguments(String descriptor, boolean receiver) {
        int size = receiver ? 1 : 0;
        for (Type arg : Type.getArgumentTypes(descriptor)) size += arg.getSize();
        return size;
    }

    private static int writtenLocal(AbstractInsnNode node) {
        if (node instanceof IincInsnNode) return ((IincInsnNode) node).var;
        if (node.getOpcode() >= ISTORE && node.getOpcode() <= ASTORE) return ((VarInsnNode) node).var;
        return -1;
    }

    private static Map<LabelNode, Integer> labels(AbstractInsnNode[] code) {
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (int i = 0; i < code.length; i++)
            if (code[i] instanceof LabelNode) labels.put((LabelNode) code[i], i);
        return labels;
    }

    private static void requireClosedControlFlow(AbstractInsnNode[] code, List<Integer> cuts,
                                                 Map<LabelNode, Integer> positions, MethodNode method)
            throws Lowering.Unsupported {
        int[] regions = new int[code.length];
        int region = 0;
        for (int i = 0; i < code.length; i++) {
            while (region < cuts.size() && i >= cuts.get(region)) region++;
            regions[i] = region;
        }
        for (int i = 0; i < code.length; i++) {
            AbstractInsnNode insn = code[i];
            int part = regions[i];
            if (insn instanceof JumpInsnNode) {
                requireSide(part, ((JumpInsnNode) insn).label, positions, regions);
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                requireSide(part, sw.dflt, positions, regions);
                for (LabelNode label : sw.labels) requireSide(part, label, positions, regions);
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                requireSide(part, sw.dflt, positions, regions);
                for (LabelNode label : sw.labels) requireSide(part, label, positions, regions);
            } else if (part == 0 && insn instanceof FrameNode) {
                FrameNode frame = (FrameNode) insn;
                if (frame.local != null) for (Object item : frame.local)
                    if (item instanceof LabelNode) requireSide(0, (LabelNode) item, positions, regions);
                if (frame.stack != null) for (Object item : frame.stack)
                    if (item instanceof LabelNode) requireSide(0, (LabelNode) item, positions, regions);
            }
        }
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            Integer start = positions.get(handler.start);
            if (start == null) throw new Lowering.Unsupported("initializer has an invalid exception start label");
            int part = regions[start];
            requireSide(part, handler.end, positions, regions);
            requireSide(part, handler.handler, positions, regions);
        }
    }

    private static void requireSide(int part, LabelNode target, Map<LabelNode, Integer> positions, int[] regions)
            throws Lowering.Unsupported {
        Integer position = positions.get(target);
        if (position == null || part != regions[position])
            throw new Lowering.Unsupported("branch, exception handler, or verifier frame crosses initializer tail boundary");
    }

    private static MethodNode copyMetadata(MethodNode source) {
        MethodNode target = new MethodNode(ASM9, source.access, source.name, source.desc,
                source.signature, source.exceptions.toArray(new String[0]));
        source.accept(target);
        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;
        return target;
    }

    private static void copyCode(MethodNode source, int start, int end, AbstractInsnNode[] code,
                                 Map<LabelNode, Integer> positions, MethodNode target, boolean keepFrames) {
        Map<LabelNode, LabelNode> localLabels = new HashMap<>();
        for (Map.Entry<LabelNode, Integer> label : positions.entrySet())
            if (within(label.getValue(), start, end)) localLabels.put(label.getKey(), new LabelNode());
        for (int i = start; i < end; i++) {
            AbstractInsnNode insn = code[i];
            if (!keepFrames && (insn instanceof FrameNode || insn instanceof LineNumberNode)) continue;
            if (insn instanceof LineNumberNode && !localLabels.containsKey(((LineNumberNode) insn).start)) continue;
            target.instructions.add(insn.clone(localLabels));
        }
        for (TryCatchBlockNode handler : source.tryCatchBlocks) {
            if (!localLabels.containsKey(handler.start)) continue;
            TryCatchBlockNode copy = new TryCatchBlockNode(localLabels.get(handler.start),
                    localLabels.get(handler.end), localLabels.get(handler.handler), handler.type);
            copy.visibleTypeAnnotations = handler.visibleTypeAnnotations;
            copy.invisibleTypeAnnotations = handler.invisibleTypeAnnotations;
            target.tryCatchBlocks.add(copy);
        }
    }

    private static boolean within(int index, int start, int end) {
        return index >= start && index < end;
    }

    private static int count(AbstractInsnNode[] code, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) if (code[i].getOpcode() >= 0) count++;
        return count;
    }
}
