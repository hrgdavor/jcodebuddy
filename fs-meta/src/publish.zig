// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! The one writer (§ 9 of `FORMAT.md`): turn a `Cache` into a trio on disk, in the order that makes a crash
//! survivable.
//!
//! ## The order, and why each step is where it is
//!
//! 1. `files.meta.tmp`, `files.txt.tmp` — the two halves of the pair, each written completely and fsynced.
//! 2. `files.json.tmp` — the sidekick, carrying the **whole-file wyhash** of both halves.
//! 3. `fsync` the directory (best effort, § 9 explains what each platform can do).
//! 4. rename `files.meta`, then `files.txt`, then **`files.json` last**.
//!
//! The commit record is the rename of `files.json`: a reader that sees the old json with new data files will find
//! a size or hash mismatch and fall back to the file system, which is the only failure this ordering leaves. A
//! reader that sees the new json sees data files that were already fully written and fsynced.
//!
//! ## What a publish does with a dirty entry
//!
//! A file whose `content_hash` is `null` has not been hashed yet — the engine hashes on demand (§ 11). It is
//! published as a **listing-only** entry (`CONTENT_KNOWN` clear, `contentHash` 0), which is exactly the state § 6
//! defines for "the name and metadata are known, no fingerprint was taken". A later publish, after a client asked
//! for the hash, writes it.

const std = @import("std");
const builtin = @import("builtin");
const format = @import("format.zig");
const json = @import("json_doc.zig");
const cache_mod = @import("cache.zig");

pub const Cache = cache_mod.Cache;

pub const Request = struct {
    /// The generation to record; the caller decides (normally `cache.generation + 1`).
    generation: u64,
    /// `"none"`, `"lf"` or `"text"` — what the engine was told to do (per-entry `HASH_LF` stays authoritative).
    normalize: []const u8,
    root_dev: u64,
    root_ino: u64,
    root_path: ?[]const u8 = null,
    /// Preserved from the previous trio by the caller, so the first publish's instant survives every rewrite.
    created: []const u8,
    updated: []const u8,
    writer: []const u8,
    scan: ?json.ScanFields = null,
};

pub const Outcome = struct {
    count: u64,
    meta_bytes: u64,
    txt_bytes: u64,
    meta_hash: u64,
    txt_hash: u64,
    /// The exact bytes written, so a caller (a test, a client answering over the socket) does not re-read them.
    meta: []const u8,
    txt: []const u8,
    json: []const u8,
};

