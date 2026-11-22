const std = @import("std");

// JNI function-table slots verified against the JDK headers. Targets are 64-bit.
pub const Env = *const [*]const ?*const anyopaque;
pub const Ref = ?*anyopaque;
pub const Method = extern struct { name: [*:0]const u8, signature: [*:0]const u8, function: *const anyopaque };

pub fn entry(comptime T: type, env: Env, index: usize) T {
    return @ptrCast(@alignCast(env.*[index].?));
}

pub fn raise(env: Env, name: [*:0]const u8, message: [*:0]const u8) void {
    const find = entry(*const fn (Env, [*:0]const u8) callconv(.c) Ref, env, 6);
    const throw = entry(*const fn (Env, Ref, [*:0]const u8) callconv(.c) i32, env, 14);
    const drop = entry(*const fn (Env, Ref) callconv(.c) void, env, 23);
    const cls = find(env, name) orelse return;
    defer drop(env, cls);
    _ = throw(env, cls, message);
}

pub fn register(env: Env, cls: Ref, methods: []const Method) void {
    const call = entry(*const fn (Env, Ref, [*]const Method, i32) callconv(.c) i32, env, 215);
    if (call(env, cls, methods.ptr, @intCast(methods.len)) != 0 and !hasException(env)) {
        raise(env, "java/lang/UnsatisfiedLinkError", "RegisterNatives failed");
    }
}

pub fn takeString(bytes: []const u8, cursor: *usize) ![*:0]const u8 {
    const begin = cursor.*;
    while (cursor.* < bytes.len and bytes[cursor.*] != 0) cursor.* += 1;
    if (cursor.* == bytes.len or cursor.* == begin) return error.InvalidNames;
    cursor.* += 1;
    return @ptrCast(bytes[begin..].ptr);
}

fn bind(env: Env, cls: Ref, id: u32, blob: []const u8, key: *const [32]u8, functions: []const *const anyopaque) !void {
    if (blob.len < 28) return error.InvalidNames;
    const cipher = blob[12 .. blob.len - 16];
    const allocator = std.heap.page_allocator;
    const names = try allocator.alloc(u8, cipher.len);
    defer allocator.free(names);
    defer std.crypto.secureZero(u8, names);
    var aad: [8]u8 = undefined;
    std.mem.writeInt(u32, aad[0..4], id, .little);
    std.mem.writeInt(u32, aad[4..8], @intCast(functions.len), .little);
    try std.crypto.aead.chacha_poly.ChaCha20Poly1305.decrypt(names, cipher, blob[blob.len - 16 ..][0..16].*, &aad, blob[0..12].*, key.*);
    const methods = try allocator.alloc(Method, functions.len);
    defer allocator.free(methods);
    var cursor: usize = 0;
    for (methods, functions) |*method, function| {
        method.name = try takeString(names, &cursor);
        method.signature = try takeString(names, &cursor);
        method.function = function;
    }
    if (cursor != names.len) return error.InvalidNames;
    register(env, cls, methods);
}

pub fn registerPacked(env: Env, cls: Ref, id: u32, blob: []const u8, key: *const [32]u8, functions: []const *const anyopaque) void {
    bind(env, cls, id, blob, key, functions) catch |err| switch (err) {
        error.OutOfMemory => raise(env, "java/lang/OutOfMemoryError", "JNI method table"),
        else => raise(env, "java/lang/SecurityException", "invalid JNI method table"),
    };
}

pub fn hasException(env: Env) bool {
    return entry(*const fn (Env) callconv(.c) u8, env, 228)(env) != 0;
}

fn copyArray(env: Env, array: Ref, dest: []u8) bool {
    if (array == null) return false;
    const length = entry(*const fn (Env, Ref) callconv(.c) i32, env, 171);
    if (length(env, array) != @as(i32, @intCast(dest.len))) return false;
    const region = entry(*const fn (Env, Ref, i32, i32, [*]u8) callconv(.c) void, env, 200);
    region(env, array, 0, @intCast(dest.len), dest.ptr);
    return !hasException(env);
}

pub fn verifyLicense(env: Env, pinned: []const u8, required: u64, key_array: Ref, body_array: Ref, sig_array: Ref) u8 {
    const P256 = std.crypto.sign.ecdsa.EcdsaP256Sha256;
    var sec1: [65]u8 = undefined;
    var body: [56]u8 = undefined;
    var signature: [64]u8 = undefined;
    if (!copyArray(env, key_array, &sec1) or !copyArray(env, body_array, &body) or !copyArray(env, sig_array, &signature)) return 0;
    if (sec1[0] != 4 or !std.mem.eql(u8, &sec1, pinned)) return 0;
    if (std.mem.readInt(u64, body[48..56], .big) & required != required) return 0;
    const key = P256.PublicKey.fromSec1(&sec1) catch return 0;
    P256.Signature.fromBytes(signature).verify(&body, key) catch return 0;
    return 1;
}
