const std = @import("std");
const jni = @import("jni.zig");
const links = @import("links.zig");
const handlers = @import("handlers.zig");
const Cipher = std.crypto.aead.chacha_poly.ChaCha20Poly1305;
pub const asRef = links.asRef;
pub const fromRef = links.fromRef;
pub const Context = *links.Context;

pub const Program = struct {
    id: u32,
    locals: u32,
    stack: u32,
    // 12-byte nonce | 9-byte encrypted instructions | 16-byte tag
    blob: []const u8,
    links: []const u8,
    handlers: []const u8,
};

pub const Decode = struct {
    mask: *const volatile [256]u8,
    values: *const volatile [256]u8,

    fn get(self: Decode, raw: u8) u8 {
        return self.mask[raw] ^ self.values[raw];
    }
};

pub const Stack = struct {
    cells: []u64,
    len: usize = 0,

    fn push(self: *Stack, value: u64) !void {
        if (self.len >= self.cells.len) return error.InvalidCode;
        self.cells[self.len] = value;
        self.len += 1;
    }
    fn pop(self: *Stack) !u64 {
        if (self.len == 0) return error.InvalidCode;
        self.len -= 1;
        return self.cells[self.len];
    }
};

fn int32(value: u64) i32 {
    return @bitCast(@as(u32, @truncate(value)));
}
fn int64(value: u64) i64 {
    return @bitCast(value);
}
pub fn fromI32(value: i32) u64 {
    return @as(u32, @bitCast(value));
}
pub fn fromI64(value: i64) u64 {
    return @bitCast(value);
}
pub fn asInt(comptime T: type, value: u64) T {
    return @bitCast(@as(@Int(.unsigned, @bitSizeOf(T)), @truncate(value)));
}

pub fn asFloat(comptime T: type, value: u64) T {
    return @bitCast(@as(@Int(.unsigned, @bitSizeOf(T)), @truncate(value)));
}
pub fn fromFloat(value: anytype) u64 {
    return @as(@Int(.unsigned, @bitSizeOf(@TypeOf(value))), @bitCast(value));
}

fn floating(comptime T: type, op: u8, a: u64, b: u64) u64 {
    @setFloatMode(.strict);
    const x = asFloat(T, a);
    const y = asFloat(T, b);
    return switch (op) {
        98, 99 => fromFloat(x + y),
        102, 103 => fromFloat(x - y),
        106, 107 => fromFloat(x * y),
        110, 111 => fromFloat(x / y),
        114, 115 => fromFloat(floatRemainder(T, x, y)),
        149...152 => fromI32(if (x < y) -1 else if (x > y) 1 else if (x == y) 0 else if (op == 149 or op == 151) -1 else 1),
        else => unreachable,
    };
}

fn floatRemainder(comptime T: type, x: T, y: T) T {
    if (std.math.isNan(x) or std.math.isNan(y) or std.math.isInf(x) or y == 0) return std.math.nan(T);
    if (std.math.isInf(y) or x == 0) return x;
    return @rem(x, y);
}

fn floatToInt(comptime T: type, value: anytype) T {
    const F = @TypeOf(value);
    if (std.math.isNan(value)) return 0;
    if (value >= @as(F, @floatFromInt(std.math.maxInt(T)))) return std.math.maxInt(T);
    if (value <= @as(F, @floatFromInt(std.math.minInt(T)))) return std.math.minInt(T);
    return @intFromFloat(value);
}

fn signedDiv(comptime T: type, a: T, b: T, remainder: bool) !T {
    if (b == 0) return error.DivideByZero;
    if (a == std.math.minInt(T) and b == -1) return if (remainder) 0 else a;
    return if (remainder) @rem(a, b) else @divTrunc(a, b);
}

fn compare(op: u8, a: i32, b: i32) bool {
    return switch (op) {
        0 => a == b,
        1 => a != b,
        2 => a < b,
        3 => a >= b,
        4 => a > b,
        5 => a <= b,
        else => unreachable,
    };
}

