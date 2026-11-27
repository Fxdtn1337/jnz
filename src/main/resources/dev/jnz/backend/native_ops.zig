const jni = @import("jni.zig");

pub fn fromRef(ref: jni.Ref) u64 {
    return if (ref) |ptr| @intFromPtr(ptr) else 0;
}

pub fn asRef(word: u64) jni.Ref {
    return if (word == 0) null else @ptrFromInt(@as(usize, @intCast(word)));
}

pub fn cast(comptime T: type, word: u64) T {
    return @bitCast(@as(@Int(.unsigned, @bitSizeOf(T)), @truncate(word)));
}

pub fn bits(comptime T: type, value: T) u64 {
    if (T == jni.Ref) return fromRef(value);
    if (T == i8 or T == i16 or T == i32) return @as(u32, @bitCast(@as(i32, value)));
    if (T == i64 or T == f64) return @as(u64, @bitCast(value));
    if (T == f32) return @as(u32, @bitCast(value));
    return value;
}

fn findClass(env: jni.Env, name: [*:0]const u8) !jni.Ref {
    const find = jni.entry(*const fn (jni.Env, [*:0]const u8) callconv(.c) jni.Ref, env, 6);
    return find(env, name) orelse error.PendingException;
}

fn required(env: jni.Env, value: u64, detail: [*:0]const u8) !jni.Ref {
    if (value != 0) return asRef(value);
    jni.raise(env, "java/lang/NullPointerException", detail);
    return error.PendingException;
}

fn instanceOf(env: jni.Env, object: jni.Ref, class: jni.Ref) bool {
    const check = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref) callconv(.c) u8, env, 32);
    return check(env, object, class) != 0;
}

pub fn receiver(env: jni.Env, owner: jni.Ref, value: u64) !jni.Ref {
    const object = try required(env, value, "null receiver");
    if (!instanceOf(env, object, owner)) return error.InvalidCode;
    return object;
}

pub fn string(env: jni.Env, literal: [*:0]const u8) !?u64 {
    // The compiler writes JNI modified UTF-8, preserving JVM UTF-16 code units including NUL.
    const create = jni.entry(*const fn (jni.Env, [*:0]const u8) callconv(.c) jni.Ref, env, 167);
    const value = create(env, literal) orelse return error.PendingException;
    const owner = try findClass(env, "java/lang/String");
    const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, env, 33);
    const id = get(env, owner, "intern", "()Ljava/lang/String;") orelse return error.PendingException;
    const invoke = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, ?*const anyopaque) callconv(.c) jni.Ref, env, 36);
    return fromRef(invoke(env, value, id, null));
}

fn elementOffset(kind: u8) !usize {
    return switch (kind) {
        'Z' => 0,
        'B' => 1,
        'C' => 2,
        'S' => 3,
        'I' => 4,
        'J' => 5,
        'F' => 6,
        'D' => 7,
        else => error.InvalidCode,
    };
}

fn getPrimitive(comptime T: type, env: jni.Env, slot: usize, array: jni.Ref, index: i32) u64 {
    const get = jni.entry(*const fn (jni.Env, jni.Ref, i32, i32, *T) callconv(.c) void, env, slot);
    var cell: T = 0; // JNI leaves the destination unchanged on an exception.
    get(env, array, index, 1, &cell);
    return bits(T, cell);
}

fn setPrimitive(comptime T: type, env: jni.Env, slot: usize, array: jni.Ref, index: i32, value: T) void {
    const set = jni.entry(*const fn (jni.Env, jni.Ref, i32, i32, *const T) callconv(.c) void, env, slot);
    set(env, array, index, 1, &value);
}

fn isArray(env: jni.Env, array: jni.Ref) !bool {
    const get_class = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) jni.Ref, env, 31);
    const type_object = get_class(env, array) orelse return error.PendingException;
    const class = get_class(env, type_object) orelse return error.PendingException;
    const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, env, 33);
    const id = get(env, class, "isArray", "()Z") orelse return error.PendingException;
    const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, ?*const anyopaque) callconv(.c) u8, env, 39);
    const result = call(env, type_object, id, null);
    if (jni.hasException(env)) return error.PendingException;
    return result != 0;
}

