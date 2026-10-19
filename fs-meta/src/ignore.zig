// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! The `.gitignore` stack a **cold-start listing** needs, so that the incremental path and the full walk agree
//! about which names exist at all.
//!
//! `gitignore.zig` decides one path against one file's rules and answers `null` when that file has no opinion.
//! Turning that into git's behaviour takes two more facts, and they are the whole of this file:
//!
//! 1. **Scope.** A `.gitignore` in `d/` governs the paths below `d`, so a decision needs the path of the candidate
//!    *relative to `d`* — not relative to the scan root. (The walker keeps that as a byte offset, `dir_len`; here
//!    it is computed by stripping the base.)
//! 2. **Precedence.** The deepest file with an opinion wins, and a negation in a deeper file re-includes what a
//!    shallower one excluded. So the decision walks from the candidate's own directory up to the root and takes
//!    the first non-`null`.
//!
//! Excluded directories are not listed and not entered — that is git's rule, and it is what makes the stack safe:
//! because the engine never enters one, no rule inside it can ever re-include anything.

const std = @import("std");
const gitignore = @import("gitignore.zig");

/// One flattened matcher for a directory: every `.gitignore` that governs it, in precedence order.
const Chain = struct {
    /// Deepest file first, each file reversed. An anchored rule's `glob` is already scoped to the root.
    items: []gitignore.Rule,
    literals: std.StringHashMapUnmanaged(usize) = .{},
    literals_dir_only: std.StringHashMapUnmanaged(usize) = .{},

    fn decide(self: Chain, path: []const u8, base: []const u8, is_dir: bool) ?bool {
        var bound: ?usize = null;
        var start: usize = 0;
        while (start <= path.len) {
            const slash = std.mem.indexOfScalarPos(u8, path, start, '/') orelse path.len;
            const segment = path[start..slash];
            if (self.literals.get(segment)) |at| {
                if (bound == null or at < bound.?) bound = at;
            }
            const last_segment = slash == path.len;
            if (!last_segment or is_dir) {
                if (self.literals_dir_only.get(segment)) |at| {
                    if (bound == null or at < bound.?) bound = at;
                }
            }
            if (last_segment) break;
            start = slash + 1;
        }

        const limit = bound orelse self.items.len;
        for (self.items[0..limit]) |rule| {
            if (rule.kind == .literal and !rule.anchored) continue; // answered by the indexed literals
            if (chainRuleMatches(rule, path, base, is_dir)) return !rule.negated;
        }
        if (bound) |at| return !self.items[at].negated;
        return null;
    }
};

fn chainRuleMatches(rule: gitignore.Rule, path: []const u8, base: []const u8, is_dir: bool) bool {
    if (!rule.directory_only) return chainMatchText(rule, if (rule.anchored) path else base);
    if (is_dir and chainMatchText(rule, path)) return true;
    var index: usize = 0;
    while (index < path.len) : (index += 1) {
        if (path[index] == '/') {
            if (chainMatchText(rule, path[0..index])) return true;
        }
    }
    return false;
}

