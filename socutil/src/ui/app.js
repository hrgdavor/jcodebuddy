"use strict";

const $ = (id) => document.getElementById(id);

const state = {
  /** @type {{id: string, path: string, source: EventSource, decoder: TextDecoder} | null} */
  session: null,
  sockets: [],
};

function setStatus(text, kind = "") {
  const node = $("status");
  node.textContent = text;
  node.className = "status" + (kind ? " " + kind : "");
}

function setInputEnabled(enabled) {
  $("input").disabled = !enabled;
  $("send").disabled = !enabled;
  $("disconnect").disabled = !enabled;
  if (!enabled) $("input").value = "";
}

function appendText(text) {
  const output = $("output");
  if (text.length === 0) return;
  const pinned = output.scrollTop + output.clientHeight >= output.scrollHeight - 12;
  output.append(document.createTextNode(text));
  if (pinned) output.scrollTop = output.scrollHeight;
}

function randomSessionId() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function base64ToBytes(encoded) {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function refreshSockets() {
  setStatus("scanning", "busy");
  try {
    const response = await fetch("/api/sockets", { cache: "no-store" });
    if (!response.ok) throw new Error(await response.text());
    const data = await response.json();
    state.sockets = data.sockets || [];
    renderSockets(data.dirs || []);
    setStatus(state.session ? "connected" : "idle", state.session ? "ok" : "");
  } catch (error) {
    console.error(error);
    setStatus("scan failed", "error");
  }
}

function renderSockets(dirs) {
  const list = $("sockets");
  list.replaceChildren();

  for (const socket of state.sockets) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "socket";
    button.title = socket.path;
    if (state.session && state.session.path === socket.path) button.classList.add("active");

    const name = document.createElement("span");
    name.className = "socket-name";
    name.textContent = socket.name;

    const dir = document.createElement("span");
    dir.className = "socket-dir";
    dir.textContent = socket.dir;

    button.append(name, dir);
    button.addEventListener("click", () => connectTo(socket));
    item.append(button);
    list.append(item);
  }

  $("empty").hidden = state.sockets.length !== 0;
  $("empty").textContent = dirs.length === 0 ? "No directories configured." : "No sockets found.";
}

function connectTo(socket) {
  closeSession(false);

  const id = randomSessionId();
  const params = new URLSearchParams({ id, path: socket.path });
  const source = new EventSource("/api/events?" + params.toString());
  const session = { id, path: socket.path, source, decoder: new TextDecoder() };
  state.session = session;

  $("output").textContent = "";
  $("target").textContent = socket.path;
  setStatus("connecting", "busy");
  setInputEnabled(false);
  renderSocketsActive();

  source.addEventListener("ready", () => {
    if (state.session !== session) return;
    setStatus("connected", "ok");
    setInputEnabled(true);
    $("input").focus();
    appendText("[connected to " + socket.path + "]\n");
  });

  source.addEventListener("out", (event) => {
    if (state.session !== session) return;
    try {
      const payload = JSON.parse(event.data);
      appendText(session.decoder.decode(base64ToBytes(payload.b), { stream: true }));
    } catch (error) {
      console.error("bad output event", error);
    }
  });

  source.addEventListener("exit", (event) => {
    if (state.session !== session) return;
    let reason = "closed";
    try {
      reason = JSON.parse(event.data).reason || reason;
    } catch {
      /* keep the default */
    }
    appendText("\n[socket " + reason + "]\n");
    closeSession(false);
  });

  source.addEventListener("error", () => {
    if (state.session !== session) return;
    appendText("\n[connection lost]\n");
    closeSession(false);
  });
}

function renderSocketsActive() {
  const buttons = document.querySelectorAll(".socket");
  buttons.forEach((button) => button.classList.remove("active"));
  if (!state.session) return;
  const list = $("sockets").querySelectorAll(".socket");
  list.forEach((button) => {
    if (button.title === state.session.path) button.classList.add("active");
  });
}

function closeSession(notifyServer = true) {
  const session = state.session;
  if (!session) return;
  state.session = null;
  session.source.close();

  if (notifyServer) {
    fetch("/api/close?" + new URLSearchParams({ id: session.id }).toString(), { method: "POST" }).catch(
      () => {},
    );
  }

  setStatus("idle");
  setInputEnabled(false);
  $("target").textContent = "not connected";
  renderSocketsActive();
}

$("refresh").addEventListener("click", refreshSockets);
$("disconnect").addEventListener("click", () => {
  closeSession(true);
  appendText("\n[disconnected]\n");
});

$("input-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const session = state.session;
  if (!session) return;

  const input = $("input");
  const line = input.value;
  input.value = "";

  try {
    const response = await fetch("/api/input?" + new URLSearchParams({ id: session.id }).toString(), {
      method: "POST",
      headers: { "content-type": "text/plain; charset=utf-8" },
      body: line + "\n",
    });
    if (!response.ok) {
      const detail = (await response.text()).trim();
      appendText("\n[input failed" + (detail ? ": " + detail : "") + "]\n");
    }
  } catch (error) {
    appendText("\n[input failed: " + error.message + "]\n");
  }
});

window.addEventListener("beforeunload", () => {
  const session = state.session;
  if (session) {
    navigator.sendBeacon("/api/close?" + new URLSearchParams({ id: session.id }).toString());
  }
});

refreshSockets();
