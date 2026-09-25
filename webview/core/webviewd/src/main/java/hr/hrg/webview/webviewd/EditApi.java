package hr.hrg.webview.webviewd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import hr.hrg.webview.core.EditRequest;
import hr.hrg.webview.core.EditService;
import hr.hrg.webview.core.TextEdit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of the write surface, and the one place a core outcome becomes an HTTP status.
 *
 * <p>Separate from the server class because that mapping is the contract: a page decides what to do next from
 * the status, and a test can assert the mapping without a socket. The bodies follow the plan's § 6.2 sketch —
 * {@code {applied, digest, unifiedDiff}} on success, {@code {reason, digest}} on a refusal — with the reason
 * spelled the same way as the core enum so a log line and a response agree.
 *
 * <p>The digest returned is always the digest of the content the operation leaves behind: what was written,
 * what <em>would</em> be written under {@code dryRun}, or what is on disk now when a request was refused as
 * stale. A page can therefore render the result and chain the next edit without re-reading the file.
 */
final class EditApi {

    /** The request body, with boxed fields so "absent" is distinguishable from "zero". */
    record Payload(String filePath, String expectedDigest, List<EditDto> edits, Boolean dryRun) {

        record EditDto(Integer startLine, Integer startColumn, Integer endLine, Integer endColumn, String newText) {
        }

        /**
         * @param defaultDryRun what an absent {@code dryRun} means: {@code true} for {@code /diff}, and for
         *                      {@code /applyEdit} the plan's rule that a page must ask twice
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
            return new EditRequest(filePath, expectedDigest, parsed,
                    dryRun == null ? defaultDryRun : dryRun);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private EditApi() {
    }

    /** Parses a request body, or throws {@link IllegalArgumentException} with something a page can act on. */
    static EditRequest parse(String body, boolean defaultDryRun) {
        Payload payload;
        try {
            payload = GSON.fromJson(body, Payload.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("the request body is not valid JSON: " + e.getMessage());
        }
        if (payload == null) {
            throw new IllegalArgumentException("the request body is empty");
        }
        return payload.toRequest(defaultDryRun);
    }

    /** The status a caller sees for an outcome. */
    static int statusOf(EditService.Reason reason) {
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

    /** The body for an outcome: the plan's fields, plus a reason whenever the answer is not a plain success. */
    static String bodyOf(EditService.Outcome outcome) {
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
    static String reasonName(EditService.Reason reason) {
        return reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
