package hr.hrg.webview.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The write surface: one implementation of "a JSON request becomes a status and a body", shared by every host.
 *
 * <p>This is the {@link EditService} counterpart of {@link Navigator}. {@code EditService} owns the rules —
 * jail, digest, atomic write, checkpoints, rate limit — and this class owns the *conversation*: parse the body,
 * honour {@code dryRun}, decide whether the change belongs in an editor's buffer or in the file, and map the
 * outcome to the status codes in {@code doc/edit-api.md}. Two hosts need exactly that (the standalone
 * one and the JetBrains plugin), which is why it lives here rather than in either of them.
 *
 * <p>It performs no I/O of its own and knows nothing about HTTP: a host reads the body, calls one method, and
 * sends the answer. That is what makes the routing testable without a socket — and what keeps a second host from
 * inventing a second set of statuses.
 *
 * <p>The digest in every answer is the digest of the content the operation leaves behind: what was written, what
 * <em>would</em> be written under {@code dryRun}, or what is on disk now when a request was refused as stale. A
 * page can therefore render the result and chain the next edit without re-reading the file.
 */
public final class WriteSurface {

    /** What a host should send: a status and a JSON body. */
    public record Answer(int status, String body) {

        public boolean ok() {
            return status == 200;
        }
    }

    /** The request body, with boxed fields so "absent" is distinguishable from "zero". */
    record Payload(String filePath, String expectedDigest, List<EditDto> edits, Boolean dryRun, String target) {

        record EditDto(Integer startLine, Integer startColumn, Integer endLine, Integer endColumn, String newText) {
        }

        /**
         * @param defaultDryRun what an absent {@code dryRun} means: {@code true} for the diff route, and for
         *                      {@code applyEdit} the plan's rule that a page has to ask twice
         */
        EditRequest toRequest(boolean defaultDryRun) {
            if (filePath == null || filePath.isBlank()) {
                throw new IllegalArgumentException("the request must name a filePath");
            }
            if (expectedDigest == null || expectedDigest.isBlank()) {
                throw new IllegalArgumentException("the request must carry expectedDigest: the digest of the "
                        + "content the page read");
            }
            List<TextEdit> parsed = new ArrayList<>();
            if (edits != null) {
                for (EditDto dto : edits) {
                    if (dto == null || dto.startLine() == null || dto.startColumn() == null
                            || dto.endLine() == null || dto.endColumn() == null) {
                        throw new IllegalArgumentException(
                                "every edit needs startLine, startColumn, endLine, endColumn");
                    }
                    parsed.add(new TextEdit(dto.startLine(), dto.startColumn(), dto.endLine(), dto.endColumn(),
                            dto.newText() == null ? "" : dto.newText()));
                }
            }
            return new EditRequest(filePath, expectedDigest, parsed, dryRun == null || dryRun);
        }
    }

    /** A parsed request and where the caller wants it applied. */
    record Routed(EditRequest request, String target) {

        boolean bufferRequested() {
            return "buffer".equals(target);
        }

