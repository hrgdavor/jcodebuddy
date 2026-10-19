// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
//! `files.json` — the sidekick that makes the binary file headerless (§ 7 of `FORMAT.md`).
//!
//! Reading keeps the whole document as a `std.json.Value`: the spec says unknown keys are preserved by a
//! rewriter, and a typed struct would drop them. Writing is hand-rolled rather than delegated to `std.json`
//! because the emitted bytes are part of what the conformance vectors pin — key order and indentation included —
//! and a formatter change must not silently move them.

const std = @import("std");
const format = @import("format.zig");

pub const Value = std.json.Value;
pub const Map = std.json.ObjectMap;

/// Thrown for a document that is well-formed JSON but not the document this format describes (§ 8 check 1).
pub const Invalid = error{Invalid, OutOfMemory};

pub const ScanFields = struct {
    hash_mode: []const u8,
    ignored_dir_names: []const []const u8,
    respect_gitignore: bool,
};

pub const Fields = struct {
    generation: u64,
    count: u64,
    meta_bytes: u64,
    txt_bytes: u64,
    meta_hash: u64,
    txt_hash: u64,
    /// `"none"`, `"lf"` or `"text"` (§ 12).
    normalize: []const u8,
    root_dev: u64,
    root_ino: u64,
    root_path: ?[]const u8 = null,
    created: []const u8,
    updated: []const u8,
    writer: []const u8,
    scan: ?ScanFields = null,
};

/// The document a publish writes: `FORMAT.md` § 7's keys, in the order a reader sees them.
pub fn encode(gpa: std.mem.Allocator, fields: Fields) ![]u8 {
    var out: std.Io.Writer.Allocating = .init(gpa);
    errdefer out.deinit();
    const writer = &out.writer;

    try writer.writeAll("{\n");
    try writer.writeAll("  \"format\": \"fs-meta\",\n");
    try writer.writeAll("  \"version\": 1,\n");
    try writer.writeAll("  \"entrySize\": 64,\n");
    try writer.writeAll("  \"order\": \"utf8-bytes\",\n");
    try writer.print("  \"generation\": {d},\n", .{fields.generation});
    try writer.print("  \"count\": {d},\n", .{fields.count});
    try writer.print("  \"meta\": {{ \"bytes\": {d}, \"wyhash\": \"{x:0>16}\" }},\n", .{ fields.meta_bytes, fields.meta_hash });
    try writer.print("  \"txt\": {{ \"bytes\": {d}, \"wyhash\": \"{x:0>16}\" }},\n", .{ fields.txt_bytes, fields.txt_hash });

    try writer.writeAll("  \"hash\": { \"content\": \"wyhash64\", \"name\": \"wyhash64\", \"seed\": 0");
    try writer.writeAll(", \"normalize\": ");
    try writeString(writer, fields.normalize);
    try writer.writeAll(" },\n");

    try writer.print("  \"root\": {{ \"dev\": {d}, \"ino\": {d} }},\n", .{ fields.root_dev, fields.root_ino });
    if (fields.root_path) |root_path| {
        try writer.writeAll("  \"rootPath\": ");
        try writeString(writer, root_path);
        try writer.writeAll(",\n");
    }
    if (fields.scan) |scan| {
        try writer.writeAll("  \"scan\": { \"hashMode\": ");
        try writeString(writer, scan.hash_mode);
        try writer.writeAll(", \"ignoredDirectoryNames\": [");
        for (scan.ignored_dir_names, 0..) |name, index| {
            if (index != 0) try writer.writeAll(", ");
            try writeString(writer, name);
        }
        try writer.writeAll("], \"respectGitignore\": ");
        try writer.writeAll(if (scan.respect_gitignore) "true" else "false");
        try writer.writeAll(" },\n");
    }
    try writer.writeAll("  \"created\": ");
    try writeString(writer, fields.created);
    try writer.writeAll(",\n  \"updated\": ");
    try writeString(writer, fields.updated);
    try writer.writeAll(",\n  \"writer\": ");
    try writeString(writer, fields.writer);
    try writer.writeAll("\n}\n");

    return out.toOwnedSlice();
}

fn writeString(writer: *std.Io.Writer, s: []const u8) !void {
    try writer.writeByte('"');
    for (s) |byte| {
        switch (byte) {
            '"' => try writer.writeAll("\\\""),
            '\\' => try writer.writeAll("\\\\"),
            '\n' => try writer.writeAll("\\n"),
            '\r' => try writer.writeAll("\\r"),
            '\t' => try writer.writeAll("\\t"),
            else => {
                if (byte < 0x20) {
                    try writer.print("\\u{x:0>4}", .{byte});
                } else {
                    try writer.writeByte(byte);
                }
            },
        }
    }
    try writer.writeByte('"');
}

// --------------------------------------------------------------------------------------------- reading

pub fn parse(gpa: std.mem.Allocator, bytes: []const u8) !std.json.Parsed(Value) {
    return std.json.parseFromSlice(Value, gpa, bytes, .{});
}

fn field(map: *const Map, key: []const u8) Invalid!Value {
    return map.get(key) orelse error.Invalid;
}

/// A required object member, as a pointer **into** the document. The map is self-referential once it is
/// initialised, so a pointer to a copy of it (a by-value `Value`) is not a usable map — hence `getPtr`.
pub fn objectField(map: *const Map, key: []const u8) Invalid!*const Map {
    const value = map.getPtr(key) orelse return error.Invalid;
    return switch (value.*) {
        .object => |*object| object,
        else => error.Invalid,
    };
}

pub fn stringField(map: *const Map, key: []const u8) Invalid![]const u8 {
    return switch (try field(map, key)) {
        .string => |s| s,
        else => error.Invalid,
    };
}