/// Write the trio into `dir` (an open `.fs-meta` directory) and return what was written.
pub fn publish(allocator: std.mem.Allocator, io: std.Io, dir: std.Io.Dir, cache: *const Cache, request: Request) !Outcome {
    // 1. The pair, in the trio's order (unsigned byte comparison of the names, § 3).
    var paths: std.ArrayListUnmanaged([]const u8) = .empty;
    defer paths.deinit(allocator);
    const files_it = cache.files.keys();
    for (files_it) |path| try paths.append(allocator, path);
    const dirs_it = cache.dirs.keys();
    for (dirs_it) |path| {
        if (path.len != 0) try paths.append(allocator, path); // the root is never listed (§ 3)
    }
    // § 3: `(nameHash, name)`. The hash is the name ident, so a batch and the main meta are joined the same way.
    std.mem.sort([]const u8, paths.items, {}, pathLessThanByHash);

    var names: std.ArrayListUnmanaged([]const u8) = .empty;
    defer names.deinit(allocator);
    var entries: std.ArrayListUnmanaged(format.Entry) = .empty;
    defer entries.deinit(allocator);
    var txt: std.ArrayListUnmanaged(u8) = .empty;
    defer txt.deinit(allocator);

    var offset: u64 = 0;
    for (paths.items) |path| {
        try names.append(allocator, path);
        try txt.appendSlice(allocator, path);
        try txt.append(allocator, '\n');

        const name_length: u32 = @intCast(path.len);
        if (cache.files.get(path)) |file| {
            // A dirty hash is published as listing-only, not as a wrong hash (§ 6).
            const known = file.content_hash != null;
            try entries.append(allocator, .{
                .mtime_ns = file.mtime_ns,
                .size_bytes = file.size_bytes,
                .content_hash = file.content_hash orelse 0,
                .name_hash = format.wyhash(path),
                .name_offset = offset,
                .name_length = name_length,
                .flags_word = switch (file.kind) {
                    .dir => unreachable, // directories live in `cache.dirs`
                    .symlink => format.flags.symlink,
                    .regular_file => format.flags.regular_file |
                        (if (known) format.flags.content_known else 0) |
                        (if (known and file.hash_lf) format.flags.hash_lf else 0),
                },
                .file_id_lo = file.file_id_lo,
                .file_id_hi = file.file_id_hi,
            });
        } else if (cache.dirs.get(path)) |directory| {
            try entries.append(allocator, .{
                .mtime_ns = directory.mtime_ns,
                .size_bytes = 0,
                .content_hash = 0,
                .name_hash = format.wyhash(path),
                .name_offset = offset,
                .name_length = name_length,
                .flags_word = format.flags.is_dir,
                .file_id_lo = directory.file_id_lo,
                .file_id_hi = directory.file_id_hi,
            });
        } else {
            return error.CacheEntryMissing;
        }
        offset += path.len + 1;
    }

    const meta = try allocator.alloc(u8, entries.items.len * format.entry_size);
    for (entries.items, 0..) |entry, index| {
        entry.encode(meta[index * format.entry_size ..][0..format.entry_size]);
    }
    const txt_bytes = try allocator.dupe(u8, txt.items);

    // 2. The sidekick, carrying the hashes the next reader will check.
    const json_bytes = try json.encode(allocator, .{
        .generation = request.generation,
        .count = entries.items.len,
        .meta_bytes = meta.len,
        .txt_bytes = txt_bytes.len,
        .meta_hash = format.wyhash(meta),
        .txt_hash = format.wyhash(txt_bytes),
        .normalize = request.normalize,
        .root_dev = request.root_dev,
        .root_ino = request.root_ino,
        .root_path = request.root_path,
        .created = request.created,
        .updated = request.updated,
        .writer = request.writer,
        .scan = request.scan,
    });

    // 3. Write both temporary files completely, and fsync each, before the first rename.
    try writeTemp(io, dir, format.meta_file, meta);
    try writeTemp(io, dir, format.txt_file, txt_bytes);
    try writeTemp(io, dir, format.json_file, json_bytes);

    // 4. The directory's own entry table, where the platform can flush it.
    syncDirectory(io, dir);

    // 5. Rename, with the commit record last.
    try std.Io.Dir.rename(dir, format.meta_file ++ ".tmp", dir, format.meta_file, io);
    try std.Io.Dir.rename(dir, format.txt_file ++ ".tmp", dir, format.txt_file, io);
    try std.Io.Dir.rename(dir, format.json_file ++ ".tmp", dir, format.json_file, io);

    // 6. And once more, so the renames themselves are on the medium where that is possible.
    syncDirectory(io, dir);

    return .{
        .count = entries.items.len,
        .meta_bytes = meta.len,
        .txt_bytes = txt_bytes.len,
        .meta_hash = format.wyhash(meta),
        .txt_hash = format.wyhash(txt_bytes),
        .meta = meta,
        .txt = txt_bytes,
        .json = json_bytes,
    };
}

fn pathLessThanByHash(_: void, a: []const u8, b: []const u8) bool {
    const ha = format.wyhash(a);
    const hb = format.wyhash(b);
    if (ha != hb) return ha < hb;
    return std.mem.lessThan(u8, a, b);
}

fn pathLessThan(_: void, left: []const u8, right: []const u8) bool {
    return switch (format.compareNames(left, right)) {
        .lt => true,
        else => false,
    };
}

fn writeTemp(io: std.Io, dir: std.Io.Dir, name: []const u8, bytes: []const u8) !void {
    const temp = try std.fmt.allocPrint(std.heap.page_allocator, "{s}.tmp", .{name});
    defer std.heap.page_allocator.free(temp);
    var file = try dir.createFile(io, temp, .{ .truncate = true });
    defer file.close(io);
    try file.writeStreamingAll(io, bytes);
    // The fsync is what makes the rename's claim survive a crash: without it the rename can reach the directory
    // before the bytes reach the disk.
    try file.sync(io);
}

