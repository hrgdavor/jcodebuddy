# WebView Explorer

<!-- Plugin description -->
A JetBrains IntelliJ Platform plugin that provides a JCEF-based WebView tool window for viewing and interacting with HTML files directly within your IDE.
<!-- Plugin description end -->

A JetBrains IntelliJ Platform plugin that provides a JCEF-based WebView tool window for viewing and interacting with HTML files directly within your IDE.

## Features

- **WebView Tool Window**: Displays HTML files in a JCEF (Java Chromium Embedded Framework) browser within the IDE.
- **Keyboard Shortcut**: Toggle the WebView Explorer tool window with `Ctrl+Alt+Shift+W`.
- **Context Menu Integration**: Right-click HTML files in the Project View, Editor, or Editor Tabs to open them in the WebView.
- **JavaScript Bridge**: Allows HTML files to comm[Run Plugin.run.xml](.run/Run%20Plugin.run.xml)unicate with the IDE (e.g., opening files at specific line numbers).
- **Persistent State**: Remembers the last opened URL across IDE restarts.
- **Improved Refresh**: The refresh button reloads the currently active page.
- **HTTP server to open files**: `/open?filePath=...&line=` (port defined by `webview.explorer.port`)


## VM options and workspace

Use Help > Edit Custom VM Options in intelij to define important VM options for this plugin:

```
-Dwebview.explorer.port=18881
-Dwebview.explorer.allowedOrigins=http://localhost:3000
```

## jcef debug

Open the Registry (Help > Find Action > type "Registry"), locate ide.browser.jcef.debug.port, set it to 9222 (or your desired port), and restart the IDE. This enables Chrome DevTools to attach via http://localhost:9222 for debugging JCEF-based browsers like previews or plugin

## Development

### Prerequisites

- Java 21 or higher (Required for IntelliJ Platform 2025.3+)
- IntelliJ IDEA (Community or Ultimate)

### Running the Plugin in Test IDE

To run the plugin in a test instance of IntelliJ IDEA:

```bash
./gradlew runIde
```

Or use the predefined **Run Plugin** configuration in the `.run` directory.

The plugin will start a new IDE instance with the WebView Explorer plugin installed. You can then:
1. Open any project containing HTML files
2. Use `Ctrl+Alt+Shift+W` to toggle the WebView Explorer tool window
3. Right-click on any HTML file in the Project View and select "Open in WebView Explorer"

### Building the Plugin

To build the plugin for distribution:

```bash
./gradlew buildPlugin
```

The packaged plugin will be available at:
```
build/distributions/WebView Explorer-<version>.zip
```

### Installing the Plugin Manually

1. Build the plugin using the command above
2. In IntelliJ IDEA, go to **Settings/Preferences** → **Plugins**
3. Click the gear icon (⚙️) → **Install Plugin from Disk...**
4. Select the `.zip` file from `build/distributions/`
5. Restart the IDE

## Technical Details

- **Plugin ID**: `hr.hrg.jetbrains.webview`
- **Tool Window ID**: `WebView Explorer`
- **Minimum IDE Build**: Defined in `gradle.properties` (`pluginSinceBuild`)
- **Platform Version**: Defined in `gradle.properties` (`platformVersion`)

## Project Structure

```
.
├── src/main/java/
│   └── hr/hrg/jetbrains/webview/
│       ├── actions/
│       │   ├── ToggleToolWindowAction.java    # Keyboard shortcut handler
│       │   └── OpenFileInWebViewAction.java   # Context menu action
│       ├── services/
│       │   └── PluginStateService.java        # Persistent state management
│       └── toolWindow/
│           ├── JcefToolWindowFactory.java     # WebView tool window factory
│           └── JcefBridgeNew.java            # JavaScript-IDE bridge
├── src/main/resources/META-INF/
│   └── plugin.xml                            # Plugin configuration
├── gradle.properties                         # Plugin metadata
└── build.gradle.kts                         # Build configuration
```

## Configuration

Key configuration files:

- **`gradle.properties`**: Plugin name, version, platform settings, and JDK home path.
- **`plugin.xml`**: Action registrations, extension points, and service declarations.
- **`settings.gradle.kts`**: Project name

## License

MIT License (or as specified in LICENSE file)
