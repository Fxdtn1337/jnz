const std = @import("std");
const jni = @import("jni.zig");
const links = @import("links.zig");
const handlers = @import("handlers.zig");
const vm = @import("vm.zig");
const Cipher = std.crypto.aead.chacha_poly.ChaCha20Poly1305;
const allocator = std.heap.page_allocator;

const Bytes = extern struct {
    ptr: [*]const u8,
    len: usize,

    fn of(value: []const u8) Bytes {
        return .{ .ptr = value.ptr, .len = value.len };
    }

    fn slice(value: Bytes) []const u8 {
        return value.ptr[0..value.len];
    }
};

const Program = extern struct {
    id: u32,
    locals: u32,
    stack: u32,
    body: Bytes,
    links: Bytes,
    handlers: Bytes,
    key: *const [32]u8,
    mask: *const [256]u8,
    tags: *const [256]u8,
};

const Frame = extern struct {
    context: *links.Context,
    body: Bytes,
    handlers: Bytes,
    mask: *const [256]u8,
    tags: *const [256]u8,
    locals: [*]u64,
    local_count: usize,
    stack: [*]u64,
    stack_count: usize,
    value: u64 = 0,
};

comptime {
    if (@sizeOf(Program) != 88 or @sizeOf(Frame) != 96) @compileError("unexpected Rust bridge ABI");
}

const Run = *const fn (*Frame) callconv(.c) i32;

fn status(err: anyerror) i32 {
    return switch (err) {
        error.PendingException => -1,
        error.DivideByZero => -2,
        error.OutOfMemory => -3,
        error.AuthenticationFailed => -5,
        else => -4,
    };
}

fn failure(code: i32) anyerror {
    return switch (code) {
        -1 => error.PendingException,
        -2 => error.DivideByZero,
        -3 => error.OutOfMemory,
        -5 => error.AuthenticationFailed,
        else => error.InvalidCode,
    };
}

fn unseal(blob: []const u8, plain: []u8, aad: []const u8, key: *const [32]u8) !void {
    if (blob.len == 0 and plain.len == 0) return;
    if (blob.len < 28 or plain.len != blob.len - 28) return error.InvalidCode;
    try Cipher.decrypt(plain, blob[12 .. blob.len - 16], blob[blob.len - 16 ..][0..16].*,
        aad, blob[0..12].*, key.*);
}

fn invoke(env: jni.Env, program: *const Program, args: []const u64, run: Run) !u64 {
    if (args.len > program.locals) return error.InvalidCode;
    const body = program.body.slice();
    const table = program.handlers.slice();
    if (body.len != 0 and (body.len < 37 or (body.len - 28) % 9 != 0)) return error.InvalidCode;
    if (table.len != 0 and (table.len < 44 or (table.len - 28) % 16 != 0)) return error.InvalidCode;
    if (body.len == 0 and table.len != 0) return error.InvalidCode;

    var small_cells: [128]u64 = undefined;
    const cell_count = @as(usize, program.locals) + program.stack;
    const cells = if (cell_count <= small_cells.len) small_cells[0..cell_count] else try allocator.alloc(u64, cell_count);
    defer if (cell_count > small_cells.len) allocator.free(cells);
    defer std.crypto.secureZero(u64, cells);
    @memset(cells, 0);
    @memcpy(cells[0..args.len], args);

    var small_code: [1024]u8 = undefined;
    const code_count = body.len -| 28;
    const code = if (code_count <= small_code.len) small_code[0..code_count] else try allocator.alloc(u8, code_count);
    defer if (code_count > small_code.len) allocator.free(code);
    defer std.crypto.secureZero(u8, code);
    var aad: [12]u8 = undefined;
    std.mem.writeInt(u32, aad[0..4], program.id, .little);
    std.mem.writeInt(u32, aad[4..8], program.locals, .little);
    std.mem.writeInt(u32, aad[8..12], program.stack, .little);
    try unseal(body, code, &aad, program.key);

    var empty: [1]u8 = undefined;
    const ranges = if (table.len == 0) empty[0..0] else try allocator.alloc(u8, table.len - 28);
    defer if (ranges.len != 0) {
        std.crypto.secureZero(u8, ranges);
        allocator.free(ranges);
    };
    try unseal(table, ranges, aad[0..4], program.key);

    var context = try links.Context.open(env, program.id, program.links.slice(), program.key);
    defer context.close();
    var frame = Frame{
        .context = &context,
        .body = .of(code),
        .handlers = .of(ranges),
        .mask = program.mask,
        .tags = program.tags,
        .locals = cells.ptr,
        .local_count = program.locals,
        .stack = cells[program.locals..].ptr,
        .stack_count = program.stack,
    };
    const result = run(&frame);
    if (result != 0) return failure(result);
    return frame.value;
}

export fn jnz_rust_invoke(env: jni.Env, program: *const Program, args: [*]const u64, argc: usize, run: Run) callconv(.c) u64 {
    return invoke(env, program, args[0..argc], run) catch |err| {
        switch (err) {
            error.PendingException => {},
            error.DivideByZero => jni.raise(env, "java/lang/ArithmeticException", "/ by zero"),
            error.OutOfMemory => jni.raise(env, "java/lang/OutOfMemoryError", "VM frame"),
            error.AuthenticationFailed => jni.raise(env, "java/lang/SecurityException", "VM program authentication failed"),
            else => jni.raise(env, "java/lang/VerifyError", "invalid VM program"),
        }
        return 0;
    };
}

export fn jnz_rust_bind(env: jni.Env, owner: jni.Ref, id: u32, names: Bytes, key: *const [32]u8,
    thunks: [*]const *const anyopaque, thunk_count: usize) callconv(.c) void
{
    jni.registerPacked(env, owner, id, names.slice(), key, thunks[0..thunk_count]);
}

export fn jnz_rust_bind_error(env: jni.Env) callconv(.c) void {
    jni.raise(env, "java/lang/UnsatisfiedLinkError", "unknown binding id");
}

export fn jnz_rust_jni_step(context: *links.Context, op: u8, x: u32, y: u32, locals: [*]u64, local_count: usize,
    stack: [*]u64, stack_count: usize, used: *usize, pc: *usize, value: *u64) callconv(.c) i32
{
    var state = vm.Stack{ .cells = stack[0..stack_count], .len = used.* };
    defer used.* = state.len;
    if (vm.step(context, op, x, y, locals[0..local_count], &state, pc) catch |err| return status(err)) |result| {
        value.* = result;
        return 1;
    }
    return 0;
}

export fn jnz_rust_exception(context: *links.Context, table: [*]const u8, table_size: usize, current: usize,
    error_code: i32, locals: [*]u64, local_count: usize, target: *usize, throwable: *u64) callconv(.c) i32
{
    const routed = handlers.route(context, table[0..table_size], current, failure(error_code)) catch |err| return status(err);
    context.remember(routed.throwable, locals[0..local_count], &.{routed.throwable}) catch |err| return status(err);
    target.* = routed.pc;
    throwable.* = routed.throwable;
    return 0;
}
