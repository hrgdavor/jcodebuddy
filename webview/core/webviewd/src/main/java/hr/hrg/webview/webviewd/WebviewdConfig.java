package hr.hrg.webview.webviewd;

import hr.hrg.webview.core.AllowedOrigins;

import java.nio.file.Path;

/**
 * The command line of {@code webviewd}, parsed once and validated once.
 *
 * <p>A record rather than a field bag because every one of these values is fixed for the process's life: the
 * project it serves, the port it binds (0 meaning "pick one and tell me"), whether to open a browser, the
 * origins it trusts beyond itself, the token to require, and which editor adapter to use.
 */
public record WebviewdConfig(Path project, int port, boolean open, String allowedOrigins, String token,
                             HostChoice host, int sidecarPort, String sidecarToken,
                             boolean printManifestOnly) {

    /** Which {@link hr.hrg.webview.core.EditorHost} to attach, or how to pick one. */
    public enum HostChoice {
        /**
         * Attach the best available adapter: the sidecar over LSP when an editor is attached to it, otherwise
         * a CLI adapter, otherwise nothing.
         */
        AUTO,
        /** Never attach anything: the headless case, which is a legitimate deployment and not a failure. */
        NONE,
        /**
         * Route navigation through the sidecar over LSP, which is the only way to reach a caret on Zed.
         * Refuses to start when the sidecar has no editor attached.
         */
        LSP,
        /** Require a CLI adapter, and refuse to start when none is found. */
        ZED_CLI;

        static HostChoice parse(String text) {
            return switch (text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "", "auto" -> AUTO;
                case "none", "null", "headless" -> NONE;
                case "lsp", "sidecar", "lsp-sidecar" -> LSP;
                case "zed", "zed-cli", "zedcli" -> ZED_CLI;
                default -> throw new IllegalArgumentException(
                        "unknown --host value '" + text + "': expected auto, none, lsp or zed-cli");
            };
        }

        @Override
        public String toString() {
            return switch (this) {
                case AUTO -> "auto";
                case NONE -> "none";
                case LSP -> "lsp";
                case ZED_CLI -> "zed-cli";
            };
        }
    }

    /** The port the JWA sidecar's HTTP face listens on unless it is told otherwise. */
    public static final int DEFAULT_SIDECAR_PORT = 7979;

    public static final String USAGE = """
            webviewd - the standalone page host

              webviewd --project <dir> [--port 0|N] [--open] [--host auto|none|lsp|zed-cli]
                       [--allowed-origins <origin,origin>] [--token <secret>]
                       [--sidecar-port <7979>] [--sidecar-token <secret>] [--print-manifest]

              --project <dir>        the directory pages may read and write; defaults to the current directory
              --port 0|N             TCP port on loopback; 0 (the default) picks a free one and publishes it
              --open                 open the host's index page in the default browser after starting
              --host auto|none|lsp|zed-cli
                                     which editor adapter to attach. auto prefers the LSP route (the only one
                                     that can place a caret on Zed) and falls back to the Zed CLI, then to no
                                     editor at all
              --allowed-origins      additional origins to trust, comma separated. The host always trusts its
                                     own origin, and an empty list trusts nothing else
              --token <secret>       require this token for /open and /file/ instead of generating one
              --sidecar-port <port>  where the JWA sidecar's HTTP face listens (default 7979)
              --sidecar-token        the sidecar's token, if it was started with one. Defaults to the
                                     jwa.sidecar.token system property so one -D configures both processes
              --print-manifest       print the manifest JSON and exit without binding a socket
            """;

    public WebviewdConfig {
        if (project == null) {
            throw new IllegalArgumentException("--project is required");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("--port must be between 0 and 65535, was " + port);
        }
        if (sidecarPort <= 0 || sidecarPort > 65535) {
            throw new IllegalArgumentException("--sidecar-port must be between 1 and 65535, was " + sidecarPort);
        }
    }

    /** The origins the host trusts before adding its own; never null. */
    public AllowedOrigins configuredOrigins() {
        return AllowedOrigins.of(allowedOrigins);
    }

    public static WebviewdConfig parse(String[] args) {
        Path project = Path.of("").toAbsolutePath().normalize();
        int port = 0;
        boolean open = false;
        boolean printManifest = false;
        String origins = "";
        String token = null;
        HostChoice host = HostChoice.AUTO;
        int sidecarPort = DEFAULT_SIDECAR_PORT;
        String sidecarToken = System.getProperty("jwa.sidecar.token", "").trim();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--project", "-p" -> project = Path.of(value(args, ++i, arg)).toAbsolutePath().normalize();
                case "--port" -> port = parseInt(value(args, ++i, arg), arg);
                case "--open" -> open = true;
                case "--print-manifest" -> printManifest = true;
                case "--allowed-origins" -> origins = value(args, ++i, arg);
                case "--token" -> token = value(args, ++i, arg);
                case "--host" -> host = HostChoice.parse(value(args, ++i, arg));
                case "--sidecar-port" -> sidecarPort = parseInt(value(args, ++i, arg), arg);
                case "--sidecar-token" -> sidecarToken = value(args, ++i, arg);
                case "--help", "-h" -> throw new HelpRequested();
                default -> throw new IllegalArgumentException("unknown option '" + arg + "'");
            }
        }
        return new WebviewdConfig(project, port, open, origins, token, host, sidecarPort, sidecarToken,
                printManifest);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static int parseInt(String text, String option) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " needs a number, got '" + text + "'");
        }
    }

    /** Thrown for {@code --help}; the caller prints {@link #USAGE} and exits 0 rather than 1. */
    public static final class HelpRequested extends RuntimeException {
        public HelpRequested() {
            super("help requested");
        }
    }
}