pub fn optionalString(map: *const Map, key: []const u8) ?[]const u8 {
    return switch (map.get(key) orelse return null) {
        .string => |s| s,
        else => null,
    };
}

pub fn intField(map: *const Map, key: []const u8) Invalid!u64 {
    const value = try field(map, key);
    return switch (value) {
        .integer => |i| if (i < 0) error.Invalid else @intCast(i),
        else => error.Invalid,
    };
}

pub fn optionalInt(map: *const Map, key: []const u8) ?u64 {
    return switch (map.get(key) orelse return null) {
        .integer => |i| if (i < 0) null else @intCast(i),
        else => null,
    };
}

/// A `wyhash` member: exactly sixteen lowercase hex characters (§ 8 check 1).
pub fn hexField(map: *const Map, key: []const u8) Invalid!u64 {
    const text = try stringField(map, key);
    if (text.len != 16) return error.Invalid;
    var value: u64 = 0;
    for (text) |byte| {
        const digit: u64 = switch (byte) {
            '0'...'9' => byte - '0',
            'a'...'f' => byte - 'a' + 10,
            else => return error.Invalid,
        };
        value = (value << 4) | digit;
    }
    return value;
}

pub fn boolField(map: *const Map, key: []const u8) Invalid!bool {
    return switch (try field(map, key)) {
        .bool => |b| b,
        else => error.Invalid,
    };
}

test "the emitted document has the keys § 7 requires, and reads back" {
    const gpa = std.testing.allocator;
    const bytes = try encode(gpa, .{
        .generation = 3,
        .count = 2,
        .meta_bytes = 128,
        .txt_bytes = 17,
        .meta_hash = 0x7b68e5cf8b010000,
        .txt_hash = 0x1234abcd5678ef90,
        .normalize = "text",
        .root_dev = 16_777_220,
        .root_ino = 1,
        .root_path = "D:\\wrk\\demo",
        .created = "2026-10-17T00:00:00Z",
        .updated = "2026-10-17T00:00:01Z",
        .writer = "fs-meta/1",
        .scan = .{ .hash_mode = "text", .ignored_dir_names = &.{ ".git", ".fs-meta" }, .respect_gitignore = true },
    });
    defer gpa.free(bytes);

    var parsed = try parse(gpa, bytes);
    defer parsed.deinit();
    const root = &parsed.value.object;

    try std.testing.expectEqualStrings("fs-meta", try stringField(root, "format"));
    try std.testing.expectEqual(@as(u64, 1), try intField(root, "version"));
    try std.testing.expectEqual(@as(u64, 64), try intField(root, "entrySize"));
    try std.testing.expectEqual(@as(u64, 3), try intField(root, "generation"));
    try std.testing.expectEqual(@as(u64, 2), try intField(root, "count"));
    const meta = try objectField(root, "meta");
    try std.testing.expectEqual(@as(u64, 128), try intField(meta, "bytes"));
    try std.testing.expectEqual(@as(u64, 0x7b68e5cf8b010000), try hexField(meta, "wyhash"));
    const txt = try objectField(root, "txt");
    try std.testing.expectEqual(@as(u64, 17), try intField(txt, "bytes"));
    try std.testing.expectEqual(@as(u64, 0x1234abcd5678ef90), try hexField(txt, "wyhash"));
    const hash = try objectField(root, "hash");
    try std.testing.expectEqualStrings("wyhash64", try stringField(hash, "content"));
    try std.testing.expectEqualStrings("wyhash64", try stringField(hash, "name"));
    try std.testing.expectEqual(@as(u64, 0), try intField(hash, "seed"));
    try std.testing.expectEqualStrings("text", try stringField(hash, "normalize"));
    const root_identity = try objectField(root, "root");
    try std.testing.expectEqual(@as(u64, 16_777_220), try intField(root_identity, "dev"));
    try std.testing.expectEqual(@as(u64, 1), try intField(root_identity, "ino"));
    try std.testing.expectEqualStrings("D:\\wrk\\demo", optionalString(root, "rootPath").?);
    try std.testing.expectEqualStrings("fs-meta/1", try stringField(root, "writer"));

    // A `wyhash` member that is not sixteen lowercase hex characters is not this document.
    try std.testing.expectError(error.Invalid, hexField(meta, "nope"));
}

test "unknown keys survive a read and a rewrite of the same value" {
    const gpa = std.testing.allocator;
    var parsed = try parse(gpa,
        \\{"format":"fs-meta","version":1,"entrySize":64,"count":0,
        \\ "meta":{"bytes":0,"wyhash":"0000000000000000"},
        \\ "txt":{"bytes":0,"wyhash":"0000000000000000"},
        \\ "hash":{"content":"wyhash64","name":"wyhash64","seed":0},
        \\ "somethingNew":{"kept":true}}
    );
    defer parsed.deinit();
    const root = &parsed.value.object;
    try std.testing.expect(root.get("somethingNew") != null);
    try std.testing.expectEqual(@as(u64, 0), try intField(root, "count"));
}

test "a string is escaped correctly" {
    const gpa = std.testing.allocator;
    const bytes = try encode(gpa, .{
        .generation = 0,
        .count = 0,
        .meta_bytes = 0,
        .txt_bytes = 0,
        .meta_hash = 0,
        .txt_hash = 0,
        .normalize = "none",
        .root_dev = 0,
        .root_ino = 0,
        .root_path = "C:\\a \"quoted\" path\nnext",
        .created = "x",
        .updated = "y",
        .writer = "z",
    });
    defer gpa.free(bytes);
    var parsed = try parse(gpa, bytes);
    defer parsed.deinit();
    try std.testing.expectEqualStrings("C:\\a \"quoted\" path\nnext", optionalString(&parsed.value.object, "rootPath").?);
}
