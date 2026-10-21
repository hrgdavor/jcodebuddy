// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! `ignore-decide` — the bench contract as a program, so any port can be timed on the same bytes.
//!
//! ```text
//! ignore-decide --rules <file.gitignore> --paths <file> [--dirs <file>]
//!     one line per path, in input order: include | exclude | no-opinion
//! ```
//!
//! `bench/perf.mjs` spawns this once per workload with `{rules}` and `{paths}` substituted. The rules are
//! written into a fresh temporary root as that root's `.gitignore`, because the chain reads files rather than
//! text: measuring a matcher through the same file-reading path every port must implement is the point of
//! the contract, and it keeps this CLI honest about the one thing a port cannot skip.
//!
//! Exactly one line per input path reaches stdout and nothing else does; every diagnostic goes to stderr and
//! exits 2, so a failure can never look like a short but valid answer list. Every input is read before the
//! temporary root is created, so a usage error leaves no directory behind.
//!
//! `-Dnaive=true` builds this same program against the historical designs (`ignore.decideNaive`: every rule
//! scanned, `gitignore.globMatchNaive`: recursive `**`). The default executable references neither, so Zig
//! never analyses them there and the benchmark cannot silently measure the wrong implementation; the tests
//! call both, so the historical path is tested rather than only remembered.

const std = @import("std");
const ignore = @import("ignore.zig");
const build_options = @import("build_options");

const usage =
    \\ignore-decide — answer the .gitignore bench contract
    \\
    \\usage:
    \\  ignore-decide --rules <file.gitignore> --paths <file> [--dirs <file>]
    \\
    \\output:
    \\  one line per path, in input order: include | exclude | no-opinion
    \\
    \\options:
    \\  --rules <file>   the .gitignore to apply (written into a temporary root)
    \\  --paths <file>   one root-relative, /-separated path per line
    \\  --dirs <file>    one 0/1 per path (0 = file, 1 = directory); default: all files
    \\
;

pub fn main(init: std.process.Init) !void {
    const gpa = init.gpa;
    const io = init.io;

    var args = try std.process.Args.Iterator.initAllocator(init.minimal.args, gpa);
    defer args.deinit();
    _ = args.next(); // the program itself

    var rules_path: ?[]const u8 = null;
    var paths_path: ?[]const u8 = null;
    var dirs_path: ?[]const u8 = null;
    while (args.next()) |arg| {
        if (std.mem.eql(u8, arg, "--rules")) {
            rules_path = args.next() orelse return fail(io, gpa, "--rules needs a file");
        } else if (std.mem.eql(u8, arg, "--paths")) {
            paths_path = args.next() orelse return fail(io, gpa, "--paths needs a file");
        } else if (std.mem.eql(u8, arg, "--dirs")) {
            dirs_path = args.next() orelse return fail(io, gpa, "--dirs needs a file");
        } else {
            return fail(io, gpa, "unknown option (only --rules, --paths and --dirs are accepted)");
        }
    }

    const rules_file = rules_path orelse return fail(io, gpa, "--rules is required");
    const paths_file = paths_path orelse return fail(io, gpa, "--paths is required");

    // Everything that can be wrong with the input is checked before the temporary root exists.
    const rules_text = readFile(io, gpa, rules_file) catch |err| return failFile(io, gpa, rules_file, err);
    defer gpa.free(rules_text);
    const paths_text = readFile(io, gpa, paths_file) catch |err| return failFile(io, gpa, paths_file, err);
    defer gpa.free(paths_text);

    var dir_flags: std.ArrayList(bool) = .empty;
    defer dir_flags.deinit(gpa);
    if (dirs_path) |dirs_file| {
        const dirs_text = readFile(io, gpa, dirs_file) catch |err| return failFile(io, gpa, dirs_file, err);
        defer gpa.free(dirs_text);
        var dir_lines = std.mem.splitScalar(u8, dirs_text, '\n');
        while (dir_lines.next()) |raw| {
            const line = trimCr(raw);
            if (line.len == 0) continue;
            if (std.mem.eql(u8, line, "1")) {
                try dir_flags.append(gpa, true);
            } else if (std.mem.eql(u8, line, "0")) {
                try dir_flags.append(gpa, false);
            } else {
                return fail(io, gpa, "--dirs lines must be 0 or 1");
            }
        }
        if (dir_flags.items.len != countPaths(paths_text)) {
            return fail(io, gpa, "--dirs must hold exactly one 0/1 per --paths line");
        }
    }

    // A fresh root per process, so two ports can be timed in parallel. The OS temporary directory is the
    // normal home; a sandbox that grants only the workspace denies it to a directly spawned process, so the
    // working directory is the fallback (the directory is removed on the way out either way).
    var random_bytes: [8]u8 = undefined;
    io.random(&random_bytes);
    var token: [std.base64.url_safe_no_pad.Encoder.calcSize(random_bytes.len)]u8 = undefined;
    _ = std.base64.url_safe_no_pad.Encoder.encode(&token, &random_bytes);
    const os_temp = init.environ_map.get("TMPDIR") orelse
        init.environ_map.get("TEMP") orelse
        init.environ_map.get("TMP") orelse ".";
    const temp_root = makeRoot(io, gpa, os_temp, &token) catch makeRoot(io, gpa, ".", &token) catch |err|
        return failFile(io, gpa, "a temporary root", err);
    defer {
        std.Io.Dir.cwd().deleteTree(io, temp_root) catch {};
        gpa.free(temp_root);
    }

    const rules_target = try std.fmt.allocPrint(gpa, "{s}/{s}", .{ temp_root, ".gitignore" });
    defer gpa.free(rules_target);
    std.Io.Dir.cwd().writeFile(io, .{ .sub_path = rules_target, .data = rules_text }) catch |err|
        return failFile(io, gpa, rules_target, err);

    var root_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const root_count = std.Io.Dir.cwd().realPathFile(io, temp_root, &root_buffer) catch |err|
        return failFile(io, gpa, temp_root, err);

    var arena_alloc = std.heap.ArenaAllocator.init(gpa);
    defer arena_alloc.deinit();
    var stack = ignore.Stack.init(arena_alloc.allocator(), io, root_buffer[0..root_count], true);
    defer stack.deinit();

    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(gpa);
    var path_count: usize = 0;
    var lines = std.mem.splitScalar(u8, paths_text, '\n');
    while (lines.next()) |raw| {
        const path = trimCr(raw);
        if (path.len == 0) continue;

        const is_dir = if (dirs_path != null) dir_flags.items[path_count] else false;
        const verdict = if (build_options.naive)
            try stack.decideNaive(path, is_dir)
        else
            try stack.decide(path, is_dir);
        try out.appendSlice(gpa, verdictName(verdict));
        try out.append(gpa, '\n');
        path_count += 1;
    }

    try std.Io.File.stdout().writeStreamingAll(io, out.items);
}

