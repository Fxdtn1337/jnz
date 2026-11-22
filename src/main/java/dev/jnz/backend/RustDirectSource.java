package dev.jnz.backend;

import dev.jnz.frontend.Linkage;
import dev.jnz.frontend.Program;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** 将直编方法的基本块和常用运算写成可读 Rust，保持 JVM 溢出与截断语义。 */
final class RustDirectSource {
    private static final String TAB = "                ";
    private static final String[] COMPARE = {" == ", " != ", " < ", " >= ", " > ", " <= "};
    private final StringBuilder out;

    private RustDirectSource(StringBuilder out) {
        this.out = out;
    }

    static void emit(StringBuilder out, Program program) {
        new RustDirectSource(out).method(program);
    }

    private void method(Program program) {
        List<Integer> leaders = BasicBlocks.leaders(program.code);
        out.append("unsafe extern \"C\" fn run_").append(program.id).append("(frame: *mut Frame) -> i32 {\n")
           .append("    vm::run_direct(frame, direct_").append(program.id).append(")\n}\n")
           .append("fn direct_").append(program.id).append("(vm: &mut Vm<'_>) -> Result<u64, i32> {\n")
           .append("    loop {\n        match vm.pc {\n");
        for (int block = 0; block + 1 < leaders.size(); block++) {
            int start = leaders.get(block), end = leaders.get(block + 1);
            out.append("            ").append(start).append(" => {\n");
            line("vm.pc = " + end + ";");
            for (int pc = start; pc < end; pc++) {
                int index = pc * 3;
                instruction(program.code[index], program.code[index + 1], program.code[index + 2]);
            }
            out.append("            }\n");
        }
        out.append("            _ => return Err(vm::INVALID),\n        }\n    }\n}\n");
    }

