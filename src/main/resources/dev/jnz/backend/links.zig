const std = @import("std");
const jni = @import("jni.zig");
const ops = @import("native_ops.zig");
const allocator = std.heap.page_allocator;
const Cipher = std.crypto.aead.chacha_poly.ChaCha20Poly1305;

const Value = extern union {
    z: u8,
    b: i8,
    c: u16,
    s: i16,
    i: i32,
    j: i64,
    f: f32,
    d: f64,
    l: jni.Ref,
};

pub const fromRef = ops.fromRef;
pub const asRef = ops.asRef;

pub fn same(env: jni.Env, a: u64, b: u64) bool {
    if (a == 0 or b == 0) return a == b;
    const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref) callconv(.c) u8, env, 24);
    return call(env, asRef(a), asRef(b)) != 0;
}

const cast = ops.cast;

fn arg(kind: u8, bits: u64) !Value {
    return switch (kind) {
        'Z' => .{ .z = @intCast(bits & 1) },
        'B' => .{ .b = cast(i8, bits) },
        'C' => .{ .c = cast(u16, bits) },
        'S' => .{ .s = cast(i16, bits) },
        'I' => .{ .i = cast(i32, bits) },
        'J' => .{ .j = cast(i64, bits) },
        'F' => .{ .f = cast(f32, bits) },
        'D' => .{ .d = cast(f64, bits) },
        'L' => .{ .l = asRef(bits) },
        else => error.InvalidCode,
    };
}

pub const Call = struct {
    name: [*:0]const u8,
    descriptor: [*:0]const u8,
    params: []const u8,
    result: u8,
    mode: u8,
};

