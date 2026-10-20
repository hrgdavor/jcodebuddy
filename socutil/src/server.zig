//! HTTP server, request routing, and the SSE relay sessions.
//!
//! The wire protocol deliberately uses Server-Sent Events for the socket ->
//! browser direction and a plain POST for browser -> socket. SSE has no frame
//! format of its own (unlike WebSocket), and the session id lets the UI post
//! commands back to the same live connection.
//!
//! Threading model:
//!   * one accept loop (the main thread) spawns one detached thread per HTTP
//!     connection;
//!   * an SSE connection additionally spawns a reader thread which blocks on
//!     the unix socket and queues its output for the connection thread;
//!   * the connection thread polls that queue, writes SSE events, and emits a
//!     keepalive every ~15s. A failed keepalive write is how a vanished
//!     browser is detected, which then shuts the unix socket down.

const std = @import("std");
const Io = std.Io;
const http = std.http;
const net = Io.net;

const sockets = @import("sockets.zig");

/// How much unread socket output we are willing to buffer per session.
const max_pending_output = 1 << 20;
/// How often the SSE connection thread checks the queue and keepalive timer.
const poll_interval = Io.Duration.fromMilliseconds(50);
/// Keeps proxies and dead sockets honest; ~300 * 50ms.
const keepalive_ticks = 300;
/// Read buffer for the unix socket (per SSE session reader thread).
const socket_read_buffer_len = 8192;
/// SSE payload chunk per event.
const sse_chunk_len = 4096;

pub const App = struct {
    gpa: std.mem.Allocator,
    io: Io,
    dirs: []const []const u8,
    sessions: std.StringHashMapUnmanaged(*Session) = .empty,
    sessions_mutex: Io.Mutex = .init,
};

pub const Session = struct {
    app: *App,
    id: []u8,
    path: []u8,
    stream: net.Stream,
    /// One reference for the HTTP handler, one for the reader thread, plus one
    /// for each in-flight REST call. Guarded by `app.sessions_mutex`.
    refs: usize = 1,
    out_mutex: Io.Mutex = .init,
    out: std.ArrayList(u8) = .empty,
    out_done: bool = false,
    out_reason: []const u8 = "eof",
    write_mutex: Io.Mutex = .init,
    closed: std.atomic.Value(bool) = .init(false),

    fn release(self: *Session) void {
        const app = self.app;
        app.sessions_mutex.lockUncancelable(app.io);
        const remaining = self.refs - 1;
        self.refs = remaining;
        app.sessions_mutex.unlock(app.io);
        if (remaining != 0) return;

        self.out.deinit(app.gpa);
        app.gpa.free(self.id);
        app.gpa.free(self.path);
        app.gpa.destroy(self);
    }

    fn finishOutput(self: *Session, reason: []const u8) void {
        const io = self.app.io;
        self.out_mutex.lockUncancelable(io);
        defer self.out_mutex.unlock(io);
        self.out_done = true;
        self.out_reason = reason;
    }

    fn pushOutput(self: *Session, bytes: []const u8) !void {
        const io = self.app.io;
        self.out_mutex.lockUncancelable(io);
        defer self.out_mutex.unlock(io);

        if (self.out.items.len + bytes.len > max_pending_output) {
            // Keep the newest half: a stalled browser must not make the server
            // grow without bound, and old terminal output is the least useful.
            const keep = max_pending_output / 2;
            if (keep < self.out.items.len) {
                const src = self.out.items.len - keep;
                std.mem.copyForwards(u8, self.out.items[0..keep], self.out.items[src..]);
                self.out.shrinkRetainingCapacity(keep);
            }
        }
        try self.out.appendSlice(self.app.gpa, bytes);
    }

    fn drainOutput(self: *Session, dest: []u8) usize {
        const io = self.app.io;
        self.out_mutex.lockUncancelable(io);
        defer self.out_mutex.unlock(io);

        const n = @min(dest.len, self.out.items.len);
        if (n == 0) return 0;
        @memcpy(dest[0..n], self.out.items[0..n]);

        const remaining = self.out.items.len - n;
        if (remaining != 0) {
            std.mem.copyForwards(u8, self.out.items[0..remaining], self.out.items[n..]);
        }
        self.out.shrinkRetainingCapacity(remaining);
        return n;
    }

    const OutputState = struct {
        done: bool,
        reason: []const u8,
    };

    fn outputState(self: *Session) OutputState {
        const io = self.app.io;
        self.out_mutex.lockUncancelable(io);
        defer self.out_mutex.unlock(io);
        return .{ .done = self.out_done, .reason = self.out_reason };
    }
};