    private void instruction(int op, int x, int y) {
        switch (op) {
            case NOP: return;
            case ACONST_NULL: push("0"); return;
            case BIPUSH: push(x + "i32 as u32 as u64"); return;
            case FCONST_0: push("0x" + Integer.toHexString(x) + "_u64"); return;
            case LCONST_0: push((((long)y << 32) | (x & 0xffff_ffffL)) + "i64 as u64"); return;
            case DCONST_0: push("0x" + Long.toHexString(((long)y << 32) | (x & 0xffff_ffffL)) + "_u64"); return;
            case ILOAD: case LLOAD: case FLOAD: case DLOAD: case ALOAD:
                line("vm.load(" + x + ")?;");
                return;
            case ISTORE: case LSTORE: case FSTORE: case DSTORE: case ASTORE:
                line("vm.store(" + x + ")?;");
                return;
            case IINC:
                line("vm.increment(" + x + ", " + y + ")?;");
                return;
            case POP:
                line("vm.pop()?;");
                return;
            case DUP:
                line("let value = vm.pop()?;");
                push("value");
                push("value");
                return;
            case IADD: case LADD: integer(op, "wrapping_add", null); return;
            case ISUB: case LSUB: integer(op, "wrapping_sub", null); return;
            case IMUL: case LMUL: integer(op, "wrapping_mul", null); return;
            case IDIV: case LDIV: integer(op, "wrapping_div", null); return;
            case IREM: case LREM: integer(op, "wrapping_rem", null); return;
            case ISHL: case LSHL: integer(op, "wrapping_shl", null); return;
            case ISHR: case LSHR: case IUSHR: case LUSHR: integer(op, "wrapping_shr", null); return;
            case IAND: case LAND: integer(op, null, "&"); return;
            case IOR: case LOR: integer(op, null, "|"); return;
            case IXOR: case LXOR: integer(op, null, "^"); return;
            case FADD: case DADD: floating(op, "+"); return;
            case FSUB: case DSUB: floating(op, "-"); return;
            case FMUL: case DMUL: floating(op, "*"); return;
            case FDIV: case DDIV: floating(op, "/"); return;
            case FREM: case DREM: floating(op, "%"); return;
            case INEG: cast("(value as i32).wrapping_neg() as u32 as u64"); return;
            case LNEG: cast("(value as i64).wrapping_neg() as u64"); return;
            case FNEG: cast("((value as u32) ^ (1 << 31)) as u64"); return;
            case DNEG: cast("value ^ (1 << 63)"); return;
            case I2L: cast("value as i32 as i64 as u64"); return;
            case I2F: cast("(value as i32 as f32).to_bits() as u64"); return;
            case I2D: cast("(value as i32 as f64).to_bits()"); return;
            case L2I: cast("value as u32 as u64"); return;
            case L2F: cast("(value as i64 as f32).to_bits() as u64"); return;
            case L2D: cast("(value as i64 as f64).to_bits()"); return;
            case F2I: cast("f32::from_bits(value as u32) as i32 as u32 as u64"); return;
            case F2L: cast("f32::from_bits(value as u32) as i64 as u64"); return;
            case F2D: cast("(f32::from_bits(value as u32) as f64).to_bits()"); return;
            case D2I: cast("f64::from_bits(value) as i32 as u32 as u64"); return;
            case D2L: cast("f64::from_bits(value) as i64 as u64"); return;
            case D2F: cast("(f64::from_bits(value) as f32).to_bits() as u64"); return;
            case I2B: cast("value as i8 as i32 as u32 as u64"); return;
            case I2C: cast("value as u16 as u64"); return;
            case I2S: cast("value as i16 as i32 as u32 as u64"); return;
            case LCMP:
                line("let right = vm.pop()? as i64;");
                line("let left = vm.pop()? as i64;");
                push("vm::comparison(Some(left.cmp(&right)), 0)");
                return;
            case FCMPL: case FCMPG: case DCMPL: case DCMPG:
                boolean wide = op >= DCMPL;
                String decode = wide ? "f64::from_bits(vm.pop()?)" : "f32::from_bits(vm.pop()? as u32)";
                line("let right = " + decode + ";");
                line("let left = " + decode + ";");
                push("vm::comparison(left.partial_cmp(&right), " + op + ")");
                return;
            case IFEQ: case IFNE: case IFLT: case IFGE: case IFGT: case IFLE:
                branch("(vm.pop()? as i32)" + COMPARE[op - IFEQ] + "0", x);
                return;
            case IF_ICMPEQ: case IF_ICMPNE: case IF_ICMPLT: case IF_ICMPGE: case IF_ICMPGT: case IF_ICMPLE:
                line("let right = vm.pop()? as i32;");
                branch("(vm.pop()? as i32)" + COMPARE[op - IF_ICMPEQ] + "right", x);
                return;
            case IFNULL: case IFNONNULL:
                branch("vm.pop()?" + (op == IFNULL ? " == " : " != ") + "0", x);
                return;
            case IF_ACMPEQ: case IF_ACMPNE:
                line("vm.compare_references(" + (op == IF_ACMPEQ) + ", " + x + ")?;");
                return;
            case GOTO:
                line("vm.pc = " + x + ";");
                return;
            case IRETURN: case LRETURN: case FRETURN: case DRETURN: case ARETURN:
                line("return vm.pop();");
                return;
            case RETURN:
                line("return Ok(0);");
                return;
            case ATHROW:
                line("return vm.throw_reference();");
                return;
            case MONITORENTER: case MONITOREXIT:
                line("vm.monitor(" + (op == MONITORENTER) + ")?;");
                return;
            case Linkage.OPCODE:
                line("vm.java_operation(" + x + ")?;");
                return;
            default:
                line("if let Some(value) = vm.step(" + op + ", " + Integer.toUnsignedString(x) + ", "
                        + Integer.toUnsignedString(y) + ")? { return Ok(value); }");
        }
    }

    private void integer(int op, String method, String operator) {
        boolean wide = (op & 1) != 0;
        boolean shift = op >= ISHL && op <= LUSHR;
        boolean logical = op == IUSHR || op == LUSHR;
        String type = (logical ? "u" : "i") + (wide ? "64" : "32");
        line("let right = vm.pop()? as " + (shift ? "u32" : type) + ";");
        line("let left = vm.pop()? as " + type + ";");
        if (op == IDIV || op == LDIV || op == IREM || op == LREM)
            line("if right == 0 { return Err(vm::DIVIDE_BY_ZERO); }");
        String result = method == null ? "(left " + operator + " right)" : "left." + method + "(right)";
        push(result + (wide ? " as u64" : " as u32 as u64"));
    }

    private void floating(int op, String operator) {
        boolean wide = (op & 1) != 0;
        String decode = wide ? "f64::from_bits(vm.pop()?)" : "f32::from_bits(vm.pop()? as u32)";
        line("let right = " + decode + ";");
        line("let left = " + decode + ";");
        push("(left " + operator + " right).to_bits()" + (wide ? "" : " as u64"));
    }

    private void cast(String value) {
        line("let value = vm.pop()?;");
        push(value);
    }

    private void branch(String test, int target) {
        line("if " + test + " { vm.pc = " + target + "; }");
    }

    private void push(String expression) {
        line("vm.push(" + expression + ")?;");
    }

    private void line(String text) {
        out.append(TAB).append(text).append('\n');
    }
}
