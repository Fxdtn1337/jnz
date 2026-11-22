const std = @import("std");
const jni = @import("jni.zig");
const links = @import("links.zig");

pub const Target = struct {
    pc: usize,
    throwable: u64,
};

const record_bytes = 16;
const any_class = std.math.maxInt(u32);

fn drop(env: jni.Env, reference: jni.Ref) void {
    const call = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) void, env, 23);
    call(env, reference);
}

fn throw(env: jni.Env, throwable: jni.Ref) anyerror {
    const call = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) i32, env, 13);
    if (call(env, throwable) != 0 and !jni.hasException(env)) return error.InvalidCode;
    return error.PendingException;
}

fn nullPointer(env: jni.Env) void {
    const find = jni.entry(*const fn (jni.Env, [*:0]const u8) callconv(.c) jni.Ref, env, 6);
    const publish = jni.entry(*const fn (jni.Env, jni.Ref, ?[*:0]const u8) callconv(.c) i32, env, 14);
    const class = find(env, "java/lang/NullPointerException") orelse return;
    defer drop(env, class);
    _ = publish(env, class, null);
}

pub fn throwReference(env: jni.Env, bits: u64) anyerror {
    if (bits == 0) {
        nullPointer(env);
        return error.PendingException;
    }
    return throw(env, links.asRef(bits));
}

pub fn monitor(env: jni.Env, bits: u64, acquire: bool) !void {
    if (bits == 0) {
        nullPointer(env);
        return error.PendingException;
    }
    const slot: usize = if (acquire) 217 else 218;
    const call = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) i32, env, slot);
    if (call(env, links.asRef(bits)) != 0) {
        if (jni.hasException(env)) return error.PendingException;
        return error.InvalidCode;
    }
}

fn isInstance(context: *links.Context, link: u32, throwable: jni.Ref) !bool {
    const operation = try context.lookup(link);
    if (operation.params.len != 0 or operation.result != 'L') return error.InvalidCode;
    const bits = (try context.invoke(operation, &.{})) orelse return error.InvalidCode;
    const class = links.asRef(bits) orelse return error.InvalidCode;
    defer drop(context.env, class);
    const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref) callconv(.c) u8,
        context.env, 32);
    return call(context.env, throwable, class) != 0;
}

/// Records are in original exception-table order: start, end, target, catch-class link.
/// The last field is 0xffffffff for a catch-all; other values reference a Class literal.
pub fn route(context: *links.Context, table: []const u8, pc: usize, failure: anyerror) !Target {
    if (table.len == 0) return failure;
    if (table.len % record_bytes != 0) return error.InvalidCode;
    switch (failure) {
        error.PendingException => {},
        error.DivideByZero => jni.raise(context.env, "java/lang/ArithmeticException", "/ by zero"),
        error.NullThrow => nullPointer(context.env),
        else => return failure,
    }

    const env = context.env;
    const occurred = jni.entry(*const fn (jni.Env) callconv(.c) jni.Ref, env, 15);
    const clear = jni.entry(*const fn (jni.Env) callconv(.c) void, env, 17);
    const throwable = occurred(env) orelse return error.InvalidCode;
    errdefer drop(env, throwable);
    clear(env);
    var offset: usize = 0;
    while (offset < table.len) : (offset += record_bytes) {
        const record = table[offset..][0..record_bytes];
        const start = std.mem.readInt(u32, record[0..4], .little);
        const end = std.mem.readInt(u32, record[4..8], .little);
        if (start >= end) return error.InvalidCode;
        if (pc < start or pc >= end) continue;

        const link = std.mem.readInt(u32, record[12..16], .little);
        if (link == any_class or try isInstance(context, link, throwable)) {
            return .{
                .pc = std.mem.readInt(u32, record[8..12], .little),
                .throwable = links.fromRef(throwable),
            };
        }
    }
    return throw(env, throwable);
}
