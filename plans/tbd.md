# TBD — deferred work, deliberately not scheduled

**Status: 2026-10-28.** The maintainer moved **ACP** here: it is deferred for later, not blocked on a missing
tool and not part of the live schedule. The live schedule stays
[`unified-plan.md`](unified-plan.md); a step in this file is picked up by copying it back (with its step id) when
someone decides to run it.

This file currently holds:

- **5.3** — the ACP go/no-go spike (moved from Phase 5 of the unified plan);
- **8.4** — the Zed ACP run, which waits on 5.3;
- **8.5's ACP item** — the one open part of the Zed survey; the survey itself and its non-ACP decision stay in
  the unified plan.

**Why deferred.** ACP was a spike, not a deliverable: it answers whether the Zed Agent Panel is a useful additional
surface. The answer is not needed for any page, host or navigation path, and the existing Zed routes are already
implemented and measured — the browser page, `webviewd`'s `LspHost` for the caret, and `ZedCliHost` for opening the
file. See [`../webview/doc/zed-integration.md`](../webview/doc/zed-integration.md) for the survey and the
non-ACP decision.

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

## 8.4 — The ACP spike's Zed run (human)
**Who:** human · **Size:** S

Run the ACP spike of 5.3 in Zed 1.21 and record the verdict in the Phase 5 record. This is the ACP half of step 8.3's
editor work; it is not the in-IDE handshake walk (8.3 stays in the unified plan).

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

1. Run 5.3 first; its verdict is the input for 8.4 and 8.5 item 3.
2. If the verdict is positive, write the ACP bridge as its own plan/steps (it is a command-layer client, not a
   webview host) and record the observation in 8.4.
3. If the verdict is negative, record the reason here and in
   [`../webview/PLAN-webview-suite.md`](../webview/PLAN-webview-suite.md)'s Phase 5 record; no ACP work is scheduled.
4. Remove the copied steps from this file when they land in the live schedule, so a TBD entry does not outlive the
   decision.
