package dev.jnz.protect;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

public final class FlowMask {
    public static final class Result {
        public final int edges, dispatchers;

        private Result(int edges, int dispatchers) {
            this.edges = edges;
            this.dispatchers = dispatchers;
        }
    }

    private FlowMask() {}

    public static int apply(MethodNode method, IntSupplier random) {
        return apply(method, random, "balanced").edges;
    }

    public static Result apply(MethodNode method, IntSupplier random, String profile) {
        if (profile.equals("off") || method.name.startsWith("<") || method.instructions.size() > 16000) {
            return new Result(0, 0);
        }
        boolean includeSwitches = profile.equals("strong");
        Map<List<Object>, Region> compatible = new LinkedHashMap<>();
        for (AbstractInsnNode op : method.instructions.toArray()) {
            if (op.getOpcode() == JSR || op.getOpcode() == RET) return new Result(0, 0);
            if (op instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) op;
                collect(compatible, jump.label, label -> jump.label = label);
            } else if (includeSwitches && op instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode table = (TableSwitchInsnNode) op;
                collect(compatible, table.dflt, label -> table.dflt = label);
                collectCases(compatible, table.labels);
            } else if (includeSwitches && op instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) op;
                collect(compatible, lookup.dflt, label -> lookup.dflt = label);
                collectCases(compatible, lookup.labels);
            }
        }
        int edges = 0;
        int dispatchers = 0;
        List<Region> regions = new ArrayList<>(compatible.values());
        Collections.shuffle(regions, new Random(random.getAsInt()));
        for (Region region : regions) {
            if (region.edges.size() >= 2) {
                edges += region.dispatch(method.instructions, random);
                dispatchers++;
            }
        }
        return new Result(edges, dispatchers);
    }

    private static void collectCases(Map<List<Object>, Region> groups, List<LabelNode> labels) {
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            collect(groups, labels.get(i), label -> labels.set(index, label));
        }
    }

    private static void collect(Map<List<Object>, Region> groups, LabelNode target, Consumer<LabelNode> edge) {
        FrameNode frame = frameAt(target);
        if (frame == null || frame.type != F_NEW || (frame.stack != null && !frame.stack.isEmpty())) return;
        List<Object> locals = frame.local == null ? Collections.emptyList() : frame.local;
        for (Object value : locals) {
            if (value instanceof LabelNode || UNINITIALIZED_THIS.equals(value)) return;
        }
        groups.computeIfAbsent(locals, Region::new).edges.computeIfAbsent(target, label -> new ArrayList<>()).add(edge);
    }

    private static FrameNode frameAt(LabelNode target) {
        for (AbstractInsnNode node = target; node != null && node.getOpcode() < 0; node = node.getNext()) {
            if (node instanceof FrameNode) return (FrameNode) node;
        }
        return null;
    }

    private static final class Region {
        final Object[] locals;
        final Map<LabelNode, List<Consumer<LabelNode>>> edges = new LinkedHashMap<>();

        private Region(List<Object> locals) {
            this.locals = locals.toArray();
        }

        private int dispatch(InsnList instructions, IntSupplier random) {
            LabelNode dispatcher = new LabelNode();
            int key = random.getAsInt();
            if (key == 0) key = 1;
            int count = 0;
            Map<Integer, LabelNode> cases = new TreeMap<>();
            List<LabelNode> targets = new ArrayList<>(edges.keySet());
            Collections.shuffle(targets, new Random(random.getAsInt()));
            for (LabelNode target : targets) {
                int id = random.getAsInt();
                while (cases.containsKey(id)) id++;
                cases.put(id, target);

                LabelNode stub = new LabelNode();
                for (Consumer<LabelNode> edge : edges.get(target)) {
                    edge.accept(stub);
                    count++;
                }
                instructions.add(stub);
                instructions.add(new FrameNode(F_NEW, locals.length, locals, 0, null));
                instructions.add(new LdcInsnNode(id ^ key));
                instructions.add(new JumpInsnNode(GOTO, dispatcher));
            }
            instructions.add(dispatcher);
            instructions.add(new FrameNode(F_NEW, locals.length, locals, 1, new Object[]{INTEGER}));
            instructions.add(new LdcInsnNode(key));
            instructions.add(new InsnNode(IXOR));
            int[] caseKeys = cases.keySet().stream().mapToInt(Integer::intValue).toArray();
            LabelNode[] caseTargets = cases.values().toArray(new LabelNode[0]);
            instructions.add(new LookupSwitchInsnNode(caseTargets[0], caseKeys, caseTargets));
            return count;
        }
    }
}
