


```js
// watch-and-scp-lib.ts
import { watch } from "fs";
import { join } from "path";
import { Client as ScpClient, config as ScpConfig } from "node-scp";

// ---- config ----
const localDir = "/path/to/watch";

const scpConfig: ScpConfig = {
  host: "example.com",
  port: 22,
  username: "user",
  // one of:
  password: "secret",
  // privateKey: require("fs").readFileSync("/path/to/id_rsa"),
};

const remoteBaseDir = "/remote/path";
const maxParallel = 4;

// ---- simple queue with concurrency limit ----
type Job = () => Promise<void>;

const queue: Job[] = [];
let running = 0;

function enqueue(job: Job) {
  queue.push(job);
  runNext();
}

async function runNext() {
  if (running >= maxParallel) return;
  const job = queue.shift();
  if (!job) return;
  running++;
  try {
    await job();
  } catch (err) {
    console.error("Job failed:", err);
  } finally {
    running--;
    void runNext();
  }
}

// ---- create one shared SCP client ----
let clientPromise: Promise<ScpClient> | null = null;

function getClient(): Promise<ScpClient> {
  if (!clientPromise) {
    clientPromise = ScpClient(scpConfig);
  }
  return clientPromise;
}

async function scpFile(localPath: string, relPath: string) {
  const client = await getClient();
  const remotePath = join(remoteBaseDir, relPath).replace(/\\/g, "/");

  // node-scp exposes .upload and .uploadDir. [web:33]
  await client.upload(localPath, remotePath);
  console.log(`Copied ${relPath} -> ${remotePath}`);
}

// ---- watch directory ----
const watcher = watch(
  localDir,
  { recursive: true }, // Bun supports Node's fs.watch API. [web:17]
  (eventType, filename) => {
    if (!filename) return;
    if (eventType !== "rename" && eventType !== "change") return;

    const relPath = filename.toString();
    const fullPath = join(localDir, relPath);

    enqueue(() => scpFile(fullPath, relPath));
  },
);

console.log(`Watching ${localDir} and syncing via SCP to ${scpConfig.host}:${remoteBaseDir}`);

process.on("SIGINT", async () => {
  watcher.close();
  if (clientPromise) {
    const c = await clientPromise;
    await c.close();
  }
  console.log("Stopped watcher");
  process.exit(0);
});
```
