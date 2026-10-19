// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! `fsmeta` — the engine's command line: the three things you can ask a trio to do.
//!
//! ```text
//! fsmeta verify  <root>      # does the trio describe what it claims? (§ 8) — exit 1 when it does not
//! fsmeta scan    <root>      # what does the walker see? one line per name, no trio written
//! fsmeta refresh <root>      # bring <root>/.fs-meta up to date; publishes only if something changed
//! ```
//!
//! `refresh` is the engine pass; `verify` is the reader's gate; `scan` is the diagnostic that shows what a walk
//! would put in a fresh trio. `--no-gitignore` and `--hash none|lf|text` mirror the engine's options, and
//! `--updated` supplies the pass's timestamp, because the engine core deliberately has no clock.

const std = @import("std");
const format = @import("format.zig");
const validate = @import("validate.zig");
const scan = @import("scan.zig");
const engine = @import("engine.zig");

const usage =
    \\fsmeta — the fs-meta engine
    \\
    \\usage:
    \\  fsmeta verify  <root>        validate <root>/.fs-meta and report the verdict
    \\  fsmeta scan    <root>        list what the walker sees (no trio written)
    \\  fsmeta refresh <root>        bring <root>/.fs-meta up to date
    \\
    \\options:
    \\  --no-gitignore               list everything except the reserved directories
    \\  --hash none|lf|text          content hash mode recorded in the trio (default: text)
    \\  --updated <ISO-8601>         the pass timestamp; the engine has no clock of its own
    \\  --per-file                   naive listing (stat per entry) instead of the batched one
    \\  --stats                      print where the pass spent its time
    \\
;

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;
    const io = init.io;
    var args = try std.process.Args.Iterator.initAllocator(init.minimal.args, gpa);
    defer args.deinit();
    _ = args.next(); // the program itself

    const command = args.next() orelse {
        try write(io, usage);
        return;
    };

    var root: ?[]const u8 = null;
    var options: engine.Options = .{ .updated = "1970-01-01T00:00:00Z" };
    var respect_gitignore = true;
    var show_stats = false;
    var hash_mode = scan.HashMode.text;
    while (args.next()) |arg| {
        if (std.mem.eql(u8, arg, "--no-gitignore")) {
            respect_gitignore = false;
        } else if (std.mem.eql(u8, arg, "--hash")) {
            const value = args.next() orelse return fail(io, gpa, "--hash needs a value");
            hash_mode = if (std.mem.eql(u8, value, "none"))
                .none
            else if (std.mem.eql(u8, value, "lf"))
                .lf
            else if (std.mem.eql(u8, value, "text"))
                .text
            else
                return fail(io, gpa, "--hash must be none, lf or text");
        } else if (std.mem.eql(u8, arg, "--stats")) {
            show_stats = true;
        } else if (std.mem.eql(u8, arg, "--per-file")) {
            options.per_file = true;
        } else if (std.mem.eql(u8, arg, "--updated")) {
            options.updated = args.next() orelse return fail(io, gpa, "--updated needs a value");
        } else if (arg.len > 0 and arg[0] == '-') {
            return fail(io, gpa, "unknown option");
        } else {
            root = arg;
        }
    }

    const target = root orelse return fail(io, gpa, "a root directory is required");

    var arena_alloc = std.heap.ArenaAllocator.init(gpa);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    if (std.mem.eql(u8, command, "verify")) return verify(io, arena, target);
    if (std.mem.eql(u8, command, "scan")) {
        return listFacts(io, arena, target, .{
            .hash_mode = hash_mode,
            .respect_gitignore = respect_gitignore,
        });
    }
    if (std.mem.eql(u8, command, "refresh") or std.mem.eql(u8, command, "publish")) {
        options.respect_gitignore = respect_gitignore;
        options.hash_mode = hash_mode;
        options.root_path = target;
        const outcome = try engine.refresh(arena, io, target, options);
        const line = try std.fmt.allocPrint(arena, "generation {d}, {d} names, published {s} (added {d}, removed {d}, modified {d}, renamed {d}, hashed {d})\n", .{
            outcome.generation,
            outcome.count,
            if (outcome.published) "yes" else "no",
            outcome.report.files_added,
            outcome.report.files_removed,
            outcome.report.files_modified,
            outcome.report.renames,
            outcome.hashed,
        });
        try write(io, line);
        if (show_stats) {
            const stats = try std.fmt.allocPrint(arena, "stats: load {d:.1} ms, discover {d:.1} ms, hash {d:.1} ms, publish {d:.1} ms\n", .{
                @as(f64, @floatFromInt(outcome.stats.load_ns)) / 1_000_000.0,
                @as(f64, @floatFromInt(outcome.stats.discover_ns)) / 1_000_000.0,
                @as(f64, @floatFromInt(outcome.stats.hash_ns)) / 1_000_000.0,
                @as(f64, @floatFromInt(outcome.stats.publish_ns)) / 1_000_000.0,
            });
            try write(io, stats);
        }
        return;
    }
    return fail(io, gpa, "unknown command");
}

