# Java Watch SCP

Watch local directories and instantly sync changed files to a remote server over SSH/SCP — with checksum-based deduplication so only what changed gets transferred.

*Uses [DirectoryWatcher](https://github.com/gmethvin/directory-watcher) because the Java built-in file watcher is still not reliable (as of Java 25).*

## Quick Start

**1. Build:**
```bash
mvn clean package
```

**2. Create `config.properties`:**
```ini
host=myserver.com
username=deploy
key_path=~/.ssh/id_rsa

[folder]
local_dir=C:/projects/myapp/src
remote_dir=/opt/app/src
excludes=target/**
```

**3. Run:**
```bash
# One-shot sync
java -jar target/java-watch-scp.jar -c config.properties

# Continuous watch mode
java -jar target/java-watch-scp.jar -w -c config.properties
```

That's it. The tool syncs only changed files on startup, then watches and re-syncs on every save.

## What It Can Do

- **Multiple folder pairs** — sync several directories in one config, with per-folder include/exclude patterns
- **Fast change detection** — content hashing (default) or `mtime`+`size` for large binary trees
- **SSH agent support** — works with KeePassXC and other agents; no credentials in config files
- **SSH config integration** — use `~/.ssh/config` aliases as the `host` value
- **Version file** — automatically writes a timestamped JSON/INI "heartbeat" to the remote on each sync
- **Sync trigger** — touch a remote file after sync to kick off a CI/CD pipeline
- **Config variables** — reuse one config across environments with `${VARNAME}` expansion and `--var` overrides
- **Local copy worker** — sync between local directories without SSH
- **stdin config** — pipe or heredoc a config with `-c -`

## A More Complex Example

Reusable config with environment-driven variables:

```ini
# Defaults — override any of these from the shell or --var
ENV.HOST=dev.example.com
ENV.REMOTE_BASE=/opt/app
ENV.SUBDIR=project

host=${HOST}

[folder]
local_dir=C:/projects/${SUBDIR}/src
remote_dir=${REMOTE_BASE}/${SUBDIR}/src
excludes=target/**

version_from=C:/projects/${SUBDIR}/version.json
version_to=${REMOTE_BASE}/${SUBDIR}/version.json
version_name=${SUBDIR}
```

```bash
# Use file defaults
java -jar target/java-watch-scp.jar -w -c config.properties

# Override for a feature branch — beats env vars
java -jar target/java-watch-scp.jar --var SUBDIR=feature-x -w -c config.properties

# Point at prod via env var
HOST=prod.example.com java -jar target/java-watch-scp.jar -c config.properties
```

→ See [Config Variable Expansion](DOCUMENTATION.md#config-variable-expansion) for the full priority rules and reasoning.

## Key CLI Flags

| Flag                                 | Description                         |
| ------------------------------------ | ----------------------------------- |
| `-c <path>` or `-c -`                | Config file path or stdin           |
| `-w`                                 | Watch mode                          |
| `--var NAME=value` / `-D NAME=value` | Set config variable (repeatable)    |
| `--check hash\                       | mtime_size`                         | Change detection mode |
| `--no-db`                            | Skip remote checksum database       |
| `--cleanup`                          | Remove remote files missing locally |
| `--dry-run`                          | Show changes without making them    |
| `--exec <cmd>`                       | Run command on remote after sync    |

## Full Documentation

→ **[DOCUMENTATION.md](DOCUMENTATION.md)** — complete config reference, all CLI options, SSH agent setup, ACL permissions, version files, glob patterns, and more.

## License

MIT
