# Java Watch SCP — Full Documentation

> For a quick-start overview see [README.md](README.md).

## Table of Contents

- [Building](#building)
- [Usage](#usage)
  - [Configuration File](#configuration-file)
  - [Command Line Arguments](#command-line-arguments)
  - [Command Line Options](#command-line-options)
  - [Reading Config from Stdin](#reading-config-from-stdin)
- [Config Reference](#config-reference)
  - [Global Settings](#global-settings)
  - [Folder Settings](#folder-settings)
  - [Config Variable Expansion](#config-variable-expansion)
- [Features In Depth](#features-in-depth)
  - [Standalone Checksum Generation](#standalone-checksum-generation)
  - [Standalone Get/Put](#standalone-getput)
  - [Advanced Database Options](#advanced-database-options)
  - [SSH Config Support](#ssh-config-support)
  - [Using with KeePassXC (SSH Agent)](#using-with-keepassxc-ssh-agent)
  - [Lightweight Sync Modes](#lightweight-sync-modes)
  - [Single File Sync Alias (`[file]`)](#single-file-sync-alias-file)
  - [Sync Trigger](#sync-trigger)
  - [Version File Support](#version-file-support)
  - [Remote Permissions and ACL Support](#remote-permissions-and-acl-support)
- [How It Works](#how-it-works)
- [License](#license)

---

## Building

Prerequisites:
- Java 21 or later
- Maven 3.x

```bash
mvn clean package
```

This creates `target/java-watch-scp.jar` with all dependencies included.

---

## Usage

### Configuration File

Create a `config.properties` file:

```ini
# Global Settings
host=example.com
username=username
password=yourpassword
parallel_threads=6
watch_delay_ms=200
compress=true
cleanup=false

# First folder pair
[folder]
local_dir=C:/path/to/watch1
remote_dir=/remote/path1
includes=*.java, *.xml
excludes=target/**

# Second folder pair
[folder]
local_dir=C:/path/to/watch2
remote_dir=/remote/path2

# Local copy worker (no SSH)
[local-folder]
dest_dir=C:/path/to/backup

[source]
local_dir=C:/source/data1
includes=*.txt

[source]
local_dir=C:/source/data2
excludes=temp/**

# Folder with local database storage
[folder]
local_dir=C:/path/to/watch3
remote_dir=/remote/path3
local_db=true
scpdb=C:/sync-dbs/project3.scpdb

# Use an alias from ~/.ssh/config
host=my-dev-server
[folder]
local_dir=C:/projects/myapp
remote_dir=/home/user/myapp
```

```bash
java -jar target/java-watch-scp.jar config.properties
```

> **parallel_threads**
> If you get "channel closed" exceptions, lower until it works
> or increase `MaxSessions` in `/etc/sshd/sshd_config`.
> About half of `MaxSessions` works well (6 is OK for default `MaxSessions=10`).

### Command Line Arguments

```bash
java -jar target/java-watch-scp.jar <source-dir> <host> <user> <pass> <remote-path> [port]
```

### Command Line Options

| Flag | Description |
|------|-------------|
| `-c, --config <path>` | Path to config file — use `-c -` to read from **stdin** |
| `-w, --watch` | Enable watch mode (continuous monitoring) |
| `-x, --compress` | Enable SSH compression |
| `--cleanup` | Remove remote files not present locally |
| `--dry-run` | Show what would be synced/removed without making changes |
| `--exec <cmd>` | Execute command on remote after each sync |
| `--check <hash\|mtime_size>` | Change detection mode (default: `hash`) |
| `--no-db` | Disable checksum database on remote server |
| `--var VARNAME=value` | Set a config variable (repeatable, highest priority) |
| `-D VARNAME=value` | Short form of `--var` |
| `-h, --help` | Show help message |

### Reading Config from Stdin

Pass `-c -` (a single dash) to read the configuration from standard input instead of a file. Useful for:
- Piping a dynamically generated config
- Securely injecting credentials without writing them to disk
- CI/CD scripts that generate config on the fly

```sh
# Pipe config from a file
cat config.properties | java -jar target/java-watch-scp.jar -w -c -

# Redirect from a file
java -jar target/java-watch-scp.jar -c - < config.properties

# Generate config dynamically (inject password from a secret manager)
my-secret-tool render config.tpl | java -jar target/java-watch-scp.jar -w -c -

# Heredoc
java -jar target/java-watch-scp.jar -c - <<'EOF'
host=myserver.com
username=user
key_path=~/.ssh/id_rsa
[folder]
local_dir=./src
remote_dir=/opt/app/src
EOF
```

---

## Config Reference

### Global Settings

| Key | Default | Description |
|-----|---------|-------------|
| `host` | — | Remote hostname or SSH config alias |
| `username` | — | SSH username |
| `password` | — | SSH password (prefer key auth or agent) |
| `key_path` | — | Path to SSH private key |
| `passphrase` | — | Passphrase for private key |
| `port` | `22` | SSH port (or set via `host:port`) |
| `parallel_threads` | `4` | Upload threads for initial sync |
| `watch_delay_ms` | `200` | Debounce delay for file-change events (ms) |
| `compress` | `false` | Enable SSH compression |
| `cleanup` | `false` | Remove remote files missing locally |
| `dry_run` | `false` | Dry run mode (don't upload or delete) |
| `color` | auto | Force (`true`) or disable (`false`) color output |
| `exec_cmd` | — | Remote command to run after each sync |
| `text_extensions` | built-in list | Comma-separated extensions treated as text |
| `version_from` | — | Local version template file path |
| `version_to` | — | Remote path to upload processed version file |
| `version_name` | — | Project name injected into version file |
| `ENV.VARNAME` | — | Config-level default for `${VARNAME}` expansion |

Credentials can also be set via environment variables:
- `SYNC_SSH_PWD` — SSH password fallback
- `SYNC_SSH_PASSPHRASE` — Key passphrase fallback

### Folder Settings

Each `[folder]` section (or `[file]` alias) supports:

| Key | Description |
|-----|-------------|
| `local_dir` | Local directory to watch/sync |
| `local_file` | (`[file]` only) Single local file — sets `local_dir` + `includes` automatically |
| `remote_dir` | Remote destination directory |
| `includes` | Comma-separated glob patterns to include |
| `excludes` | Comma-separated glob patterns to exclude |
| `check` | `hash` (default) or `mtime_size` |
| `no_db` | `true` to skip the `.scpdb` database |
| `local_db` | `true` to store `.scpdb` locally |
| `scpdb` | Custom path/name for the database file |
| `trigger_from` | Local file to upload as sync trigger |
| `trigger_to` | Remote path to write the sync trigger |
| `version_from` | Per-folder override for version template |
| `version_to` | Per-folder override for version remote path |
| `version_name` | Per-folder override for project name |

### Config Variable Expansion

Any config value can contain `${VARNAME}` placeholders resolved at load time.

**Resolution order (highest to lowest priority):**

| Priority | Source | Example |
|----------|--------|---------|
| 1 | `--var` CLI flag | `--var SUBDIR=v2` |
| 2 | Real environment variable | `export SUBDIR=v2` |
| 3 | `ENV.VARNAME=` config default | `ENV.SUBDIR=project` in config |
| — | **Error** | Variable missing from all three sources |

**Why this order?**

- **`--var` is highest**: most explicit — typed on *this specific invocation*. It is also the only override scoped purely to one run without polluting the shell environment.
- **Env vars are second**: represent the process's context (Docker, CI/CD, shell profile). Right for per-machine or per-environment settings that span many runs.
- **`ENV.X=` defaults are last**: live in the file, version-controlled, visible. Apply only when nothing external is specified.
- **Missing = error**: silent empty expansion would create broken paths like `:/opt/app/src` that are very hard to debug.

**Example — reusable team config:**
```ini
# Defaults — override any of these from the shell or --var
ENV.HOST=dev.example.com
ENV.REMOTE_BASE=/home/user
ENV.SUBDIR=project

host=${HOST}

[folder]
local_dir=C:/projects/${SUBDIR}/src
remote_dir=${REMOTE_BASE}/${SUBDIR}/src

version_from=C:/projects/${SUBDIR}/version.json
version_to=${REMOTE_BASE}/${SUBDIR}/version.json
version_name=${SUBDIR}
```

```sh
# Use file defaults
java -jar target/java-watch-scp.jar -c config.properties

# Override via --var (beats env vars)
java -jar target/java-watch-scp.jar --var SUBDIR=feature-x --var HOST=staging.example.com -c config.properties

# Override via environment variable
SUBDIR=project-v2 java -jar target/java-watch-scp.jar -c config.properties

# Windows cmd
set SUBDIR=project-v2 && java -jar target/java-watch-scp.jar -c config.properties

# Windows PowerShell (scoped)
& { $env:SUBDIR = "project-v2"; java -jar target/java-watch-scp.jar -c config.properties }
```

**Error on undefined variable:**
```
Config error: variable '${HOST}' is not defined.
  Set it via: --var HOST=value  |  env var HOST  |  ENV.HOST= in config.
```

---

## Features In Depth

### Standalone Checksum Generation

Create a `.scpdb` file locally without connecting to a remote server:
```sh
java -jar target/java-watch-scp.jar create ./src --includes *.java --excludes target/*
```

### Standalone Get/Put

Download or upload a single file without folder matching or watching:
```sh
# Download remote file to local path
java -jar target/java-watch-scp.jar get /remote/path/file.txt ./local/file.txt -c config.properties

# Upload local file to remote path
java -jar target/java-watch-scp.jar put ./local/file.txt /remote/path/file.txt -c config.properties
```

### Advanced Database Options

- **`local_db=true`**: Stores the `.scpdb` on the local machine — keeps remote directories clean of metadata.
- **Absolute Paths**: If `scpdb` starts with `/` (Linux) or `X:/` (Windows), it is an absolute path.
  - `local_db=false` → absolute path on the **remote** server.
  - `local_db=true` → absolute path on the **local** machine.

### SSH Config Support

The tool reads `~/.ssh/config` (or `%USERPROFILE%\.ssh\config` on Windows). If `host` matches an alias, it fills in missing details:
- `HostName` — actual IP or hostname
- `User` — remote username
- `Port` — SSH port
- `IdentityFile` — private key path (supports `~` expansion)

Explicit settings in `config.properties` always take precedence.

### Using with KeePassXC (SSH Agent)

1. In KeePassXC settings, enable the **SSH Agent** feature.
2. Add your SSH key to a KeePassXC entry and enable **SSH Agent** for that entry.
3. The tool automatically tries the active SSH agent — no `password` or `passphrase` needed in the config file.

On Windows, ensure the `OpenSSH Authentication Agent` service is running and KeePassXC is configured to use it (named pipe).

### Lightweight Sync Modes

**Check Mode (`check`):**
- `hash` (default): Wyhash64 content hash — accurate, reads file content.
- `mtime_size`: Uses modification time and size — faster, no file content read.

**No-DB Mode (`no_db=true`):**
- Skips the `.scpdb` database entirely.
- Uses direct SFTP `stat` calls to decide if upload is needed.
- Ideal for single files or small projects without metadata overhead.

```ini
[folder]
local_dir=C:/projects/assets
remote_dir=/var/www/assets
check=mtime_size
no_db=true
```

### Single File Sync Alias (`[file]`)

A shorthand for syncing a single file using `mtime_size` + `no_db=true`:

```ini
[file]
local_file=D:/project/src/deps.extra.txt
remote_dir=/opt/dev/project/dev33
```

Equivalent to:
```ini
[folder]
local_dir=D:/project/src
remote_dir=/opt/dev/project/dev33
includes=deps.extra.txt
check=mtime_size
no_db=true
```

### Sync Trigger

Copies a file (or creates an empty one) on the remote after each sync — useful for triggering CI/CD pipelines or remote scripts.

```ini
[folder]
local_dir=./src
remote_dir=/opt/app/src
trigger_to=/opt/app/sync-complete.flag
# Optional: local file to copy; omit to create empty file
trigger_from=./local-trigger.txt
```

### Version File Support

Maintains a "heartbeat" version file on the remote, updated every sync.

**How it works:**
1. Reads local template (`version_from`).
2. Replaces placeholders with current Unix timestamp and `version_name`.
3. Uploads processed file to `version_to`.

`version_from` / `version_to` / `version_name` can be global or per-folder (per-folder takes priority).

**Placeholder injection (all formats):**

| Placeholder | Replaced with |
|-------------|---------------|
| `${timestamp}` | Current Unix timestamp (seconds) |
| `${name}` | Value of `version_name` |
| `${version_name}` | Value of `version_name` (alias) |

**Automatic field injection by file extension:**

| Extension | Field replaced | Example result |
|-----------|---------------|----------------|
| `.json` | `"timestamp": <old>` | `"timestamp": 1715170800` |
| `.json` | `"name": "<old>"` | `"name": "MyProject-1.0"` |
| `.ini` | `timestamp=<old>` | `timestamp=1715170800` |
| `.ini` | `name=<old>` | `name=MyProject-1.0` |

**JSON template:**
```json
{ "name": "placeholder", "timestamp": 0 }
```
After sync:
```json
{ "name": "MyProject-1.0", "timestamp": 1715170800 }
```

**Generic template:**
```
version=${name} built at ${timestamp}
```
After sync:
```
version=MyProject-1.0 built at 1715170800
```

### Remote Permissions and ACL Support

The tool uses **neutral SFTP permissions** (mode `0`) when creating files and directories — letting the server's `umask` and ACLs govern final permissions.

**Why this matters:**
- **ACL Compatibility**: Explicitly setting `0644` during creation can override the ACL mask on many SFTP servers, capping permissions granted by other ACL entries.
- **Shared Group Folders**: Allows uploading to directories owned by other users where you have group write access.

The tool avoids `sftp_setstat` / `sftp_fsetstat` calls, so it never tries to change ownership or permissions on files it doesn't own.

---

## How It Works

1. **Initial Sync**:
   - Downloads `.scpdb` from remote (if exists and `no_db=false`)
   - Scans local files; detects changes using Wyhash64 or `mtime`+`size`
   - Uploads only changed/new files
   - Prunes `.scpdb`: removes entries for files no longer present locally

2. **Initial Cleanup** (if `--cleanup`):
   - Lists all remote files matching patterns
   - Removes remote files missing locally

3. **Watch Mode** (if `-w`):
   - Monitors source directories for file changes
   - On change: uploads if different, updates `.scpdb`

---

## License

MIT
