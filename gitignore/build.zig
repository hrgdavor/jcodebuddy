const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // The library, as another project consumes it: one module named `gitignore`.
    _ = b.addModule("gitignore", .{
        .root_source_file = b.path("src/gitignore.zig"),
        .target = target,
        .optimize = optimize,
    });

    // Tests: both files, so the chain is covered as well as the matcher.
    const test_step = b.step("test", "Run the matcher and chain tests");
    const modules = [_][]const u8{ "gitignore", "ignore" };
    for (modules) |name| {
        const tests = b.addTest(.{
            .root_module = b.createModule(.{
                .root_source_file = b.path(b.fmt("src/{s}.zig", .{name})),
                .target = target,
                .optimize = optimize,
            }),
        });
        test_step.dependOn(&b.addRunArtifact(tests).step);
    }
}