pub fn arrayOp(env: jni.Env, op: u8, descriptor: [*:0]const u8, values: []const u64) !?u64 {
    if (op == 188) {
        if (values.len != 1 or descriptor[0] != '[') return error.InvalidCode;
        const offset = try elementOffset(descriptor[1]);
        const create = jni.entry(*const fn (jni.Env, i32) callconv(.c) jni.Ref, env, 175 + offset);
        return fromRef(create(env, cast(i32, values[0])));
    }
    if (op == 190) {
        if (values.len != 1) return error.InvalidCode;
        const array = try required(env, values[0], "null array");
        if (!try isArray(env, array)) return error.InvalidCode;
        const length = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) i32, env, 171);
        return bits(i32, length(env, array));
    }

    const store = op >= 79;
    if (values.len != @as(usize, if (store) 3 else 2) or descriptor[0] != '[') return error.InvalidCode;
    const array = try required(env, values[0], "null array");
    const index = cast(i32, values[1]);
    var kind = descriptor[1];
    if (op == 51 or op == 84) { // BALOAD/BASTORE also handle boolean[].
        if (instanceOf(env, array, try findClass(env, "[Z"))) kind = 'Z' else if (!instanceOf(env, array, try findClass(env, "[B"))) return error.InvalidCode;
    } else if (!instanceOf(env, array, try findClass(env, descriptor))) return error.InvalidCode;

    if (kind == 'L') {
        if (store) {
            const set = jni.entry(*const fn (jni.Env, jni.Ref, i32, jni.Ref) callconv(.c) void, env, 174);
            set(env, array, index, asRef(values[2]));
            return null;
        }
        const get = jni.entry(*const fn (jni.Env, jni.Ref, i32) callconv(.c) jni.Ref, env, 173);
        return fromRef(get(env, array, index));
    }
    const offset = try elementOffset(kind);
    if (store) {
        const slot = 207 + offset;
        const value = values[2];
        switch (kind) {
            'Z' => setPrimitive(u8, env, slot, array, index, @intCast(value & 1)),
            'B' => setPrimitive(i8, env, slot, array, index, cast(i8, value)),
            'C' => setPrimitive(u16, env, slot, array, index, cast(u16, value)),
            'S' => setPrimitive(i16, env, slot, array, index, cast(i16, value)),
            'I' => setPrimitive(i32, env, slot, array, index, cast(i32, value)),
            'J' => setPrimitive(i64, env, slot, array, index, cast(i64, value)),
            'F' => setPrimitive(f32, env, slot, array, index, cast(f32, value)),
            'D' => setPrimitive(f64, env, slot, array, index, cast(f64, value)),
            else => return error.InvalidCode,
        }
        return null;
    }
    const slot = 199 + offset;
    return switch (kind) {
        'Z' => getPrimitive(u8, env, slot, array, index),
        'B' => getPrimitive(i8, env, slot, array, index),
        'C' => getPrimitive(u16, env, slot, array, index),
        'S' => getPrimitive(i16, env, slot, array, index),
        'I' => getPrimitive(i32, env, slot, array, index),
        'J' => getPrimitive(i64, env, slot, array, index),
        'F' => getPrimitive(f32, env, slot, array, index),
        'D' => getPrimitive(f64, env, slot, array, index),
        else => error.InvalidCode,
    };
}

fn getField(comptime T: type, env: jni.Env, slot: usize, target: jni.Ref, id: jni.Ref) u64 {
    const get = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref) callconv(.c) T, env, slot);
    return bits(T, get(env, target, id));
}

fn setField(comptime T: type, env: jni.Env, slot: usize, target: jni.Ref, id: jni.Ref, value: T) void {
    const set = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, T) callconv(.c) void, env, slot);
    set(env, target, id, value);
}

fn validateFieldReference(env: jni.Env, owner: jni.Ref, id: jni.Ref, static: bool, value: jni.Ref) !void {
    if (value == null) return;
    // ToReflectedField.getType resolves the declared type without initializing the field's class.
    const reflect = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, u8) callconv(.c) jni.Ref, env, 12);
    const field = reflect(env, owner, id, @intFromBool(static)) orelse return error.PendingException;
    const get_class = jni.entry(*const fn (jni.Env, jni.Ref) callconv(.c) jni.Ref, env, 31);
    const field_class = get_class(env, field) orelse return error.PendingException;
    const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, env, 33);
    const method = get(env, field_class, "getType", "()Ljava/lang/Class;") orelse return error.PendingException;
    const call = jni.entry(*const fn (jni.Env, jni.Ref, jni.Ref, ?*const anyopaque) callconv(.c) jni.Ref, env, 36);
    const declared = call(env, field, method, null);
    if (jni.hasException(env)) return error.PendingException;
    if (declared == null or !instanceOf(env, value, declared)) return error.InvalidCode;
}

pub fn fieldOp(env: jni.Env, owner: jni.Ref, op: u8, name: [*:0]const u8, descriptor: [*:0]const u8, values: []const u64) !?u64 {
    const static = op == 178 or op == 179;
    const store = op == 179 or op == 181;
    const receiver_slots: usize = if (static) 0 else 1;
    if (values.len != receiver_slots + @as(usize, if (store) 1 else 0)) return error.InvalidCode;
    const target = if (static) owner else try receiver(env, owner, values[0]);
    const get = jni.entry(*const fn (jni.Env, jni.Ref, [*:0]const u8, [*:0]const u8) callconv(.c) jni.Ref, env, if (static) 144 else 94);
    const id = get(env, owner, name, descriptor) orelse return error.PendingException;
    const kind = if (descriptor[0] == '[') 'L' else descriptor[0];
    const offset = if (kind == 'L') 0 else 1 + try elementOffset(kind);
    if (store) {
        const slot = @as(usize, if (static) 154 else 104) + offset;
        const value = values[receiver_slots];
        switch (kind) {
            'L' => {
                try validateFieldReference(env, owner, id, static, asRef(value));
                setField(jni.Ref, env, slot, target, id, asRef(value));
            },
            'Z' => setField(u8, env, slot, target, id, @intCast(value & 1)),
            'B' => setField(i8, env, slot, target, id, cast(i8, value)),
            'C' => setField(u16, env, slot, target, id, cast(u16, value)),
            'S' => setField(i16, env, slot, target, id, cast(i16, value)),
            'I' => setField(i32, env, slot, target, id, cast(i32, value)),
            'J' => setField(i64, env, slot, target, id, cast(i64, value)),
            'F' => setField(f32, env, slot, target, id, cast(f32, value)),
            'D' => setField(f64, env, slot, target, id, cast(f64, value)),
            else => return error.InvalidCode,
        }
        return null;
    }
    const slot = @as(usize, if (static) 145 else 95) + offset;
    return switch (kind) {
        'L' => getField(jni.Ref, env, slot, target, id),
        'Z' => getField(u8, env, slot, target, id),
        'B' => getField(i8, env, slot, target, id),
        'C' => getField(u16, env, slot, target, id),
        'S' => getField(i16, env, slot, target, id),
        'I' => getField(i32, env, slot, target, id),
        'J' => getField(i64, env, slot, target, id),
        'F' => getField(f32, env, slot, target, id),
        'D' => getField(f64, env, slot, target, id),
        else => error.InvalidCode,
    };
}