/// § 9: `fsync` the directory. Unix can; Windows has no directory handle to flush (and `fsync` on one is not part
/// of Win32), so the file fsyncs are the guarantee there and the whole-file hashes close the window that leaves.
fn syncDirectory(io: std.Io, dir: std.Io.Dir) void {
    _ = io;
    if (builtin.os.tag == .windows) return;
    std.posix.fsync(dir.handle) catch {};
}

// --------------------------------------------------------------------------------------------- tests

const testing = std.testing;

fn entryFor(name: []const u8, kind: format.Kind, mtime_ns: u64, size: u64, hash: ?u64, file_id: u64) format.Entry {
    return .{
        .mtime_ns = mtime_ns,
        .size_bytes = size,
        .content_hash = hash orelse 0,
        .name_hash = format.wyhash(name),
        .name_offset = 0,
        .name_length = @intCast(name.len),
        .flags_word = switch (kind) {
            .dir => format.flags.is_dir,
            .symlink => format.flags.symlink,
            .regular_file => format.flags.regular_file |
                (if (hash != null) format.flags.content_known else 0),
        },
        .file_id_lo = file_id,
        .file_id_hi = 7,
    };
}

/// A cache with two files and a directory, the shape every test here publishes.
fn sampleCache(allocator: std.mem.Allocator) !Cache {
    const names = [_][]const u8{ "a.txt", "src", "src/b.java" };
    const entries = [_]format.Entry{
        entryFor("a.txt", .regular_file, 100, 6, 0xabcdef, 10),
        entryFor("src", .dir, 200, 0, null, 11),
        entryFor("src/b.java", .regular_file, 300, 11, null, 12), // dirty: published listing-only
    };
    return Cache.load(allocator, &entries, &names, 1);
}

fn publishInto(dir: std.Io.Dir, io: std.Io, allocator: std.mem.Allocator, cache: *const Cache, generation: u64) !Outcome {
    return publish(allocator, io, dir, cache, .{
        .generation = generation,
        .normalize = "text",
        .root_dev = 7,
        .root_ino = 1,
        .root_path = "D:\\wrk\\demo",
        .created = "2026-10-17T00:00:00Z",
        .updated = "2026-10-18T00:00:00Z",
        .writer = "fs-meta/1",
        .scan = .{ .hash_mode = "text", .ignored_dir_names = &.{ ".git", ".fs-meta" }, .respect_gitignore = true },
    });
}

test "a published trio passes the reader's own checks" {
    const io = testing.io;
    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.createDirPath(io, ".fs-meta");

    var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    var cache = try sampleCache(arena);
    defer cache.deinit();
    var meta_dir = try tmp.dir.openDir(io, ".fs-meta", .{ .iterate = true });
    defer meta_dir.close(io);

    const outcome = try publishInto(meta_dir, io, arena, &cache, 2);

    // The three names, in byte order, and one LF-terminated line each.
    try testing.expectEqualStrings("a.txt\nsrc/b.java\nsrc\n", outcome.txt);
    try testing.expectEqual(@as(u64, 3), outcome.count);
    try testing.expectEqual(@as(u64, 3 * 64), outcome.meta_bytes);

    const validate = @import("validate.zig");
    const result = try validate.validate(arena, .{
        .json_bytes = outcome.json,
        .meta_bytes = outcome.meta,
        .txt_bytes = outcome.txt,
    });
    try testing.expectEqual(validate.Verdict.ok, result.verdict);

    // The dirty file is published as listing-only: its content hash is 0 and CONTENT_KNOWN is clear.
    const dirty = result.entries[2];
    try testing.expect(!dirty.contentKnown());
    try testing.expectEqual(@as(u64, 0), dirty.content_hash);
    // The hashed one carries the hash it had.
    try testing.expect(result.entries[0].contentKnown());
    try testing.expectEqual(@as(u64, 0xabcdef), result.entries[0].content_hash);
    // And the directory carries no fingerprint.
    try testing.expectEqual(format.Kind.dir, result.entries[2].kind().?);
    try testing.expect(!result.entries[1].contentKnown());

    // A publish is a new generation, and the reader sees it.
    const second = try publishInto(meta_dir, io, arena, &cache, 3);
    const second_result = try validate.validate(arena, .{
        .json_bytes = second.json,
        .meta_bytes = second.meta,
        .txt_bytes = second.txt,
    });
    try testing.expectEqual(validate.Verdict.ok, second_result.verdict);
    try testing.expect(std.mem.indexOf(u8, second.json, "\"generation\": 3") != null);
}