pub const Context = struct {
    env: jni.Env,
    plain: []u8 = &.{},
    owner: jni.Ref = null,
    references: std.ArrayList(u64) = .empty,
    monitors: std.ArrayList(u64) = .empty,
    next_sweep: usize = 16,

    pub fn open(env: jni.Env, id: u32, blob: []const u8, key: *const [32]u8) !Context {
        if (blob.len == 0) return .{ .env = env };
        if (blob.len < 37) return error.InvalidCode;
        const cipher = blob[12 .. blob.len - 16];
        const bytes = try allocator.alloc(u8, cipher.len);
        errdefer allocator.free(bytes);
        errdefer std.crypto.secureZero(u8, bytes);
        var aad: [4]u8 = undefined;
        std.mem.writeInt(u32, &aad, id, .little);
        try Cipher.decrypt(bytes, cipher, blob[blob.len - 16 ..][0..16].*, &aad, blob[0..12].*, key.*);
        const n = std.mem.readInt(u32, bytes[0..4], .little);
        if (n == 0 or n > (bytes.len - 4) / 4) return error.InvalidCode;
        var offset = 4 + @as(usize, n) * 4;
        const name = try jni.takeString(bytes, &offset);
        const find = jni.entry(*const fn (jni.Env, [*:0]const u8) callconv(.c) jni.Ref, env, 6);
        const owner = find(env, name) orelse return error.PendingException;
        return .{ .env = env, .plain = bytes, .owner = owner };
    }

    pub fn close(self: *Context) void {
        // Valid bytecode exits its synchronized blocks. Release any remaining native monitors on VM failure.
        if (self.monitors.items.len > 0) {
            const release = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) i32, self.env, 218);
            var i = self.monitors.items.len;
            while (i > 0) {
                i -= 1;
                _ = release(self.env, asRef(self.monitors.items[i]));
            }
        }
        self.monitors.deinit(allocator);
        if (self.owner != null) {
            const drop = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) void, self.env, 23);
            drop(self.env, self.owner);
        }
        if (self.plain.len > 0) {
            std.crypto.secureZero(u8, self.plain);
            allocator.free(self.plain);
        }
        self.references.deinit(allocator);
    }

    pub fn reserveMonitor(self: *Context) !void {
        try self.monitors.ensureUnusedCapacity(allocator, 1);
    }

    pub fn monitorIndex(self: *const Context, bits: u64) ?usize {
        var i = self.monitors.items.len;
        while (i > 0) {
            i -= 1;
            if (same(self.env, bits, self.monitors.items[i])) return i;
        }
        return null;
    }

    pub fn lookup(self: *const Context, index: u32) !Call {
        const bytes = self.plain;
        if (bytes.len < 4 or index >= std.mem.readInt(u32, bytes[0..4], .little))
            return error.InvalidCode;
        var offset: usize = std.mem.readInt(u32, bytes[4 + @as(usize, index) * 4 ..][0..4], .little);
        const name = try jni.takeString(bytes, &offset);
        const descriptor = try jni.takeString(bytes, &offset);
        if (bytes.len - offset < 3) return error.InvalidCode;
        const count = bytes[offset + 1];
        if (bytes.len - offset - 3 < count) return error.InvalidCode;
        switch (bytes[offset]) {
            0...4, 46...53, 79...86, 178...184, 188, 190 => {},
            else => return error.InvalidCode,
        }
        return .{ .name = name, .descriptor = descriptor, .mode = bytes[offset], .result = bytes[offset + 2], .params = bytes[offset + 3 ..][0..count] };
    }

    fn typedCall(self: *const Context, comptime T: type, mode: u8, index: usize, target: jni.Ref, id: jni.Ref, args: [*]const Value) T {
        if (mode == 183) {
            const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, jni.Ref, [*]const Value) callconv(.c) T, self.env, index);
            return call(self.env, target, self.owner, id, args);
        }
        const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, [*]const Value) callconv(.c) T, self.env, index);
        return call(self.env, target, id, args);
    }

    fn method(self: *const Context, operation: Call, values: []const u64) !?u64 {
        var args: [255]Value = undefined;
        for (operation.params, values, 0..) |kind, value, i| args[i] = try arg(kind, value);
        if (operation.mode == 2) {
            if (operation.params.len == 0 or operation.params[0] != 'L' or operation.result != 'V')
                return error.InvalidCode;
            try self.construct(operation.name, operation.descriptor, args[0].l, args[1..].ptr);
            return null;
        }
        const mode = operation.mode;
        const instance = mode == 182 or mode == 183;
        if (instance and (operation.params.len == 0 or operation.params[0] != 'L')) return error.InvalidCode;
        const target = if (instance) try ops.receiver(self.env, self.owner, values[0]) else self.owner;
        const params: [*]const Value = if (instance) args[1..].ptr else &args;
        const base: usize = switch (mode) {
            182 => 36,
            183 => 66,
            else => 116,
        };
        const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, self.env, if (instance) 33 else 113);
        const id = get(self.env, self.owner, operation.name, operation.descriptor) orelse return error.PendingException;
        return switch (operation.result) {
            'L' => ops.bits(jni.Ref, self.typedCall(jni.Ref, mode, base, target, id, params)),
            'Z' => ops.bits(u8, self.typedCall(u8, mode, base + 3, target, id, params)),
            'B' => ops.bits(i8, self.typedCall(i8, mode, base + 6, target, id, params)),
            'C' => ops.bits(u16, self.typedCall(u16, mode, base + 9, target, id, params)),
            'S' => ops.bits(i16, self.typedCall(i16, mode, base + 12, target, id, params)),
            'I' => ops.bits(i32, self.typedCall(i32, mode, base + 15, target, id, params)),
            'J' => ops.bits(i64, self.typedCall(i64, mode, base + 18, target, id, params)),
            'F' => ops.bits(f32, self.typedCall(f32, mode, base + 21, target, id, params)),
            'D' => ops.bits(f64, self.typedCall(f64, mode, base + 24, target, id, params)),
            'V' => blk: {
                self.typedCall(void, mode, base + 27, target, id, params);
                break :blk null;
            },
            else => error.InvalidCode,
        };
    }

    pub fn invoke(self: *Context, operation: Call, values: []const u64) !?u64 {
        if (values.len != operation.params.len) return error.InvalidCode;
        const push = jni.entry(*const fn (jni.Env, i32) callconv(.c) i32, self.env, 19);
        const pop = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) jni.Ref, self.env, 20);
        if (operation.result == 'L') {
            const ensure = jni.entry(*const fn (jni.Env, i32) callconv(.c) i32, self.env, 26);
            if (ensure(self.env, @intCast(self.references.items.len + 4)) != 0) return error.PendingException;
        }
        if (push(self.env, 16) != 0) return error.PendingException;
        var popped = false;
        defer if (!popped) {
            _ = pop(self.env, null);
        };
        const result: ?u64 = switch (operation.result) {
            'L', 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D', 'V' => switch (operation.mode) {
                0...2, 182...184 => try self.method(operation, values),
                3, 4 => try ops.string(self.env, if (operation.mode == 4) "" else operation.name),
                46...53, 79...86, 188, 190 => try ops.arrayOp(self.env, operation.mode, operation.descriptor, values),
                178...181 => try ops.fieldOp(self.env, self.owner, operation.mode, operation.name, operation.descriptor, values),
                else => return error.InvalidCode,
            },
            else => return error.InvalidCode,
        };
        if (jni.hasException(self.env)) return error.PendingException;
        if (operation.result == 'L') {
            var reference = asRef(result orelse return error.InvalidCode);
            if (operation.mode == 1) {
                const allocate = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) jni.Ref, self.env, 27);
                reference = allocate(self.env, reference orelse return error.InvalidCode);
                if (jni.hasException(self.env)) return error.PendingException;
            }
            const ref = pop(self.env, reference);
            popped = true;
            if (jni.hasException(self.env)) return error.PendingException;
            return fromRef(ref);
        }
        return result;
    }

    fn construct(self: *const Context, name: [*:0]const u8, desc: [*:0]const u8, receiver: jni.Ref, args: [*]const Value) !void {
        if (receiver == null) return error.InvalidCode;
        const find = jni.entry(*const fn (jni.Env, [*:0]const u8) callconv(.c) jni.Ref, self.env, 6);
        const owner = find(self.env, name) orelse return error.PendingException;
        const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, self.env, 33);
        const ctor = get(self.env, owner, "<init>", desc) orelse return error.PendingException;
        const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, jni.Ref, [*]const Value) callconv(.c) void, self.env, 93);
        call(self.env, receiver, owner, ctor, args);
        if (jni.hasException(self.env)) return error.PendingException;
    }

    // 在循环中及时释放 JNI 产生且已经离开栈与局部槽的引用，入参由 JVM 管理。
    pub fn remember(self: *Context, bits: u64, locals: []const u64, stack: []const u64) !void {
        if (bits == 0) return;
        if (std.mem.indexOfScalar(u64, self.references.items, bits) == null)
            try self.references.append(allocator, bits);
        if (self.references.items.len < self.next_sweep) return;
        const drop = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) void, self.env, 23);
        var i: usize = 0;
        while (i < self.references.items.len) {
            const ref = self.references.items[i];
            if (std.mem.indexOfScalar(u64, locals, ref) != null or std.mem.indexOfScalar(u64, stack, ref) != null or std.mem.indexOfScalar(u64, self.monitors.items, ref) != null) {
                i += 1;
            } else {
                drop(self.env, asRef(ref));
                _ = self.references.swapRemove(i);
            }
        }
        self.next_sweep = @max(16, self.references.items.len * 2);
    }
};