fn readFile(io: std.Io, gpa: std.mem.Allocator, path: []const u8) ![]u8 {
    return std.Io.Dir.cwd().readFileAlloc(io, path, gpa, std.Io.Limit.limited(1 << 26));
}

/// Creates `<base>/.gitignore-decide-<id>-<token>` and returns its path. Unique per process, and the caller
/// owns the returned slice.
fn makeRoot(io: std.Io, gpa: std.mem.Allocator, base: []const u8, token: []const u8) ![]u8 {
    const path = try std.fmt.allocPrint(gpa, "{s}/.gitignore-decide-{d}-{s}", .{
        base,
        std.Thread.getCurrentId(),
        token,
    });
    errdefer gpa.free(path);
    try std.Io.Dir.cwd().createDirPath(io, path);
    return path;
}

/// How many paths the input names (blank lines are transport, not paths).
fn countPaths(paths_text: []const u8) usize {
    var count: usize = 0;
    var lines = std.mem.splitScalar(u8, paths_text, '\n');
    while (lines.next()) |raw| {
        if (trimCr(raw).len > 0) count += 1;
    }
    return count;
}

fn verdictName(verdict: ?bool) []const u8 {
    if (verdict) |excluded| return if (excluded) "exclude" else "include";
    return "no-opinion";
}

/// A CRLF line ending is part of the transport, not part of a path or a flag.
fn trimCr(line: []const u8) []const u8 {
    return if (line.len > 0 and line[line.len - 1] == '\r') line[0 .. line.len - 1] else line;
}

fn fail(io: std.Io, allocator: std.mem.Allocator, message: []const u8) !void {
    const text = try std.fmt.allocPrint(allocator, "ignore-decide: {s}\n{s}", .{ message, usage });
    try std.Io.File.stderr().writeStreamingAll(io, text);
    std.process.exit(2);
}

fn failFile(io: std.Io, allocator: std.mem.Allocator, path: []const u8, err: anyerror) !void {
    const message = try std.fmt.allocPrint(allocator, "cannot use {s}: {s}", .{ path, @errorName(err) });
    return fail(io, allocator, message);
}