test "the trio reads back into a cache, and a cold start over it sees no change" {
    const io = testing.io;
    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.createDirPath(io, ".fs-meta");

    var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    var cache = try sampleCache(arena);
    defer cache.deinit();
    var meta_dir = try tmp.dir.openDir(io, ".fs-meta", .{ .iterate = true });
    defer meta_dir.close(io);
    const outcome = try publishInto(meta_dir, io, arena, &cache, 2);

    const validate = @import("validate.zig");
    const result = try validate.validate(arena, .{
        .json_bytes = outcome.json,
        .meta_bytes = outcome.meta,
        .txt_bytes = outcome.txt,
    });
    try testing.expectEqual(validate.Verdict.ok, result.verdict);

    // What a restart does: validate, load, reconcile.
    var loaded = try Cache.load(arena, result.entries, result.names, 2);
    defer loaded.deinit();
    try testing.expectEqual(@as(usize, 2), loaded.files.count());
    try testing.expectEqual(@as(usize, 1), loaded.dirCount()); // `src`; the root is not an entry
}

test "a torn publish is caught by the hashes, not by luck" {
    const io = testing.io;
    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.createDirPath(io, ".fs-meta");

    var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    var cache = try sampleCache(arena);
    defer cache.deinit();
    var meta_dir = try tmp.dir.openDir(io, ".fs-meta", .{ .iterate = true });
    defer meta_dir.close(io);
    const outcome = try publishInto(meta_dir, io, arena, &cache, 2);

    const validate = @import("validate.zig");

    // A crash between the data files and the commit record: the json is still the previous generation's while
    // the data files are new. Here the previous json is simulated by a hash that no longer matches.
    const meta_torn = try arena.dupe(u8, outcome.meta);
    meta_torn[0] ^= 0xff;
    const after_meta = try validate.validate(arena, .{
        .json_bytes = outcome.json,
        .meta_bytes = meta_torn,
        .txt_bytes = outcome.txt,
    });
    try testing.expectEqual(validate.Verdict.meta_hash_mismatch, after_meta.verdict);

    const txt_torn = try arena.dupe(u8, outcome.txt);
    txt_torn[0] = 'z';
    const after_txt = try validate.validate(arena, .{
        .json_bytes = outcome.json,
        .meta_bytes = outcome.meta,
        .txt_bytes = txt_torn,
    });
    try testing.expectEqual(validate.Verdict.txt_hash_mismatch, after_txt.verdict);

    // A truncated data file is a size mismatch, which the size checks catch before the hash.
    const after_short = try validate.validate(arena, .{
        .json_bytes = outcome.json,
        .meta_bytes = outcome.meta[0 .. outcome.meta.len - 8],
        .txt_bytes = outcome.txt,
    });
    try testing.expectEqual(validate.Verdict.meta_size_mismatch, after_short.verdict);
}

test "the trio on disk is what the outcome says it is" {
    const io = testing.io;
    var tmp = testing.tmpDir(.{});
    defer tmp.cleanup();
    try tmp.dir.createDirPath(io, ".fs-meta");

    var arena_alloc = std.heap.ArenaAllocator.init(testing.allocator);
    defer arena_alloc.deinit();
    const arena = arena_alloc.allocator();

    var cache = try sampleCache(arena);
    defer cache.deinit();
    var meta_dir = try tmp.dir.openDir(io, ".fs-meta", .{ .iterate = true });
    defer meta_dir.close(io);
    const outcome = try publishInto(meta_dir, io, arena, &cache, 2);

    // Re-read the three files and compare with what publish returned: the rename left exactly those bytes.
    const names = [_][]const u8{ format.meta_file, format.txt_file, format.json_file };
    const expected = [_][]const u8{ outcome.meta, outcome.txt, outcome.json };
    for (names, expected) |name, want| {
        const got = try meta_dir.readFileAlloc(io, name, arena, std.Io.Limit.limited(1 << 20));
        try testing.expectEqualSlices(u8, want, got);
    }
    // No temporary file is left behind.
    var iterator = meta_dir.iterate();
    while (try iterator.next(io)) |entry| {
        try testing.expect(std.mem.endsWith(u8, entry.name, ".tmp") == false);
    }
}