package dev.jnz.backend;

import dev.jnz.frontend.Program;
import java.util.List;

/** 按基本块生成 Zig；指令号和两个操作数成为调用点常量，编译器直接展开运算。 */
final class DirectSource {
    private DirectSource() {}

    static void emit(StringBuilder out, Program p) {
        List<Integer> blocks = BasicBlocks.leaders(p.code);
        out.append("fn d").append(p.id)
           .append("(context: vm.Context, locals: []u64, stack: *vm.Stack) !u64 {\n    var pc: usize = 0;\n    while (true) switch (pc) {\n");
        for (int block = 0; block + 1 < blocks.size(); block++) {
            int start = blocks.get(block), end = blocks.get(block+1);
            out.append("        ").append(start).append(" => {\n            pc = ").append(end).append(";\n");
            for (int i = start; i < end; i++) {
                int j = 3*i;
                out.append("            if (try vm.step(context, ").append(p.code[j]).append(", ")
                   .append(Integer.toUnsignedString(p.code[j+1])).append(", ")
                   .append(Integer.toUnsignedString(p.code[j+2])).append(", locals, stack, &pc)) |value| return value;\n");
            }
            out.append("        },\n");
        }
        out.append("        else => return error.InvalidCode,\n    };\n}\n");
    }
}