pub fn connectionThread(app: *App, stream: net.Stream) void {
    handleConnection(app, stream) catch |err| {
        std.log.debug("socutil: connection ended: {s}", .{@errorName(err)});
    };
}

fn handleConnection(app: *App, stream: net.Stream) !void {
    defer stream.close(app.io);

    var read_buf: [8192]u8 = undefined;
    var write_buf: [8192]u8 = undefined;
    var reader = stream.reader(app.io, &read_buf);
    var writer = stream.writer(app.io, &write_buf);
    var server = http.Server.init(&reader.interface, &writer.interface);

    // Every response is sent with `connection: close`, so one request per
    // connection is enough and keeps the lifecycle trivial.
    var request = try server.receiveHead();
    try route(app, &request);
}

fn route(app: *App, req: *http.Server.Request) !void {
    const target = req.head.target;
    const query_pos = std.mem.indexOfScalar(u8, target, '?');
    const path = target[0..(query_pos orelse target.len)];
    const query = if (query_pos) |i| target[i + 1 ..] else "";

    if (std.mem.eql(u8, path, "/api/sockets")) {
        if (!getLike(req)) return methodNotAllowed(req, "GET, HEAD");
        return handleSockets(app, req);
    }
    if (std.mem.eql(u8, path, "/api/events")) {
        if (!getLike(req)) return methodNotAllowed(req, "GET");
        return handleEvents(app, req, query);
    }
    if (std.mem.eql(u8, path, "/api/input")) {
        if (req.head.method != .POST) return methodNotAllowed(req, "POST");
        return handleInput(app, req, query);
    }
    if (std.mem.eql(u8, path, "/api/close")) {
        if (req.head.method != .POST) return methodNotAllowed(req, "POST");
        return handleClose(app, req, query);
    }
    if (std.mem.eql(u8, path, "/favicon.ico")) {
        if (!getLike(req)) return methodNotAllowed(req, "GET, HEAD");
        return req.respond("", .{ .status = .no_content, .keep_alive = false });
    }

    if (assetFor(path)) |asset| {
        if (!getLike(req)) return methodNotAllowed(req, "GET, HEAD");
        return respond(req, .ok, asset.content_type, asset.body);
    }

    return respond(req, .not_found, "text/plain; charset=utf-8", "not found\n");
}

fn getLike(req: *const http.Server.Request) bool {
    return req.head.method == .GET or req.head.method == .HEAD;
}

fn handleSockets(app: *App, req: *http.Server.Request) !void {
    var scan = sockets.scan(app.gpa, app.io, app.dirs) catch |err| {
        std.log.err("socutil: socket scan failed: {s}", .{@errorName(err)});
        return respondError(req, .internal_server_error, "scan failed");
    };
    defer scan.deinit();

    var aw: Io.Writer.Allocating = .init(app.gpa);
    defer aw.deinit();
    const w = &aw.writer;

    try w.writeAll("{\"dirs\":[");
    for (app.dirs, 0..) |dir, i| {
        if (i != 0) try w.writeByte(',');
        try writeJsonString(w, dir);
    }
    try w.writeAll("],\"sockets\":[");
    for (scan.entries.items, 0..) |entry, i| {
        if (i != 0) try w.writeByte(',');
        try w.writeAll("{\"name\":");
        try writeJsonString(w, entry.name);
        try w.writeAll(",\"dir\":");
        try writeJsonString(w, entry.dir);
        try w.writeAll(",\"path\":");
        try writeJsonString(w, entry.path);
        try w.writeByte('}');
    }
    try w.writeAll("]}");

    return respond(req, .ok, "application/json", w.buffered());
}

