package hr.hrg.jcodebuddy.ipc;

import java.io.IOException;
import java.io.Reader;

/**
 * The line framing every connection uses: one JSON object per line, bounded so a local peer cannot make the
 * tool allocate without limit.
 */
final class Wire {

    private Wire() {
    }

    /** Reads one line, or {@code null} at end of stream; a line longer than the frame limit is refused. */
    static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') {
                return line.toString();
            }
            if (next == '\r') {
                continue;
            }
            if (line.length() >= ToolProtocol.MAX_FRAME_BYTES) {
                throw new IOException("frame is longer than " + ToolProtocol.MAX_FRAME_BYTES + " characters");
            }
            line.append((char) next);
        }
        return line.length() == 0 ? null : line.toString();
    }
}
