package dev.jnz.backend;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Compiles each generated Rust runner's fetch/execute/return/exception graph into a small control program. */
final class RustControlSource {
    private static final int FETCH = 0, EXECUTE = 1, ADVANCE = 2, RECOVER = 3, FINISH = 4;
    private RustControlSource() {}

    static void emit(StringBuilder out, int id, SecureRandom random) {
        List<Integer> order = Arrays.asList(FETCH, EXECUTE, ADVANCE, RECOVER, FINISH);
        Collections.shuffle(order, random);
        int[] positions = new int[order.size()];
        for (int index = 0; index < order.size(); index++) positions[order.get(index)] = index;

        int[] tags = new int[order.size()];
        boolean[] used = new boolean[256];
        for (int role = 0; role < tags.length; role++) {
            int tag;
            do { tag = random.nextInt(256); } while (used[tag]);
            used[tag] = true;
            tags[role] = tag;
        }
        int seed = random.nextInt();
        out.append("static FLOW_").append(id).append(": control::Program = control::Program { seed: ")
                .append(Integer.toUnsignedString(seed)).append(", entry: ").append(positions[FETCH])
                .append(", tags: [");
        for (int tag : tags) out.append(tag).append(", ");
        out.append("], words: [");
        for (int index = 0; index < order.size(); index++) {
            int role = order.get(index);
            int word = tags[role];
            switch (role) {
                case FETCH: word |= positions[EXECUTE] << 8; break;
                case EXECUTE:
                    word |= positions[ADVANCE] << 8 | positions[FINISH] << 16 | positions[RECOVER] << 24;
                    break;
                case ADVANCE: case RECOVER: word |= positions[FETCH] << 8; break;
                case FINISH: break;
                default: throw new IllegalStateException("unknown Rust control state");
            }
            out.append(Integer.toUnsignedString(word ^ mask(seed, index))).append(", ");
        }
        out.append("] };\n")
                .append("unsafe extern \"C\" fn layered_").append(id)
                .append("(frame: *mut Frame) -> i32 {\n    control::run(frame, &FLOW_").append(id)
                .append(")\n}\n");
    }

    private static int mask(int seed, int index) {
        int value = seed + 0x7f4a7c15 * (index + 1);
        value ^= value >>> 15;
        value *= 0x85ebca6b;
        return value ^ (value >>> 13);
    }
}
