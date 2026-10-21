// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! The conformance vectors as a test.
//!
//! `conformance/gitignore-vectors.json` is the contract a port is measured against, and until this file
//! existed it was prose: a case could be wrong, and nothing failed. Each case is materialised as a real
//! temporary tree — its `ruleFiles` become `.gitignore` files under a root — and answered by the **chain**
//! (`ignore.Stack.decide`), because precedence, an excluded ancestor and the third value (`no-opinion`) are
//! chain behaviour rather than one file's matcher.
//!
//! The JSON is embedded through a build-system anonymous import (`build.zig` maps `vectors.json` to the
//! file), so the test binary reads no path at run time and the file is still a tracked build input.

const std = @import("std");
const ignore = @import("ignore.zig");

const testing = std.testing;
const vectors_json = @embedFile("vectors.json");

/// One field of a JSON object, with the two failure modes named instead of panicking.
fn field(value: std.json.Value, name: []const u8) !std.json.Value {
    return switch (value) {
        .object => |object| object.get(name) orelse error.MissingField,
        else => error.NotAnObject,
    };
}

fn verdictName(verdict: ?bool) []const u8 {
    if (verdict) |excluded| return if (excluded) "exclude" else "include";
    return "no-opinion";
}

test "every conformance vector answers as it expects" {
    const io = testing.io;

    var parsed = try std.json.parseFromSlice(std.json.Value, testing.allocator, vectors_json, .{});
    defer parsed.deinit();

    const cases = (try field(parsed.value, "cases")).array.items;
    try testing.expect(cases.len > 0);

    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_count = try tmp.dir.realPath(io, &root_buffer);
    const tmp_root = root_buffer[0..root_count];

    var failures: usize = 0;
    for (cases, 0..) |case, case_index| {
        const name = (try field(case, "name")).string;
        const expect = (try field(case, "expect")).string;
        const path = (try field(case, "path")).string;
        const is_dir = (try field(case, "isDir")).bool;

        // One directory per case, so no rule file from an earlier case can reach this one.
        const case_dir = try std.fmt.allocPrint(testing.allocator, "case{d}", .{case_index});
        defer testing.allocator.free(case_dir);
        const case_root = try std.fmt.allocPrint(testing.allocator, "{s}/{s}", .{ tmp_root, case_dir });
        defer testing.allocator.free(case_root);
        try tmp.dir.createDirPath(io, case_dir);

        for ((try field(case, "ruleFiles")).array.items) |rule_file| {
            const dir = (try field(rule_file, "dir")).string;
            const rules = (try field(rule_file, "rules")).string;
            const file_path = if (dir.len == 0)
                try std.fmt.allocPrint(testing.allocator, "{s}/{s}", .{ case_dir, ".gitignore" })
            else blk: {
                const sub_dir = try std.fmt.allocPrint(testing.allocator, "{s}/{s}", .{ case_dir, dir });
                defer testing.allocator.free(sub_dir);
                try tmp.dir.createDirPath(io, sub_dir);
                break :blk try std.fmt.allocPrint(testing.allocator, "{s}/{s}", .{ sub_dir, ".gitignore" });
            };
            defer testing.allocator.free(file_path);
            try tmp.dir.writeFile(io, .{ .sub_path = file_path, .data = rules });
        }

        var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
        defer arena_alloc.deinit();
        var stack = ignore.Stack.init(arena_alloc.allocator(), io, case_root, true);
        defer stack.deinit();

        const got = verdictName(try stack.decide(path, is_dir));
        if (!std.mem.eql(u8, got, expect)) {
            std.debug.print(
                "conformance case {d} '{s}' (path '{s}'): expected {s}, got {s}\n",
                .{ case_index, name, path, expect, got },
            );
            failures += 1;
        }
    }

    if (failures > 0) {
        std.debug.print("conformance: {d} of {d} vectors failed\n", .{ failures, cases.len });
        return error.ConformanceFailed;
    }
}

test "an unterminated class matches nothing, not the literal name" {
    // The vector file pins this too; it is repeated here because the first version of the matcher, and the
    // Java and Bun ports, treated `[abc` as the literal string `[abc`. git's wildmatch aborts the pattern.
    const gitignore = @import("gitignore.zig");
    var rules = try gitignore.Rules.parse(testing.allocator, "[abc\n");
    defer rules.deinit();
    try testing.expect(rules.decide("[abc", false) == null);
}
