# Java Watch Agent

The **Java Watch Agent** is a unique, deterministic, and modular developer utility that automates boilerplate generation, project maintenance, and refactorings directly from your code, without relying solely on heavy IDE plugins or cloud AI.

It operates via a background watcher and provides both a local Terminal UI (TUI) and a Web UI to review, reject, or apply generated code additions safely.

## Features

*   **Comment-Driven Generation:** Trigger advanced file manipulations simply by typing comment markers (like `// @gen builder`) in your code.
*   **Dual UI (Terminal & Web):** Review, diff, and approve generated code through a built-in terminal interface or a companion web application.
*   **Audit Trails:** Every change is safely backed up in a `.watch` audit directory. You can easily rollback if needed.
*   **Zero-Config Security:** Web interfaces are secured by default using Basic Authentication with auto-generated passwords.
*   **Extensible Tooling:** The agent runs specific `ActionTool` implementations (e.g., Getters, Builders, Record Builders) and can be extended with custom refactoring logic.

## Built-in Tools

The following generators are currently bundled. To use them, add the corresponding trigger comment just above a class or record declaration:

*   **`// @gen builder`**: Generates a standard fluent Builder pattern as a static inner class.
*   **`// @gen record_builder`**: Generates a fluent Builder specifically tailored for Java `record` types.
*   **`// @gen getters`**: Generates standard getters (e.g., `getName()`, `isActive()`) for all fields.
*   **`// @gen setters`**: Generates standard setters for all fields.
*   **`// @gen accessors`**: Generates both getters and setters.
*   **`// @gen constructor`**: Generates a public constructor taking all fields as arguments.

## How to Build

The project is built using Maven.

```bash
mvn clean install
```

## How to Run

You can run the agent by executing the main class `hr.hrg.watch2.agent.WatchAgent`. This should be run from the root of the Java workspace you want to monitor.

Upon starting, the agent will:
1. Load or create a `.watch_agent.conf` file.
2. If the web server is enabled (default), it creates a random web password and prints it to the console.
3. Start watching the files in the current director for changes.
4. Open the Interactive Terminal Session (TUI).

## Interactive Terminal UI (TUI) Commands

In the terminal where the agent is running, you can use the following commands:

*   `list` - List all pending actions triggered by comments.
*   `diff <idx>` - Show a line-by-line diff of what the tool will change.
*   `accept <idx>` - Apply the generated code to your source file.
*   `reject <idx>` - Discard the pending changes.
*   `jump <idx>` - Open your configured editor directly to the location of the trigger.
*   `copy <idx>` - Copy the file path and line number to your clipboard.
*   `rebuild-cache` - Force a rescan of tracked metadata.
*   `exit` / `quit` - Shutdown the agent.

## Configuration

Configuration is automatically stored in `.watch_agent.conf` at your project root.

Example configuration options:
*   `webEnabled`: Boolean to turn the web companion UI on/off (default: `true`).
*   `webPort`: Port for the web interface (default: `6666`).
*   `webUser`: default `java_watch_agent`
*   `webPassword`: The password for Basic Authentication. If empty, one is generated on startup.
*   `editorCommand`: Command template to open your IDE (e.g., `"code --goto %f:%l"` or `"idea --line %l %f"`).
*   `applyFirst`: If enabled (`true`), actions are applied immediately when discovered, and then you review them to decide to keep (accept) or roll back (reject). Default is `false` (i.e. first prepare the action, wait for review to apply).

## Apply Modes (applyFirst)

The Java Watch Agent offers two distinct workflows for how generated code is handled, controlled by the `applyFirst` configuration option:

### `applyFirst: false` (Default)
In this mode, the agent prioritizes safety and manual review.
1. **Prepare:** When a trigger (like `// @gen builder`) is detected, the agent generates the boilerplate code but *does not* write it back to your source file immediately.
2. **Review:** The pending changes are listed in the TUI or Web UI. Your source file remains untouched.
3. **Accept/Reject:** You manually review the diff. Clicking "Accept" applies the generated code to your source file. Clicking "Reject" discards the generated code.

This mode is ideal when you want to carefully inspect the automated changes before they become part of your codebase.

### `applyFirst: true`
In this mode, the agent acts more like a real-time auto-fixer, prioritizing speed while still offering safety via audit trails.
1. **Apply Immediately:** When a trigger is detected, the agent generates the boilerplate code and *immediately* writes it back to your source file.
2. **Review via Audit:** The action is still recorded as pending in the TUI or Web UI, and a snapshot of your file *before* the change was made is saved in the `.watch/audit` directory.
3. **Accept/Reject (Revert):** If you like the change, clicking "Accept" finalizes the action. If you don't like the change, clicking "Reject" will *revert* your source file back to its original state using the `.watch/audit` snapshot.

This mode is ideal for experienced users who trust the generators and want the code available in their IDE immediately, while still retaining the ability to undo the action cleanly if it produces an unexpected result.

## Web UI

If enabled, navigate to `http://localhost:6666` (or your configured port). 
You will be prompted for credentials:
*   **Username**: `admin`
*   **Password**: *[Check your terminal output for the generated password, or set one manually in watch_agent.conf]*

The web UI provides a visual diff and allows you to Accept/Reject actions just like the terminal interface.
