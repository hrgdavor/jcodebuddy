//! socutil - a tiny browser UI for unix-domain sockets.
//!
//! It serves an embedded HTML/JS page, lists unix sockets from configured
//! directories, and relays one to the browser: a WebSocket byte tunnel by
//! default, or a Server-Sent Events tail with commands over a plain POST.

const std = @import("std");
const builtin = @import("builtin");
const Io = std.Io;
const net = Io.net;

const server = @import("server.zig");

const default_port: u16 = 8765;

const Config = struct {
    host: []const u8 = "127.0.0.1",
    port: u16 = default_port,
    dirs: std.ArrayList([]const u8) = .empty,
};

pub fn main(init: std.process.Init) !void {
    const arena = init.arena.allocator();
    const io = init.io;
    const args = try init.minimal.args.toSlice(arena);

    var config: Config = .{};
    parseArgs(args, arena, &config) catch |err| switch (err) {
        error.HelpRequested => {
            printUsage();
            return;
        },
        else => {
            std.debug.print("socutil: {s}\n\n", .{@errorName(err)});
            printUsage();
            std.process.exit(2);
        },
    };

    if (config.dirs.items.len == 0) try addDefaultDirs(arena, init, &config.dirs);
    const dirs = try config.dirs.toOwnedSlice(arena);

    const app = try arena.create(server.App);
    app.* = .{
        .gpa = init.gpa,
        .io = io,
        .dirs = dirs,
    };

    const address = net.IpAddress.resolve(io, config.host, config.port) catch |err| {
        std.debug.print("socutil: cannot resolve {s}: {s}\n", .{ config.host, @errorName(err) });
        std.process.exit(2);
    };
    var listener = address.listen(io, .{ .reuse_address = true }) catch |err| {
        std.debug.print(
            "socutil: cannot listen on {s}:{d}: {s}\n",
            .{ config.host, config.port, @errorName(err) },
        );
        std.process.exit(1);
    };
    defer listener.deinit(io);

    try printListenUrl(io, config.host, listener.socket.address.getPort());

    while (true) {
        const stream = listener.accept(io) catch |err| {
            if (err == error.Canceled) return;
            std.log.err("socutil: accept failed: {s}", .{@errorName(err)});
            io.sleep(.fromMilliseconds(20), .awake) catch {};
            continue;
        };
        const thread = std.Thread.spawn(.{}, server.connectionThread, .{ app, stream }) catch |err| {
            std.log.err("socutil: cannot spawn connection thread: {s}", .{@errorName(err)});
            stream.close(io);
            continue;
        };
        thread.detach();
    }
}

fn parseArgs(args: []const [:0]const u8, arena: std.mem.Allocator, config: *Config) !void {
    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        const arg: []const u8 = args[i];
        if (std.mem.eql(u8, arg, "-h") or std.mem.eql(u8, arg, "--help")) {
            return error.HelpRequested;
        } else if (std.mem.eql(u8, arg, "--listen") or std.mem.eql(u8, arg, "--host")) {
            i += 1;
            if (i >= args.len) return error.MissingValue;
            config.host = args[i];
        } else if (std.mem.eql(u8, arg, "--port")) {
            i += 1;
            if (i >= args.len) return error.MissingValue;
            config.port = std.fmt.parseInt(u16, args[i], 10) catch return error.InvalidPort;
        } else if (std.mem.eql(u8, arg, "--dir")) {
            i += 1;
            if (i >= args.len) return error.MissingValue;
            try config.dirs.append(arena, args[i]);
        } else if (arg.len != 0 and arg[0] == '-') {
            return error.UnknownOption;
        } else {
            try config.dirs.append(arena, arg);
        }
    }
}

fn addDefaultDirs(arena: std.mem.Allocator, init: std.process.Init, dirs: *std.ArrayList([]const u8)) !void {
    if (init.environ_map.get("XDG_RUNTIME_DIR")) |path| try appendUnique(arena, dirs, path);
    if (builtin.os.tag == .windows) {
        if (init.environ_map.get("TEMP")) |path| try appendUnique(arena, dirs, path);
        if (init.environ_map.get("TMP")) |path| try appendUnique(arena, dirs, path);
    } else {
        try appendUnique(arena, dirs, "/tmp");
        try appendUnique(arena, dirs, "/var/run");
        try appendUnique(arena, dirs, "/run");
    }
    try appendUnique(arena, dirs, ".");
}

fn appendUnique(arena: std.mem.Allocator, dirs: *std.ArrayList([]const u8), path: []const u8) !void {
    for (dirs.items) |existing| {
        if (std.mem.eql(u8, existing, path)) return;
    }
    try dirs.append(arena, path);
}

fn printListenUrl(io: Io, host: []const u8, port: u16) !void {
    var buffer: [1024]u8 = undefined;
    var stdout: Io.File.Writer = .init(.stdout(), io, &buffer);
    const writer = &stdout.interface;

    try writer.writeAll("socutil listening on http://");
    if (std.mem.indexOfScalar(u8, host, ':') != null and !std.mem.startsWith(u8, host, "[")) {
        try writer.print("[{s}]", .{host});
    } else {
        try writer.writeAll(host);
    }
    try writer.print(":{d}/\n", .{port});
    try writer.flush();
}

fn printUsage() void {
    std.debug.print(
        \\usage: socutil [options] [directory...]
        \\
        \\Serves a small browser UI that lists unix-domain sockets found in the
        \\given directories and relays one of them, over a WebSocket byte tunnel
        \\or a Server-Sent Events tail with commands sent by POST.
        \\
        \\options:
        \\  --listen <host>   address to bind (default 127.0.0.1)
        \\  --port <port>     TCP port to bind (default 8765, 0 = any free port)
        \\  --dir <path>      directory to scan; may be repeated
        \\  -h, --help        show this help
        \\
        \\With no directory arguments, platform defaults are used:
        \\  POSIX:   $XDG_RUNTIME_DIR, /tmp, /var/run, /run, .
        \\  Windows: %TEMP%, %TMP%, .
        \\
    , .{});
}

test {
    _ = @import("server.zig");
    _ = @import("sockets.zig");
}