        boolean diskRequested() {
            return "disk".equals(target);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final EditService edits;
    private final EditorHost host;

    public WriteSurface(EditService edits, EditorHost host) {
        this.edits = edits;
        this.host = host == null ? NullHost.INSTANCE : host;
    }

    /** The proposal: a diff and the digest the file would have, with nothing written. */
    public Answer diff(String requestBody) {
        try {
            Routed routed = parse(requestBody, true);
            return answer(edits.propose(routed.request()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /**
     * The write, routing to the editor's buffer or to disk according to {@code target}:
     * {@code auto} (the default) prefers the buffer when the host declares {@link EditorHost#CAP_EDIT},
     * {@code buffer} insists on it, {@code disk} insists on this host's own write.
     *
     * <p>Three properties this method is responsible for, and each is a decision rather than an implementation
     * detail: a page that forgets {@code dryRun} gets a proposal; the digest guard runs <b>before</b> an editor is
     * asked, so no editor is ever handed edits computed against content the page did not read; and a host that
     * refuses the buffer falls back to the disk write rather than reporting a failure.
     */
    public Answer applyEdit(String requestBody) {
        Routed routed;
        try {
            routed = parse(requestBody, true);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        EditRequest request = routed.request();
        boolean hostCanEdit = host.capabilities().contains(EditorHost.CAP_EDIT);

        if (routed.bufferRequested() && !hostCanEdit) {
            return new Answer(409, noBufferBody(host.name()));
        }
        if (!request.dryRun() && !routed.diskRequested() && hostCanEdit) {
            EditService.Outcome proposal = edits.propose(request);
            if (proposal.reason() != EditService.Reason.OK
                    && proposal.reason() != EditService.Reason.NO_CHANGE) {
                return answer(proposal);
            }
            if (host.applyEdit(proposal.filePath(), request.edits())) {
                return new Answer(200, bufferBody(proposal));
            }
            // The host declined: the caller's fallback is this host's own write, which is the documented
            // asymmetry rather than an error.
        }
        return answer(edits.apply(request));
    }

    public Answer undo(String requestBody) {
        return restore(requestBody, false);
    }

    public Answer redo(String requestBody) {
        return restore(requestBody, true);
    }

    /** A refusal for a body this surface could not read; a host uses it instead of inventing its own shape. */
    public Answer badRequest(String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applied", false);
        body.put("reason", "invalid-edit");
        body.put("detail", detail == null ? "" : detail);
        return new Answer(400, GSON.toJson(body));
    }

    private Answer restore(String requestBody, boolean redo) {
        String filePath;
        try {
            filePath = filePathOf(requestBody);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return answer(redo ? edits.redo(filePath) : edits.undo(filePath));
    }

    /** Undo and redo name only a file, so they get their own minimal parse rather than a whole edit request. */
    private String filePathOf(String requestBody) {
        Payload payload = parsePayload(requestBody);
        if (payload.filePath() == null || payload.filePath().isBlank()) {
            throw new IllegalArgumentException("the request must name a filePath");
        }
        return payload.filePath();
    }

    private Routed parse(String requestBody, boolean defaultDryRun) {
        Payload payload = parsePayload(requestBody);
        String target = payload.target() == null ? "auto" : payload.target().trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(target) && !"buffer".equals(target) && !"disk".equals(target)) {
            throw new IllegalArgumentException("target must be auto, buffer or disk, was '" + payload.target() + "'");
        }
        return new Routed(payload.toRequest(defaultDryRun), target);
    }

    private Payload parsePayload(String requestBody) {
        Payload payload;
        try {
            payload = GSON.fromJson(requestBody, Payload.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("the request body is not valid JSON: " + e.getMessage());
        }
        if (payload == null) {
            throw new IllegalArgumentException("the request body is empty");
        }
        return payload;
    }

    /** The status a caller sees for an outcome. */
    public static int statusOf(EditService.Reason reason) {
        return switch (reason) {
            case OK, NO_CHANGE -> 200;
            case STALE -> 409;
            case INVALID_EDIT, INVALID_PATH -> 400;
            case OUTSIDE_PROJECT -> 403;
            case NOT_FOUND, NOTHING_TO_UNDO, NOTHING_TO_REDO -> 404;
            case NOT_READABLE -> 500;
            case RATE_LIMITED -> 429;
        };
    }

    /** The body for an outcome: the documented fields, plus a reason whenever the answer is not a plain success. */
    public static String bodyOf(EditService.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome.reason() == EditService.Reason.OK || outcome.reason() == EditService.Reason.NO_CHANGE) {
            body.put("applied", outcome.wrote());
            if (outcome.reason() == EditService.Reason.NO_CHANGE) {
                body.put("reason", "no-change");
            }
        } else {
            body.put("applied", false);
            body.put("reason", reasonName(outcome.reason()));
        }
        body.put("digest", outcome.digest());
        if (outcome.unifiedDiff() != null && !outcome.unifiedDiff().isEmpty()) {
            body.put("unifiedDiff", outcome.unifiedDiff());
        }
        if (outcome.detail() != null && !outcome.detail().isEmpty()) {
            body.put("detail", outcome.detail());
        }
        return GSON.toJson(body);
    }

    /** The kebab-case spelling a page reads, rather than the enum's SCREAMING_CASE. */
    public static String reasonName(EditService.Reason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Answer answer(EditService.Outcome outcome) {
        return new Answer(statusOf(outcome.reason()), bodyOf(outcome));
    }

    /**
     * The answer when the editor applied the change in its own buffer.
     *
     * <p>{@code target: "buffer"} is the field that matters: the file on disk still holds what
     * {@code expectedDigest} described until the reader saves, so a page that keeps editing has to know whether
     * it is tracking the buffer or the file. Stated in the body rather than left to be discovered.
     */
    static String bufferBody(EditService.Outcome proposal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applied", true);
        body.put("target", "buffer");
        body.put("digest", proposal.digest());
        if (proposal.unifiedDiff() != null && !proposal.unifiedDiff().isEmpty()) {
            body.put("unifiedDiff", proposal.unifiedDiff());
        }
        body.put("detail", "applied in the editor's own buffer; the file on disk is unchanged until the "
                + "editor saves, and the reader can undo it with the editor's own undo");
        return GSON.toJson(body);
    }

    /** The answer when a page asked for the buffer and the attached host cannot provide one. */
    static String noBufferBody(String hostName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applied", false);
        body.put("reason", "no-buffer-edit");
        body.put("detail", "target 'buffer' was requested but host '" + hostName
                + "' does not declare the edit capability; ask for target 'disk' to have this host write the file");
        return GSON.toJson(body);
    }
}