fn handleEvents(app: *App, req: *http.Server.Request, query: []const u8) !void {
    var arena = std.heap.ArenaAllocator.init(app.gpa);
    defer arena.deinit();
    const a = arena.allocator();

    const q = try parseQuery(a, query);
    const id = q.id orelse return respondError(req, .bad_request, "missing id");
    if (!validSessionId(id)) return respondError(req, .bad_request, "invalid id");
    const requested_path = q.path orelse return respondError(req, .bad_request, "missing path");

    // Only sockets we just discovered may be connected to: the client cannot
    // make the server connect to an arbitrary path.
    var scan = sockets.scan(app.gpa, app.io, app.dirs) catch |err| {
        std.log.err("socutil: socket scan failed: {s}", .{@errorName(err)});
        return respondError(req, .internal_server_error, "scan failed");
    };
    defer scan.deinit();

    var target: ?[]const u8 = null;
    for (scan.entries.items) |entry| {
        if (std.mem.eql(u8, entry.path, requested_path)) {
            target = entry.path;
            break;
        }
    }
    const socket_path = target orelse return respondError(req, .not_found, "socket not found");

    const session = createSession(app, id, socket_path) catch |err| {
        std.log.warn("socutil: cannot connect to {s}: {s}", .{ socket_path, @errorName(err) });
        return respondError(req, .bad_gateway, "cannot connect to socket");
    };

    registerSession(app, session) catch |err| {
        session.stream.close(app.io);
        session.release();
        return switch (err) {
            error.SessionAlreadyExists => respondError(req, .conflict, "session id in use"),
            else => respondError(req, .internal_server_error, "cannot register session"),
        };
    };

    const reader_thread = std.Thread.spawn(.{}, readerMain, .{session}) catch |err| {
        std.log.err("socutil: cannot spawn socket reader: {s}", .{@errorName(err)});
        unregisterSession(app, session);
        dropReaderRef(app, session);
        session.stream.close(app.io);
        session.release();
        return respondError(req, .internal_server_error, "cannot start reader");
    };

    var body_buf: [1024]u8 = undefined;
    var body = req.respondStreaming(&body_buf, .{ .respond_options = .{
        .keep_alive = false,
        .extra_headers = &.{
            .{ .name = "content-type", .value = "text/event-stream; charset=utf-8" },
            .{ .name = "cache-control", .value = "no-cache, no-transform" },
            .{ .name = "x-accel-buffering", .value = "no" },
        },
    } }) catch |err| {
        cleanupSession(app, session, reader_thread);
        return err;
    };

    {
        var aw: Io.Writer.Allocating = .init(app.gpa);
        defer aw.deinit();
        const w = &aw.writer;
        try w.writeAll("event: ready\ndata: {\"id\":");
        try writeJsonString(w, id);
        try w.writeAll(",\"path\":");
        try writeJsonString(w, socket_path);
        try w.writeAll("}\n\n");
        sseSend(&body, w.buffered()) catch {
            cleanupSession(app, session, reader_thread);
            return;
        };
    }

    var chunk: [sse_chunk_len]u8 = undefined;
    var b64_buf: [std.base64.standard.Encoder.calcSize(sse_chunk_len)]u8 = undefined;
    var idle_ticks: u32 = 0;

    while (true) {
        const n = session.drainOutput(&chunk);
        if (n != 0) {
            const encoded = std.base64.standard.Encoder.encode(&b64_buf, chunk[0..n]);
            sseSendOutput(&body, encoded) catch break;
            idle_ticks = 0;
            continue;
        }

        const state = session.outputState();
        if (state.done) {
            var reason_buf: [160]u8 = undefined;
            const event = std.fmt.bufPrint(
                &reason_buf,
                "event: exit\ndata: {{\"reason\":\"{s}\"}}\n\n",
                .{state.reason},
            ) catch "event: exit\n\n";
            sseSend(&body, event) catch {};
            break;
        }

        app.io.sleep(poll_interval, .awake) catch break;
        idle_ticks += 1;
        if (idle_ticks >= keepalive_ticks) {
            sseSend(&body, ": keepalive\n\n") catch break;
            idle_ticks = 0;
        }
    }

    body.end() catch {};
    cleanupSession(app, session, reader_thread);
}

