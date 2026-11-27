package dev.jnz.frontend;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;

/** New pools discard unreachable symbols. Preserve indices where ASM cannot prove that it is safe. */
final class ConstantPoolSafety {
    private ConstantPoolSafety() {}

    static boolean canRebuild(ClassReader reader, ClassNode owner) {
        if (owner.attrs != null && !owner.attrs.isEmpty()) return false;
        for (FieldNode field : owner.fields)
            if (field.attrs != null && !field.attrs.isEmpty()) return false;
        for (MethodNode method : owner.methods)
            if (method.attrs != null && !method.attrs.isEmpty()) return false;
        if (owner.recordComponents != null) for (RecordComponentNode component : owner.recordComponents)
            if (component.attrs != null && !component.attrs.isEmpty()) return false;

        // Distinct but structurally identical CONSTANT_Dynamic entries have separate resolution state.
        // ASM coalesces them when it builds a fresh pool.
        Set<ConstantDynamic> dynamic = new HashSet<>();
        char[] scratch = new char[reader.getMaxStringLength()];
        for (int id = 1; id < reader.getItemCount(); id++) {
            int offset = reader.getItem(id);
            if (offset == 0 || reader.readByte(offset - 1) != 17) continue;
            try {
                if (!dynamic.add((ConstantDynamic) reader.readConst(id, scratch))) return false;
            } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
                return false;
            }
        }
        return true;
    }
}
