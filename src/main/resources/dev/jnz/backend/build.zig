const std = @import("std");

pub fn build(b: *std.Build) void {
    const module = b.createModule(.{
        .root_source_file = b.path("module.zig"),
        .target = b.standardTargetOptions(.{}),
        .optimize = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseSafe }),
        .pic = true,
        .strip = true,
    });
    b.installArtifact(b.addLibrary(.{ .name = "jnz", .linkage = .dynamic, .root_module = module }));
}