fn sseSend(body: *http.BodyWriter, bytes: []const u8) !void {
    try body.writer.writeAll(bytes);
    try body.writer.flush();
    try body.flush();
}

fn sseSendOutput(body: *http.BodyWriter, encoded: []const u8) !void {
    try body.writer.print("event: out\ndata: {{\"b\":\"{s}\"}}\n\n", .{encoded});
    try body.writer.flush();
    try body.flush();
}

fn cleanupSession(app: *App, session: *Session, reader_thread: std.Thread) void {
    unregisterSession(app, session);
    closeSession(app, session);
    reader_thread.join();
    session.stream.close(app.io);
    session.release();
}

fn readerMain(session: *Session) void {
    defer session.release();

    var buf: [socket_read_buffer_len]u8 = undefined;
    while (true) {
        var data = [1][]u8{&buf};
        const result = session.app.io.operate(.{ .net_read = .{
            .socket_handle = session.stream.socket.handle,
            .data = &data,
        } }) catch |err| {
            session.finishOutput(if (err == error.Canceled) "canceled" else "io error");
            return;
        };
        const read_result = result.net_read catch |err| {
            session.finishOutput(switch (err) {
                error.ConnectionResetByPeer => "reset",
                error.SocketUnconnected => "closed",
                error.ConnectionTimedOut => "timeout",
                else => "io error",
            });
            return;
        };
        if (read_result.data_len == 0) {
            session.finishOutput("eof");
            return;
        }
        session.pushOutput(buf[0..read_result.data_len]) catch {
            session.finishOutput("buffer overflow");
            return;
        };
    }
}

fn handleInput(app: *App, req: *http.Server.Request, query: []const u8) !void {
    var arena = std.heap.ArenaAllocator.init(app.gpa);
    defer arena.deinit();
    const a = arena.allocator();

    const q = try parseQuery(a, query);
    const id = q.id orelse return respondError(req, .bad_request, "missing id");

    const session = acquireSession(app, id) orelse return respondError(req, .not_found, "unknown session");
    defer session.release();

    var reader_buf: [1024]u8 = undefined;
    const body_reader = req.readerExpectContinue(&reader_buf) catch {
        return respondError(req, .bad_request, "cannot read request body");
    };

    var body: [64 * 1024]u8 = undefined;
    var total: usize = 0;
    while (total < body.len) {
        const n = body_reader.readSliceShort(body[total..]) catch {
            return respondError(req, .bad_request, "cannot read request body");
        };
        if (n == 0) break;
        total += n;
    }
    if (total == body.len) return respondError(req, .payload_too_large, "input too large");

    if (session.closed.load(.acquire)) return respondError(req, .gone, "session closed");

    session.write_mutex.lockUncancelable(app.io);
    defer session.write_mutex.unlock(app.io);
    if (session.closed.load(.acquire)) return respondError(req, .gone, "session closed");

    var write_buf: [1024]u8 = undefined;
    var writer = session.stream.writer(app.io, &write_buf);
    writer.interface.writeAll(body[0..total]) catch |err| {
        std.log.warn("socutil: socket write failed: {s}", .{@errorName(err)});
        closeSession(app, session);
        return respondError(req, .bad_gateway, "write to socket failed");
    };
    writer.interface.flush() catch |err| {
        std.log.warn("socutil: socket flush failed: {s}", .{@errorName(err)});
        closeSession(app, session);
        return respondError(req, .bad_gateway, "write to socket failed");
    };

    var resp_buf: [96]u8 = undefined;
    const resp = std.fmt.bufPrint(&resp_buf, "{{\"ok\":true,\"written\":{d}}}\n", .{total}) catch
        "{\"ok\":true}\n";
    return respond(req, .ok, "application/json", resp);
}

