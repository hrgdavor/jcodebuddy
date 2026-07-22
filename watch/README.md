# Watch Module

File watcher application that monitors a directory for changes and automatically copies files to a backup location.

## Usage

```bash
java -jar target/watch-1.0-SNAPSHOT.jar <source-directory> <destination-directory>
```

## Implementation

- **FileWatcherService**: Core service using DirectoryWatcher library
- **FileWatcherApp**: Main application with CLI interface

## Features

- Real-time file monitoring with DirectoryWatcher
- Handles CREATE, MODIFY, and DELETE events
- Recursive directory watching
- Initial sync on startup
- Windows-friendly file handling
- SLF4J logging

See parent [README](../README.md) for more details.
