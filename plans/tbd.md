# TBD — deferred work, deliberately not scheduled

**Status: 2026-10-28.** The maintainer moved **ACP** (5.3, 8.4 and 8.5's ACP item), the **Eclipse observations**
(8.2) and the **in-IDE handshake walk** (8.3) here: they are deferred for later, not part of the live schedule. The
live schedule stays [`unified-plan.md`](unified-plan.md); a step in this file is picked up by copying it back (with
its step id) when someone decides to run it.

This file currently holds:

- **5.3** — the ACP go/no-go spike (moved from Phase 5 of the unified plan);
- **8.2** — the Eclipse 4.41 workbench observations (moved from Phase 8);
- **8.3** — the in-IDE walk of the delivered agent handshake (moved from Phase 8);
- **8.4** — the Zed ACP run, which waits on 5.3;
- **8.5's ACP item** — the one open part of the Zed survey; the survey itself and its non-ACP decision stay in
  the unified plan.

**Why deferred.** ACP was a spike, not a deliverable: it answers whether the Zed Agent Panel is a useful additional
surface. The answer is not needed for any page, host or navigation path, and the existing Zed routes are already
implemented and measured — the browser page, `webviewd`'s `LspHost` for the caret, and `ZedCliHost` for opening the
file. See [`../webview/doc/zed-integration.md`](../webview/doc/zed-integration.md) for the survey and the
non-ACP decision. The Eclipse observations wait on a person who actually uses Eclipse — none is available — and
the 8.3 walk needs a running editor; its code half (the handshake) is already delivered and tested.

---

## 5.3 — The ACP go/no-go spike (human)
**Who:** human (Zed 1.21, from a normal shell) · **Size:** S

Phase 5's spike "ends in a written go/no-go" and no such document exists. Run it as the plan describes
(`webviewd --acp` registered as a custom agent, `initialize` / `session/new` / `session/prompt`, one tool
calling the same command layer as `/api/v1`), then write the verdict — including "no, and here is why" —
into the plan's Phase 5 record.

**Gate:** the written verdict exists and the plan links to it.

**Done when:** ACP is either scheduled as its own plan or dropped with a reason.

**Deferred 2026-10-28.** No part of it has been run; the written verdict is the whole deliverable. The
`webviewd --acp` surface and the command layer it would call already exist, so resuming means the spike only, not
building a bridge first.

## 8.2 — Eclipse 4.41 workbench observations (human)
**Who:** human with Eclipse 4.41 (2026-09) · **Size:** S

Run the Eclipse host's observation checklist against the 4.41 build: the caret landing, the unsaved buffer edit and
the single `Ctrl+Z`, the `dropins/` install layout, and the two-live-hosts claim. Q2 is already answered — the first
release ships by `dropins/` — and Phase 5 (the Tycho/p2 update site) is declined; this is the observation half only.
The checklist is [`../webview/doc/ide-observation-checklist.md`](../webview/doc/ide-observation-checklist.md) § 1a
and § 2a, and the host's own plan is [`../webview/PLAN-eclipse-host.md`](../webview/PLAN-eclipse-host.md).

**Gate:** a dated, version-named observation record in the host's plan/checklist; nothing is claimed as observed
without it.

**Deferred 2026-10-28 — no interested Eclipse user.** The maintainer will run this if someone who uses Eclipse is
found; until then the host stays implemented and headless-tested.

## 8.3 — The in-IDE walk of the agent handshake (human)
**Who:** human with a running editor · **Size:** S

The code half is **delivered** (2026-10-10): `WebviewHandshake` resolves a host's published port — a descriptor that
names this project and comes from a live pid wins; anything else falls back to the system property, else 7979 — and
the token is read from the descriptor's `tokenPath`. The agent module's **30 tests, 0 failures** (9 new in
`WebviewHandshakeTest`; `RemoteJumpTest`'s 2 unchanged) prove the property fallback still works. What remains is the
human half: with a host live on a **non-default** port, open the agent dashboard, use a "jump to source" action, and
confirm the caret lands. The expected evidence is the port the dashboard used (the descriptor's, not 7979) and the
editor's own tab; `describe()` and the per-jump debug line answer "which port did it choose" when it does not.

**Gate:** the in-IDE walk is recorded with the port and the editor tab it reached, in
[`../jcodebuddy/jcodebuddy-agent/plan.md`](../jcodebuddy/jcodebuddy-agent/plan.md).

**Deferred 2026-10-28** — the maintainer moved the walk here; it needs a running editor, and the code it exercises is
already delivered.

## 8.4 — The ACP spike's Zed run (human)
**Who:** human · **Size:** S

Run the ACP spike of 5.3 in Zed 1.21 and record the verdict in the Phase 5 record. This is the ACP half of the
editor work; it is not the in-IDE handshake walk (8.3, also in this file).

**Gate:** the 5.3 verdict names what the Zed run observed, and `webview/doc/zed-integration.md`'s ACP item is
answered in the same breath.

**Deferred 2026-10-28** with 5.3.

## 8.5 item 3 — the ACP path (from the Zed survey)
**Who:** agent, after a positive 5.3 verdict · **Size:** S

The Zed survey's other three items are answered in
[`../webview/doc/zed-integration.md`](../webview/doc/zed-integration.md): no extension and no new Zed page host; the
browser + `webviewd` path is the supported experience. The remaining item is the ACP path: if 5.3 says yes, schedule
the ACP bridge beside the existing command layer (the same tool `/api/v1` exposes), never as a page host; if it says
no, record that verdict and close the item.

**Gate:** the ACP item has an answer, linked from the survey.

**Deferred 2026-10-28** with 5.3.

---

## When this file is picked up

1. **8.2** first needs an interested Eclipse user; run the checklist on the 4.41 build and write the dated record.
2. **8.3** needs a running editor: with a host on a non-default port, do the dashboard jump and record the port and
   tab it reached. No code change is expected; the handshake is delivered.
3. **ACP**: run 5.3 first; its verdict is the input for 8.4 and 8.5 item 3.
4. If the ACP verdict is positive, write the ACP bridge as its own plan/steps (it is a command-layer client, not a
   webview host) and record the observation in 8.4.
5. If the ACP verdict is negative, record the reason here and in
   [`../webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md)'s Phase 5 record; no ACP work is scheduled.
6. Remove the copied steps from this file when they land in the live schedule, so a TBD entry does not outlive the
   decision.
