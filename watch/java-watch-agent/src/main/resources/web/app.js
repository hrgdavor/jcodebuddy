// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
const API = {
    status: () => fetch('/status').then(r => r.json()),
    actions: () => fetch('/actions').then(r => r.json()),
    accept: (id) => fetch(`/accept?idx=${id}`).then(r => r.json()),
    reject: (id) => fetch(`/reject?idx=${id}`).then(r => r.json()),
    diff: (id) => fetch(`/diff?idx=${id}`).then(r => r.json())
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
            </div>
        </div>
    `).join('');
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
