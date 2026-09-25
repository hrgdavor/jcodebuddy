// A registration extension, and nothing more.
//
// Zed 1.21.0 starts a language server only for a name some extension or built-in adapter registered, so the
// settings key `lsp.webview-sidecar.binary.path` is inert on its own (measured; see
// webview/PHASE0-ZED-FINDINGS.md section A'). This crate supplies the missing registration and a sensible
// default command. Every decision it makes is one a user can override from settings, because Zed applies
// `lsp.<id>.binary` over whatever this returns - which is the mechanism that was verified in Phase 0.
//
// It deliberately does NOT use `process:exec`: the extension never spawns anything. Zed spawns the command
// returned below, which is why no `[[capabilities]]` block is needed.

use zed_extension_api as zed;

/// Where the sidecar lives in a checkout, relative to the worktree root.
const SIDECAR_JAR: &str = "webview/jwa-sidecar/target/jwa-sidecar.jar";

/// Environment variable naming the `java` executable to use, for a checkout whose jar needs a JDK the PATH
/// does not offer (the sidecar is compiled for 25; the PATH `java` on this machine is 1.8).
const JAVA_ENV: &str = "JCB_WEBVIEW_JAVA";

/// Environment variable naming the sidecar binary outright, ahead of every other probe.
const BINARY_ENV: &str = "JCB_WEBVIEW_SIDECAR";

struct WebviewZedExtension;

impl WebviewZedExtension {
    fn env_value(env: &[(String, String)], name: &str) -> Option<String> {
        env.iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.clone())
            .filter(|value| !value.is_empty())
    }

    /// `$JAVA_HOME/bin/java[.exe]`, so a checkout can be run without touching the user's PATH order.
    fn java_home_binary(env: &[(String, String)]) -> Option<String> {
        let home = Self::env_value(env, "JAVA_HOME")?;
        let separator = match zed::current_platform().0 {
            zed::Os::Windows => "\\",
            _ => "/",
        };
        let executable = match zed::current_platform().0 {
            zed::Os::Windows => "java.exe",
            _ => "java",
        };
        let home = home.trim_end_matches('/').trim_end_matches('\\');
        Some(format!("{home}{separator}bin{separator}{executable}"))
    }
}

impl zed::Extension for WebviewZedExtension {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        worktree: &zed::Worktree,
    ) -> zed::Result<zed::Command> {
        let env = worktree.shell_env();

        // 1. An explicit binary wins over every probe, so a user can bypass all guessing with one env var.
        if let Some(binary) = Self::env_value(&env, BINARY_ENV) {
            return Ok(zed::Command {
                command: binary,
                args: vec!["--lsp".to_string()],
                env,
            });
        }

        // 2. The product binary, if this machine has one: `webviewd --lsp` is the intended long-term answer
        //    (one shaded jar, three modes - see the plan's Q4).
        if let Some(webviewd) = worktree.which("webviewd") {
            return Ok(zed::Command {
                command: webviewd,
                args: vec!["--lsp".to_string()],
                env,
            });
        }

        // 3. A checkout's sidecar jar, run on a JDK that can load it.
        let root = worktree.root_path();
        let root = root.trim_end_matches('/').trim_end_matches('\\');
        let jar = format!("{root}/{SIDECAR_JAR}");
        let java = Self::env_value(&env, JAVA_ENV)
            .or_else(|| Self::java_home_binary(&env))
            .or_else(|| worktree.which("java"));

        match java {
            Some(java) => Ok(zed::Command {
                command: java,
                args: vec!["-jar".to_string(), jar],
                env,
            }),
            None => Err(format!(
                "no way to start the webview sidecar: neither `webviewd` nor a `java` executable was found. \
                 Build it with `mvnd -pl webview/jwa-sidecar -am package`, then either put `webviewd` on the \
                 PATH or set `lsp.webview-sidecar.binary` in settings (for example \
                 {{\"path\": \"C:\\\\Program Files\\\\Java\\\\jdk-25\\\\bin\\\\java.exe\", \"arguments\": \
                 [\"-jar\", \"{jar}\"]}}). The jar is compiled for Java 25, so a JAVA_HOME pointing at an older \
                 JDK will not run it."
            )),
        }
    }
}

zed::register_extension!(WebviewZedExtension);