fn verify(io: std.Io, arena: std.mem.Allocator, root: []const u8) !void {
    const dir_path = if (root.len > 0 and (root[root.len - 1] == '/' or root[root.len - 1] == '\\'))
        try std.fmt.allocPrint(arena, "{s}{s}", .{ root, format.directory_name })
    else
        try std.fmt.allocPrint(arena, "{s}/{s}", .{ root, format.directory_name });
    var dir = std.Io.Dir.cwd().openDir(io, dir_path, .{ .iterate = true }) catch {
        return write(io, "no .fs-meta directory\n");
    };
    defer dir.close(io);

    const names = [_][]const u8{ format.meta_file, format.txt_file, format.json_file };
    var bytes: [3]?[]u8 = .{ null, null, null };
    for (names, 0..) |name, index| {
        bytes[index] = dir.readFileAlloc(io, name, arena, std.Io.Limit.limited(1 << 28)) catch |err| switch (err) {
            error.FileNotFound => null,
            else => return err,
        };
    }
    if (bytes[0] == null or bytes[1] == null or bytes[2] == null) {
        return write(io, "incomplete trio\n");
    }

    const result = try validate.validate(arena, .{
        .json_bytes = bytes[2].?,
        .meta_bytes = bytes[0].?,
        .txt_bytes = bytes[1].?,
    });
    if (result.verdict == .ok) {
        const line = try std.fmt.allocPrint(arena, "ok ({d} names)\n", .{result.count});
        try write(io, line);
        return;
    }
    const line = try std.fmt.allocPrint(arena, "{s}: {s}\n", .{ result.verdict.wire(), result.detail });
    try write(io, line);
    std.process.exit(1);
}

fn listFacts(io: std.Io, arena: std.mem.Allocator, root: []const u8, options: scan.Options) !void {
    const facts = try scan.scan(arena, io, root, options);
    for (facts) |fact| {
        const kind = switch (fact.kind) {
            .dir => "dir",
            .symlink => "symlink",
            .regular_file => "file",
        };
        const line = try std.fmt.allocPrint(arena, "{s} {d} {d} {s}\n", .{ kind, fact.size_bytes, fact.mtime_ns, fact.name });
        try write(io, line);
    }
    const total = try std.fmt.allocPrint(arena, "{d} names\n", .{facts.len});
    try write(io, total);
}

fn write(io: std.Io, bytes: []const u8) !void {
    try std.Io.File.stdout().writeStreamingAll(io, bytes);
}

fn fail(io: std.Io, allocator: std.mem.Allocator, message: []const u8) !void {
    const text = try std.fmt.allocPrint(allocator, "fsmeta: {s}\n{s}", .{ message, usage });
    try std.Io.File.stdout().writeStreamingAll(io, text);
    std.process.exit(2);
}