fn handleClose(app: *App, req: *http.Server.Request, query: []const u8) !void {
    var arena = std.heap.ArenaAllocator.init(app.gpa);
    defer arena.deinit();
    const a = arena.allocator();

    const q = try parseQuery(a, query);
    const id = q.id orelse return respondError(req, .bad_request, "missing id");

    const session = acquireSession(app, id) orelse return respondError(req, .not_found, "unknown session");
    defer session.release();

    closeSession(app, session);
    return respond(req, .ok, "application/json", "{\"ok\":true}\n");
}

fn createSession(app: *App, id: []const u8, path: []const u8) !*Session {
    const session = try app.gpa.create(Session);
    errdefer app.gpa.destroy(session);

    session.* = .{
        .app = app,
        .id = try app.gpa.dupe(u8, id),
        .path = try app.gpa.dupe(u8, path),
        .stream = undefined,
    };
    errdefer app.gpa.free(session.id);

    const address = try net.UnixAddress.init(session.path);
    session.stream = try address.connect(app.io);
    return session;
}

fn unregisterSession(app: *App, session: *Session) void {
    app.sessions_mutex.lockUncancelable(app.io);
    defer app.sessions_mutex.unlock(app.io);
    if (app.sessions.get(session.id)) |current| {
        if (current == session) _ = app.sessions.remove(session.id);
    }
}

fn registerSession(app: *App, session: *Session) !void {
    app.sessions_mutex.lockUncancelable(app.io);
    defer app.sessions_mutex.unlock(app.io);
    if (app.sessions.contains(session.id)) return error.SessionAlreadyExists;
    try app.sessions.put(app.gpa, session.id, session);
    // The reader thread receives its own reference.
    session.refs += 1;
}

fn dropReaderRef(app: *App, session: *Session) void {
    app.sessions_mutex.lockUncancelable(app.io);
    defer app.sessions_mutex.unlock(app.io);
    session.refs -= 1;
}

fn acquireSession(app: *App, id: []const u8) ?*Session {
    app.sessions_mutex.lockUncancelable(app.io);
    defer app.sessions_mutex.unlock(app.io);
    const session = app.sessions.get(id) orelse return null;
    session.refs += 1;
    return session;
}

fn closeSession(app: *App, session: *Session) void {
    if (session.closed.swap(true, .acq_rel)) return;
    session.stream.shutdown(app.io, .both) catch {};
}

const Asset = struct {
    body: []const u8,
    content_type: []const u8,
};

fn assetFor(path: []const u8) ?Asset {
    if (std.mem.eql(u8, path, "/") or std.mem.eql(u8, path, "/index.html")) {
        return .{ .body = @embedFile("ui/index.html"), .content_type = "text/html; charset=utf-8" };
    }
    if (std.mem.eql(u8, path, "/app.js")) {
        return .{ .body = @embedFile("ui/app.js"), .content_type = "application/javascript; charset=utf-8" };
    }
    if (std.mem.eql(u8, path, "/style.css")) {
        return .{ .body = @embedFile("ui/style.css"), .content_type = "text/css; charset=utf-8" };
    }
    return null;
}

fn respond(
    req: *http.Server.Request,
    status: http.Status,
    content_type: []const u8,
    body: []const u8,
) !void {
    try req.respond(body, .{
        .status = status,
        .keep_alive = false,
        .extra_headers = &.{.{ .name = "content-type", .value = content_type }},
    });
}