fn chainMatchText(rule: gitignore.Rule, text: []const u8) bool {
    return switch (rule.kind) {
        .literal => std.mem.eql(u8, rule.glob, text),
        .suffix => text.len >= rule.suffix.len and std.mem.endsWith(u8, text, rule.suffix),
        .glob => gitignore.globMatch(rule.glob, text),
    };
}
pub const Stack = struct {
    arena: std.mem.Allocator,
    io: std.Io,
    /// The tree's root as the engine received it; every `.gitignore` scope is measured against it.
    root: []const u8,
    respect: bool,
    /// Parsed rules by the absolute path of the directory that owns the file.
    rules: std.StringArrayHashMapUnmanaged(gitignore.Rules) = .empty,
    /// Directories whose `.gitignore` has been looked for, so a miss costs one `openFile` per pass.
    checked: std.StringArrayHashMapUnmanaged(void) = .empty,
    /// Flattened matchers, by the directory of their deepest rules file.
    chains: std.StringHashMapUnmanaged(*Chain) = .{},
    /// One reused buffer for the path handed to a chain: an entry lookup must not allocate.
    scratch: std.ArrayListUnmanaged(u8) = .empty,

    pub fn init(arena: std.mem.Allocator, io: std.Io, root: []const u8, respect: bool) Stack {
        return .{ .arena = arena, .io = io, .root = root, .respect = respect };
    }

    pub fn deinit(self: *Stack) void {
        for (self.rules.values()) |*parsed| parsed.deinit();
        self.rules.deinit(self.arena);
        self.checked.deinit(self.arena);
        self.scratch.deinit(self.arena);
        var it = self.chains.iterator();
        while (it.next()) |entry| {
            entry.value_ptr.*.literals.deinit(self.arena);
            entry.value_ptr.*.literals_dir_only.deinit(self.arena);
            self.arena.free(entry.value_ptr.*.items);
            self.arena.destroy(entry.value_ptr.*);
        }
        self.chains.deinit(self.arena);
    }

    /// Whether any `.gitignore` from the root down to `dir_path` has rules. Answered once per directory, so the
    /// per-entry path can skip the whole matcher when the answer is no — which is the common case.
    pub fn hasRules(self: *Stack, dir_path: []const u8) !bool {
        if (!self.respect) return false;
        var dir: []const u8 = dir_path;
        while (true) {
            if (try self.rulesFor(dir)) |parsed| {
                if (parsed.ruleCount() > 0) return true;
            }
            if (dir.len <= self.root.len) return false;
            const parent = std.fs.path.dirname(dir) orelse return false;
            if (parent.len >= dir.len) return false;
            dir = parent;
        }
    }

    /// Whether the child `name` of `dir_path` is visible to the engine under the `.gitignore` files that govern it.
    /// `dir_path` is an OS path (the root joined with the relative directory), as the lister receives it.
    pub fn ignored(self: *Stack, dir_path: []const u8, name: []const u8, is_dir: bool) !bool {
        if (!self.respect) return false;
        const chain = (try self.chainFor(dir_path)) orelse return false;
        if (chain.items.len == 0) return false;
        const rel_dir = self.relOf(dir_path);
        self.scratch.clearRetainingCapacity();
        if (rel_dir.len > 0) {
            try self.scratch.appendSlice(self.arena, rel_dir);
            try self.scratch.append(self.arena, '/');
        }
        try self.scratch.appendSlice(self.arena, name);
        return chain.decide(self.scratch.items, name, is_dir) orelse false;
    }

    /// The flattened matcher for `dir_path`, built once per deepest rules file and cached there.
    fn chainFor(self: *Stack, dir_path: []const u8) !?*Chain {
        var deepest: ?[]const u8 = null;
        var dir: []const u8 = dir_path;
        while (true) {
            if (try self.rulesFor(dir)) |parsed| {
                if (parsed.ruleCount() > 0) {
                    deepest = dir;
                    break;
                }
            }
            if (dir.len <= self.root.len) break;
            const parent = std.fs.path.dirname(dir) orelse break;
            if (parent.len >= dir.len) break;
            dir = parent;
        }
        const key = deepest orelse return null;
        if (self.chains.get(key)) |chain| return chain;

        // Deepest first, each file reversed; anchored patterns scoped with their owner's path.
        var items: std.ArrayListUnmanaged(gitignore.Rule) = .empty;
        var chain = try self.arena.create(Chain);
        chain.* = .{ .items = &.{} };
        var walk: []const u8 = key;
        while (true) {
            if (try self.rulesFor(walk)) |parsed| {
                const base_rel = self.relOf(walk);
                var index = parsed.items.len;
                while (index > 0) {
                    index -= 1;
                    var rule = parsed.items[index];
                    if (rule.anchored and base_rel.len > 0) {
                        rule.glob = try std.fmt.allocPrint(self.arena, "{s}/{s}", .{ base_rel, rule.glob });
                    }
                    const position = items.items.len;
                    try items.append(self.arena, rule);
                    if (rule.kind == .literal and !rule.anchored) {
                        const map = if (rule.directory_only) &chain.literals_dir_only else &chain.literals;
                        if (!map.contains(rule.glob)) try map.put(self.arena, rule.glob, position);
                    }
                }
            }
            if (walk.len <= self.root.len) break;
            const parent = std.fs.path.dirname(walk) orelse break;
            if (parent.len >= walk.len) break;
            walk = parent;
        }
        chain.items = try items.toOwnedSlice(self.arena);
        try self.chains.put(self.arena, key, chain);
        return chain;
    }

    /// The path of `path` relative to the root, `""` for the root itself.
    fn relOf(self: *Stack, path: []const u8) []const u8 {
        if (self.root.len == 0) return path;
        if (path.len <= self.root.len) return "";
        const last = self.root[self.root.len - 1];
        const skip: usize = if (last == '/' or last == '\\') 0 else 1;
        return path[self.root.len + skip ..];
    }


    /// The rules of `dir`'s own `.gitignore`, or `null` when it has none. Looked for once per directory.
    fn rulesFor(self: *Stack, dir: []const u8) !?gitignore.Rules {
        if (self.checked.contains(dir)) return self.rules.get(dir);
        try self.checked.put(self.arena, try self.arena.dupe(u8, dir), {});

        const file_path = if (dir.len == 0)
            gitignore.file_name
        else
            try std.fmt.allocPrint(self.arena, "{s}/{s}", .{ dir, gitignore.file_name });
        const text = std.Io.Dir.cwd().readFileAlloc(self.io, file_path, self.arena, std.Io.Limit.limited(1 << 20)) catch |err| switch (err) {
            error.FileNotFound, error.IsDir, error.NotDir => return null,
            else => return err,
        };
        const parsed = try gitignore.Rules.parse(self.arena, text);
        try self.rules.put(self.arena, try self.arena.dupe(u8, dir), parsed);
        return parsed;
    }
};

