// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
const API = {
    status: () => fetch('/status').then(r => r.json()),
    actions: () => fetch('/actions').then(r => r.json()),
    accept: (id) => fetch(`/accept?idx=${id}`).then(r => r.json()),
    reject: (id) => fetch(`/reject?idx=${id}`).then(r => r.json()),
    diff: (id) => fetch(`/diff?idx=${id}`).then(r => r.json()),
    // Plan step 7.2: the remote jump goes through OUR server, which holds the sidecar's token. The page never sees
    // the token, and the request is same-origin, so the sidecar's own origin gate does not have to allow this page.
    jump: (file, line) => fetch('/jump', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uri: file, line: line })
    }).then(async (response) => ({ status: response.status, body: await response.json() }))
};

const state = {
    actions: [],
    currentDiffId: null
};

async function reload() {
    try {
        state.actions = await API.actions();
        renderActions();
        document.getElementById('status-badge').textContent = 'Live';
        document.getElementById('status-badge').className = 'badge';
    } catch (e) {
        document.getElementById('status-badge').textContent = 'Disconnected';
        document.getElementById('status-badge').className = 'badge btn-danger';
    }
}

function renderActions() {
    const list = document.getElementById('actions-list');
    if (state.actions.length === 0) {
        list.innerHTML = '<div class="card">No pending actions</div>';
        return;
    }

    list.innerHTML = state.actions.map(action => `
        <div class="card">
            <div class="card-header">
                <div class="card-title">${action.tool}</div>
                <div class="card-meta">Agent: ${action.agent} | File: ${action.file}:${action.line}</div>
            </div>
            <div class="card-footer">
                <button onclick="showDiff(${action.id})" class="btn btn-secondary">Review Diff</button>
                <button onclick="jumpToEditor(${action.id})" class="btn btn-secondary">Jump to editor</button>
                <span id="jump-result-${action.id}" class="jump-result"></span>
            </div>
        </div>
    `).join('');
}

/**
 * Plan step 7.2: ask the editor to jump to an action's location, and show what the SIDECAR reported.
 *
 * The sidecar reports a navigation OUTCOME rather than an assumed success (its rule since 2026-09-25): the editor
 * may have reached the exact line, only opened the file, or been unable to be asked at all. So the answer is
 * displayed as reported - `reason`/`detail` when the sidecar named one, its `error` when it refused, and the raw
 * body as a last resort - and a 502 means no sidecar was listening on the port. "Jumping..." is deliberately not
 * left on screen after a failure, because that is exactly the assumed success this step exists to remove.
 */
async function jumpToEditor(id) {
    const action = state.actions.find(a => a.id === id)
    const target = document.getElementById(`jump-result-${id}`)
    target.textContent = 'Jumping...'
    target.className = 'jump-result'
    try {
        const answer = await API.jump(action.file, action.line)
        const body = answer.body || {}
        target.textContent = body.detail || body.reason || body.error || JSON.stringify(body)
        target.className = answer.status === 200 ? 'jump-result' : 'jump-result jump-failed'
    } catch (e) {
        target.textContent = `Jump failed: ${e.message}`
        target.className = 'jump-result jump-failed'
    }
}

async function showDiff(id) {
    state.currentDiffId = id;
    const diffs = await API.diff(id);
    const container = document.getElementById('diff-container');
    const action = state.actions.find(a => a.id === id);

    document.getElementById('diff-title').textContent = `Review: ${action.tool}`;
    container.innerHTML = diffs.map(d => `
        <div class="diff-file">
            <div class="diff-file-header">${d.file}</div>
            <pre><code>${formatDiff(d.before, d.after)}</code></pre>
        </div>
    `).join('');

    document.getElementById('diff-section').classList.remove('hidden');
}

function formatDiff(before, after) {
    // Simple naive diff for visualization
    const bLines = before.split('\n');
    const aLines = after.split('\n');

    // This is a very basic visualization, ideally use a diff library, 
    // but we are keeping it no-framework.
    if (before === after) return 'No changes detected.';

    return `// Changes in lines...\n<ins>Updated content generated.</ins>`;
}

async function applyAction() {
    if (state.currentDiffId === null) return;
    await API.accept(state.currentDiffId);
    closeDiff();
    reload();
}

async function rejectAction() {
    if (state.currentDiffId === null) return;
    await API.reject(state.currentDiffId);
    closeDiff();
    reload();
}

function closeDiff() {
    document.getElementById('diff-section').classList.add('hidden');
    state.currentDiffId = null;
}

document.getElementById('close-diff').onclick = closeDiff;
document.getElementById('modal-accept').onclick = applyAction;
document.getElementById('modal-reject').onclick = rejectAction;

// Poll for updates
setInterval(reload, 2000);
reload();