fn binary(op: u8, a: u64, b: u64) !u64 {
    const ai = int32(a);
    const bi = int32(b);
    const al = int64(a);
    const bl = int64(b);
    const si: u5 = @truncate(b);
    const sl: u6 = @truncate(b);
    return switch (op) {
        96 => fromI32(ai +% bi),
        97 => a +% b,
        100 => fromI32(ai -% bi),
        101 => a -% b,
        104 => fromI32(ai *% bi),
        105 => a *% b,
        108 => fromI32(try signedDiv(i32, ai, bi, false)),
        109 => fromI64(try signedDiv(i64, al, bl, false)),
        112 => fromI32(try signedDiv(i32, ai, bi, true)),
        113 => fromI64(try signedDiv(i64, al, bl, true)),
        120 => fromI32(ai << si),
        121 => a << sl,
        122 => fromI32(ai >> si),
        123 => fromI64(al >> sl),
        124 => @as(u32, @bitCast(ai)) >> si,
        125 => a >> sl,
        126 => fromI32(ai & bi),
        127 => a & b,
        128 => fromI32(ai | bi),
        129 => a | b,
        130 => fromI32(ai ^ bi),
        131 => a ^ b,
        148 => fromI32(if (al < bl) -1 else if (al > bl) 1 else 0),
        else => error.InvalidCode,
    };
}

fn interpret(context: Context, code: []const u8, table: []const u8, decode: Decode, locals: []u64, storage: []u64) !u64 {
    var stack = Stack{ .cells = storage };
    var pc: usize = 0;
    while (pc < code.len / 9) {
        const ins = code[pc * 9 ..][0..9];
        const op = decode.get(ins[0]);
        const x = std.mem.readInt(u32, ins[1..5], .little);
        const y = std.mem.readInt(u32, ins[5..9], .little);
        const current = pc;
        pc += 1;
        const result = step(context, op, x, y, locals, &stack, &pc) catch |failure| {
            const target = try handlers.route(context, table, current, failure);
            stack.len = 0;
            try stack.push(target.throwable);
            try context.remember(target.throwable, locals, stack.cells[0..stack.len]);
            pc = target.pc;
            continue;
        };
        if (result) |value| return value;
    }
    return error.InvalidCode;
}