fn methodNotAllowed(req: *http.Server.Request, allow: []const u8) !void {
    try req.respond("method not allowed\n", .{
        .status = .method_not_allowed,
        .keep_alive = false,
        .extra_headers = &.{
            .{ .name = "content-type", .value = "text/plain; charset=utf-8" },
            .{ .name = "allow", .value = allow },
        },
    });
}

fn respondError(req: *http.Server.Request, status: http.Status, message: []const u8) !void {
    var buf: [256]u8 = undefined;
    const body = std.fmt.bufPrint(&buf, "{{\"error\":\"{s}\"}}\n", .{message}) catch
        "{\"error\":\"error\"}\n";
    return respond(req, status, "application/json", body);
}

const Query = struct {
    id: ?[]const u8 = null,
    path: ?[]const u8 = null,
};

fn parseQuery(allocator: std.mem.Allocator, raw: []const u8) !Query {
    var query: Query = .{};
    var it = std.mem.splitScalar(u8, raw, '&');
    while (it.next()) |part| {
        if (part.len == 0) continue;
        const eq = std.mem.indexOfScalar(u8, part, '=');
        const key = if (eq) |i| part[0..i] else part;
        const value = if (eq) |i| part[i + 1 ..] else "";
        if (std.mem.eql(u8, key, "id")) {
            query.id = try decodeQueryValue(allocator, value);
        } else if (std.mem.eql(u8, key, "path")) {
            query.path = try decodeQueryValue(allocator, value);
        }
    }
    return query;
}

fn decodeQueryValue(allocator: std.mem.Allocator, value: []const u8) ![]u8 {
    const decoded = try allocator.dupe(u8, value);
    for (decoded) |*c| {
        if (c.* == '+') c.* = ' ';
    }
    return std.Uri.percentDecodeInPlace(decoded);
}

fn validSessionId(id: []const u8) bool {
    if (id.len == 0 or id.len > 64) return false;
    for (id) |c| {
        if (!std.ascii.isAlphanumeric(c) and c != '-' and c != '_') return false;
    }
    return true;
}

fn writeJsonString(w: *Io.Writer, text: []const u8) !void {
    try w.writeByte('"');
    for (text) |c| switch (c) {
        '"' => try w.writeAll("\\\""),
        '\\' => try w.writeAll("\\\\"),
        0...0x1f => try w.print("\\u{x:0>4}", .{c}),
        else => try w.writeByte(c),
    };
    try w.writeByte('"');
}

test "valid session ids" {
    try std.testing.expect(validSessionId("abc-123_XYZ"));
    try std.testing.expect(validSessionId("0b9f4d3a-7f31-4e2e-9a5a-8b2a1c3d4e5f"));
    try std.testing.expect(!validSessionId(""));
    try std.testing.expect(!validSessionId("has space"));
    try std.testing.expect(!validSessionId("slash/inside"));
    const too_long: [65]u8 = @splat('x');
    try std.testing.expect(!validSessionId(&too_long));
}

test "query decoding" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const a = arena.allocator();

    const q = try parseQuery(a, "id=abc%2D123&path=%2Ftmp%2Fmy+socket.sock&other=ignored");
    try std.testing.expectEqualStrings("abc-123", q.id.?);
    try std.testing.expectEqualStrings("/tmp/my socket.sock", q.path.?);

    const empty = try parseQuery(a, "");
    try std.testing.expect(empty.id == null);
    try std.testing.expect(empty.path == null);
}

test "json string escaping" {
    var aw: Io.Writer.Allocating = .init(std.testing.allocator);
    defer aw.deinit();

    try writeJsonString(&aw.writer, "a\"b\\c\n");
    try std.testing.expectEqualStrings("\"a\\\"b\\\\c\\u000a\"", aw.writer.buffered());
}