test "the deepest .gitignore wins, and a negation re-includes" {
    const testing = std.testing;
    const io = testing.io;
    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.writeFile(io, .{ .sub_path = ".gitignore", .data = "*.log\n!keep.log\nbuild/\n" });
    try tmp.dir.createDirPath(io, "src");
    try tmp.dir.createDirPath(io, "build");
    try tmp.dir.writeFile(io, .{ .sub_path = "src/.gitignore", .data = "!keep.log\n" });
    try tmp.dir.writeFile(io, .{ .sub_path = "x.log", .data = "x\n" });
    try tmp.dir.writeFile(io, .{ .sub_path = "keep.log", .data = "k\n" });
    try tmp.dir.writeFile(io, .{ .sub_path = "src/keep.log", .data = "k\n" });

    var path_buffer: [std.Io.Dir.max_path_bytes]u8 = undefined;
    const count = try tmp.dir.realPath(io, &path_buffer);
    const root = path_buffer[0..count];
    const src = try std.fmt.allocPrint(testing.allocator, "{s}/src", .{root});
    defer testing.allocator.free(src);

    var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    var stack = Stack.init(arena, io, root, true);
    defer stack.deinit();

    try testing.expect(try stack.ignored(root, "x.log", false));
    try testing.expect(!try stack.ignored(root, "keep.log", false)); // the root's own negation
    try testing.expect(!try stack.ignored(src, "keep.log", false)); // re-included from below
    try testing.expect(try stack.ignored(root, "build", true));
    try testing.expect(!try stack.ignored(root, ".gitignore", false)); // the rule file itself is visible

    var off = Stack.init(arena, io, root, false);
    defer off.deinit();
    try testing.expect(!try off.ignored(root, "x.log", false)); // respect = false lists everything
}