pub inline fn step(context: Context, op: u8, x: u32, y: u32, locals: []u64, stack: *Stack, pc: *usize) !?u64 {
    switch (op) {
        0 => {},
        1 => try stack.push(0),
        9, 14 => try stack.push((@as(u64, y) << 32) | x),
        11, 16 => try stack.push(x),
        21...25, 54...58, 132 => {
            if (x >= locals.len) return error.InvalidCode;
            switch (op) {
                21...25 => try stack.push(locals[x]),
                54...58 => locals[x] = try stack.pop(),
                132 => locals[x] = fromI32(int32(locals[x]) +% @as(i32, @bitCast(y))),
                else => unreachable,
            }
        },
        87 => _ = try stack.pop(),
        89 => {
            const v = try stack.pop();
            try stack.push(v);
            try stack.push(v);
        },
        96, 97, 100, 101, 104, 105, 108, 109, 112, 113, 120...131, 148 => {
            const b = try stack.pop();
            const a = try stack.pop();
            try stack.push(try binary(op, a, b));
        },
        98, 99, 102, 103, 106, 107, 110, 111, 114, 115, 149...152 => {
            const b = try stack.pop();
            const a = try stack.pop();
            const single = switch (op) {
                98, 102, 106, 110, 114, 149, 150 => true,
                else => false,
            };
            try stack.push(if (single) floating(f32, op, a, b) else floating(f64, op, a, b));
        },
        116...119, 133...147 => {
            const v = try stack.pop();
            try stack.push(switch (op) {
                116 => fromI32(-%int32(v)),
                117 => 0 -% v,
                118 => (v ^ (1 << 31)) & 0xffffffff,
                119 => v ^ (1 << 63),
                133 => fromI64(int32(v)),
                136 => fromI32(int32(v)),
                134 => fromFloat(@as(f32, @floatFromInt(int32(v)))),
                135 => fromFloat(@as(f64, @floatFromInt(int32(v)))),
                137 => fromFloat(@as(f32, @floatFromInt(int64(v)))),
                138 => fromFloat(@as(f64, @floatFromInt(int64(v)))),
                139 => fromI32(floatToInt(i32, asFloat(f32, v))),
                140 => fromI64(floatToInt(i64, asFloat(f32, v))),
                141 => fromFloat(@as(f64, @floatCast(asFloat(f32, v)))),
                142 => fromI32(floatToInt(i32, asFloat(f64, v))),
                143 => fromI64(floatToInt(i64, asFloat(f64, v))),
                144 => fromFloat(@as(f32, @floatCast(asFloat(f64, v)))),
                145 => fromI32(asInt(i8, v)),
                146 => @as(u16, @truncate(v)),
                147 => fromI32(asInt(i16, v)),
                else => unreachable,
            });
        },
        153...158 => if (compare(op - 153, int32(try stack.pop()), 0)) {
            pc.* = x;
        },
        159...164 => {
            const b = int32(try stack.pop());
            if (compare(op - 159, int32(try stack.pop()), b)) pc.* = x;
        },
        165...166 => {
            const b = try stack.pop();
            const a = try stack.pop();
            if (links.same(context.env, a, b) == (op == 165)) pc.* = x;
        },
        167 => pc.* = x,
        172...176 => return try stack.pop(),
        177 => return 0,
        191 => return handlers.throwReference(context.env, try stack.pop()),
        194 => {
            const bits = try stack.pop();
            try context.reserveMonitor();
            try handlers.monitor(context.env, bits, true);
            context.monitors.appendAssumeCapacity(bits);
        },
        195 => {
            const bits = try stack.pop();
            if (bits == 0) return handlers.throwReference(context.env, 0);
            const index = context.monitorIndex(bits) orelse {
                jni.raise(context.env, "java/lang/IllegalMonitorStateException", "monitor not owned by this frame");
                return error.PendingException;
            };
            try handlers.monitor(context.env, bits, false);
            _ = context.monitors.orderedRemove(index);
        },
        198...199 => {
            if (((try stack.pop()) == 0) == (op == 198)) pc.* = x;
        },
        254 => {
            const operation = try context.lookup(x);
            if (operation.params.len > stack.len) return error.InvalidCode;
            const args = stack.cells[stack.len - operation.params.len .. stack.len];
            stack.len -= operation.params.len;
            if (try context.invoke(operation, args)) |value| {
                try stack.push(value);
                if (operation.result == 'L') try context.remember(value, locals, stack.cells[0..stack.len]);
            }
        },
        else => return error.InvalidCode,
    }
    return null;
}

const Frame = struct {
    cells: []u64,
    local_count: usize,
    allocated: bool,

    fn init(small: []u64, local_count: u32, stack_count: u32, args: []const u64) !Frame {
        if (args.len > local_count) return error.InvalidCode;
        const count = @as(usize, local_count) + stack_count;
        const cells = if (count <= small.len) small[0..count] else try std.heap.page_allocator.alloc(u64, count);
        @memset(cells[0..local_count], 0);
        @memcpy(cells[0..args.len], args);
        return .{ .cells = cells, .local_count = local_count, .allocated = count > small.len };
    }
    fn deinit(frame: Frame) void {
        std.crypto.secureZero(u64, frame.cells);
        if (frame.allocated) std.heap.page_allocator.free(frame.cells);
    }
    fn locals(frame: Frame) []u64 {
        return frame.cells[0..frame.local_count];
    }
    fn stack(frame: Frame) []u64 {
        return frame.cells[frame.local_count..];
    }
};

