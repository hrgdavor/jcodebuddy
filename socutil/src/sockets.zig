//! Discovery of unix-domain sockets in configured directories.
//!
//! A socket is recognised differently per platform:
//!  * on POSIX the directory entry (or a stat of it) reports
//!    `File.Kind.unix_domain_socket` authoritatively;
//!  * on Windows an AF_UNIX socket is a reparse point, so a directory entry
//!    shows up as a symlink and stat returns `IO_REPARSE_TAG_NOT_HANDLED`.
//!    There is no portable way to tell it from an ordinary symlink without
//!    connecting, so on Windows the name convention (`.sock` / `.socket`) is
//!    used and documented in the README.

const std = @import("std");
const builtin = @import("builtin");
const Io = std.Io;

pub const Entry = struct {
    /// The configured directory, exactly as given on the command line.
    dir: []const u8,
    /// The entry name within that directory.
    name: []const u8,
    /// `dir` joined with `name`, as a path the client may pass back to us.
    path: []const u8,
};

pub const Scan = struct {
    allocator: std.mem.Allocator,
    entries: std.ArrayList(Entry) = .empty,

    pub fn deinit(self: *Scan) void {
        for (self.entries.items) |entry| {
            self.allocator.free(entry.dir);
            self.allocator.free(entry.name);
            self.allocator.free(entry.path);
        }
        self.entries.deinit(self.allocator);
    }

    pub fn containsPath(self: *const Scan, path: []const u8) bool {
        for (self.entries.items) |entry| {
            if (std.mem.eql(u8, entry.path, path)) return true;
        }
        return false;
    }
};

/// Scan `dirs` for unix-domain sockets, sorted by path.
///
/// Directories that cannot be opened are reported through `std.log.warn` and
/// skipped: one bad default directory must not hide the sockets in the rest.
pub fn scan(
    allocator: std.mem.Allocator,
    io: Io,
    dirs: []const []const u8,
) std.mem.Allocator.Error!Scan {
    var result: Scan = .{ .allocator = allocator };
    errdefer result.deinit();

    for (dirs) |dir_path| {
        var dir = openDir(io, dir_path) catch |err| {
            std.log.warn("socutil: cannot scan {s}: {s}", .{ dir_path, @errorName(err) });
            continue;
        };
        defer dir.close(io);

        var it = dir.iterate();
        while (it.next(io) catch |err| {
            std.log.warn("socutil: cannot read {s}: {s}", .{ dir_path, @errorName(err) });
            break;
        }) |dirent| {
            if (!isSocketEntry(io, dir, dirent)) continue;

            const dir_copy = try allocator.dupe(u8, dir_path);
            errdefer allocator.free(dir_copy);
            const name_copy = try allocator.dupe(u8, dirent.name);
            errdefer allocator.free(name_copy);
            const path = try Io.Dir.path.join(allocator, &.{ dir_path, dirent.name });
            errdefer allocator.free(path);

            try result.entries.append(allocator, .{
                .dir = dir_copy,
                .name = name_copy,
                .path = path,
            });
        }
    }

    std.mem.sort(Entry, result.entries.items, {}, lessThanPath);
    return result;
}

fn lessThanPath(_: void, a: Entry, b: Entry) bool {
    return std.mem.lessThan(u8, a.path, b.path);
}

fn openDir(io: Io, path: []const u8) !Io.Dir {
    if (Io.Dir.path.isAbsolute(path)) {
        return Io.Dir.openDirAbsolute(io, path, .{ .iterate = true });
    }
    return Io.Dir.cwd().openDir(io, path, .{ .iterate = true });
}

fn isSocketEntry(io: Io, dir: Io.Dir, dirent: Io.Dir.Entry) bool {
    switch (dirent.kind) {
        .unix_domain_socket => return true,
        .unknown => return statSaysSocket(io, dir, dirent.name),
        .sym_link => switch (builtin.os.tag) {
            // Windows AF_UNIX sockets are reparse points; stat cannot follow
            // them, so fall back to the documented naming convention.
            .windows => return hasSocketSuffix(dirent.name),
            else => return statSaysSocket(io, dir, dirent.name),
        },
        else => return false,
    }
}

fn statSaysSocket(io: Io, dir: Io.Dir, name: []const u8) bool {
    const stat = dir.statFile(io, name, .{}) catch return false;
    return stat.kind == .unix_domain_socket;
}

pub fn hasSocketSuffix(name: []const u8) bool {
    return std.ascii.endsWithIgnoreCase(name, ".sock") or
        std.ascii.endsWithIgnoreCase(name, ".socket");
}

test "socket suffix convention" {
    try std.testing.expect(hasSocketSuffix("app.sock"));
    try std.testing.expect(hasSocketSuffix("APP.SOCK"));
    try std.testing.expect(hasSocketSuffix("x.socket"));
    try std.testing.expect(!hasSocketSuffix("app.socketx"));
    try std.testing.expect(!hasSocketSuffix("app"));
}