fn execute(env: jni.Env, program: Program, key: *const [32]u8, decode: Decode, args: []const u64) !u64 {
    const blob = program.blob;
    if (blob.len < 37 or (blob.len - 28) % 9 != 0) return error.InvalidCode;
    // The common case stays on the calling thread's stack. Larger methods use the page allocator.
    var local_cells: [128]u64 = undefined;
    var local_bytes: [1024]u8 = undefined;
    const allocator = std.heap.page_allocator;
    const frame = try Frame.init(&local_cells, program.locals, program.stack, args);
    defer frame.deinit();
    const cipher = blob[12 .. blob.len - 16];
    const plain = if (cipher.len <= local_bytes.len) local_bytes[0..cipher.len] else try allocator.alloc(u8, cipher.len);
    defer if (cipher.len > local_bytes.len) allocator.free(plain);
    defer std.crypto.secureZero(u8, plain);
    var aad: [12]u8 = undefined;
    std.mem.writeInt(u32, aad[0..4], program.id, .little);
    std.mem.writeInt(u32, aad[4..8], program.locals, .little);
    std.mem.writeInt(u32, aad[8..12], program.stack, .little);
    try Cipher.decrypt(plain, cipher, blob[blob.len - 16 ..][0..16].*, &aad, blob[0..12].*, key.*);
    var context = try links.Context.open(env, program.id, program.links, key);
    defer context.close();
    const table = try readHandlers(program.handlers, program.id, key);
    defer if (table.len != 0) {
        std.crypto.secureZero(u8, table);
        allocator.free(table);
    };
    return interpret(&context, plain, table, decode, frame.locals(), frame.stack());
}

fn readHandlers(blob: []const u8, id: u32, key: *const [32]u8) ![]u8 {
    if (blob.len == 0) return &.{};
    if (blob.len < 44 or (blob.len - 28) % 16 != 0) return error.InvalidCode;
    const cipher = blob[12 .. blob.len - 16];
    const allocator = std.heap.page_allocator;
    const table = try allocator.alloc(u8, cipher.len);
    errdefer allocator.free(table);
    errdefer std.crypto.secureZero(u8, table);
    var aad: [4]u8 = undefined;
    std.mem.writeInt(u32, &aad, id, .little);
    try Cipher.decrypt(table, cipher, blob[blob.len - 16 ..][0..16].*, &aad, blob[0..12].*, key.*);
    return table;
}

pub fn invoke(env: jni.Env, program: Program, key: *const [32]u8, decode: Decode, args: []const u64) u64 {
    return execute(env, program, key, decode, args) catch |err| raiseError(env, err);
}

pub fn invokeDirect(env: jni.Env, id: u32, local_count: u32, stack_count: u32, metadata: []const u8,
    key: *const [32]u8, args: []const u64, comptime body: anytype) u64
{
    var small: [128]u64 = undefined;
    const frame = Frame.init(&small, local_count, stack_count, args) catch |err| return raiseError(env, err);
    defer frame.deinit();
    var stack = Stack{ .cells = frame.stack() };
    var context = links.Context.open(env, id, metadata, key) catch |err| return raiseError(env, err);
    defer context.close();
    return body(&context, frame.locals(), &stack) catch |err| raiseError(env, err);
}

fn raiseError(env: jni.Env, err: anyerror) u64 {
    switch (err) {
        error.PendingException => {},
        error.DivideByZero => jni.raise(env, "java/lang/ArithmeticException", "/ by zero"),
        error.OutOfMemory => jni.raise(env, "java/lang/OutOfMemoryError", "VM frame"),
        error.AuthenticationFailed => jni.raise(env, "java/lang/SecurityException", "VM program authentication failed"),
        else => jni.raise(env, "java/lang/VerifyError", "invalid VM program"),
    }
    return 0;
}
