// AppMap Recording Studio — single-page JS controller for the recording UI.
//
// Wired entirely against the REST surface exposed by RecordingController and
// PlaybookController. Polls the active recording every 2s while it is in a
// non-terminal state so the elapsed/event/action counters tick in real time.

const API = "/api/v1/appmap";
let CURRENT_ID = null;
let POLL_HANDLE = null;

function el(id) { return document.getElementById(id); }

function status(badge, label) {
    const node = el("recordingMode");
    node.className = "mode " + badge;
    node.textContent = label;
}

function showError(msg) {
    const banner = el("recordingError");
    banner.textContent = msg;
    banner.hidden = false;
    setTimeout(() => { banner.hidden = true; }, 6000);
}

async function jsonOrThrow(res) {
    const text = await res.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch (_) { /* keep null */ }
    if (!res.ok) {
        const msg = body && body.message
            ? "HTTP " + res.status + " — " + body.message
            : "HTTP " + res.status + " — " + (text || res.statusText);
        const err = new Error(msg);
        err.status = res.status;
        err.body = body;
        throw err;
    }
    return body;
}

async function api(method, path, body) {
    const init = {
        method,
        credentials: "include",
        headers: { "Accept": "application/json" }
    };
    if (body !== undefined) {
        init.headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
    }
    return jsonOrThrow(await fetch(API + path, init));
}

async function refreshMode() {
    try {
        const s = await api("GET", "/recordings/_status");
        if (s.recordingEnabled) {
            status("ok", "recording enabled");
            el("modeNote").textContent =
                s.active + " active · " + s.total + " in this session · " + s.archiveSize + " archived";
        } else {
            status("warn", "recording disabled");
            el("modeNote").textContent =
                "Restart with -javaagent:appmap-agent.jar (or set omnibank.appmap.synthetic-recording=true) to enable capture.";
        }
    } catch (e) {
        status("err", "studio unreachable");
        el("modeNote").textContent = e.message || String(e);
    }
}

function fmtElapsed(ms) {
    if (ms < 1000) return ms + "ms";
    const s = Math.floor(ms / 1000);
    if (s < 60) return s + "s";
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return m + "m" + rem + "s";
}

function fmtDate(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleString();
}

function fmtSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / 1024 / 1024).toFixed(1) + " MB";
}

function statusBadgeClass(s) {
    if (s === "RECORDING" || s === "READY") return "";
    if (s === "FAILED") return "failed";
    if (s === "CANCELLED") return "cancelled";
    return "terminal";
}

function renderLive(rec) {
    el("liveCard").hidden = false;
    el("liveLabel").textContent = rec.label;
    el("liveId").textContent = rec.id;
    el("liveStatus").textContent = rec.status.toLowerCase();
    el("liveStatus").parentElement.className = "badge " + statusBadgeClass(rec.status);
    el("liveElapsed").textContent = fmtElapsed(rec.elapsedMillis);
    el("liveEvents").textContent = rec.capturedEvents;
    el("liveActions").textContent = rec.actionCount;
    const tl = el("liveTimeline");
    tl.innerHTML = "";
    rec.actions.forEach(a => {
        const li = document.createElement("li");
        li.innerHTML = "<strong>" + escapeHtml(a.kind) + "</strong> "
            + escapeHtml(a.description)
            + (a.reference ? " <span class='ref'>" + escapeHtml(a.reference) + "</span>" : "")
            + " <span class='when'>" + fmtDate(a.performedAt) + "</span>";
        tl.appendChild(li);
    });

    const terminal = ["SAVED", "CANCELLED", "FAILED", "STOPPED"].indexOf(rec.status) >= 0;
    el("btnStop").disabled = terminal;
    el("btnSave").disabled = (rec.status === "SAVED" || rec.status === "CANCELLED" || rec.status === "FAILED");
    el("btnCancel").disabled = terminal;
    el("btnAddAction").disabled = terminal;
    if (terminal) {
        stopPolling();
        if (rec.status === "SAVED") {
            status("ok", "saved · " + rec.savedFile);
        }
    }
}

function escapeHtml(s) {
    if (s == null) return "";
    return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

function startPolling() {
    stopPolling();
    POLL_HANDLE = setInterval(async () => {
        if (!CURRENT_ID) return;
        try {
            const rec = await api("GET", "/recordings/" + CURRENT_ID);
            renderLive(rec);
        } catch (e) {
            console.warn("poll failed", e);
        }
    }, 2000);
}

function stopPolling() {
    if (POLL_HANDLE) {
        clearInterval(POLL_HANDLE);
        POLL_HANDLE = null;
    }
}

async function loadPlaybooks() {
    const grid = el("playbookGrid");
    grid.innerHTML = "<div class='help'>loading playbooks…</div>";
    try {
        const list = await api("GET", "/playbooks");
        grid.innerHTML = "";
        list.forEach(pb => {
            const card = document.createElement("div");
            card.className = "playbook";
            card.innerHTML =
                "<strong>" + escapeHtml(pb.label) + "</strong>"
                + "<em>" + escapeHtml(pb.description) + "</em>"
                + "<button>Run</button>"
                + "<pre hidden></pre>";
            const btn = card.querySelector("button");
            const out = card.querySelector("pre");
            btn.addEventListener("click", async () => {
                btn.disabled = true;
                out.hidden = false;
                out.textContent = "running…";
                try {
                    const result = await api("POST", "/playbooks/" + pb.id, {
                        recordingId: CURRENT_ID
                    });
                    out.textContent = JSON.stringify(result, null, 2);
                    refreshList();
                    if (CURRENT_ID) {
                        const rec = await api("GET", "/recordings/" + CURRENT_ID);
                        renderLive(rec);
                    }
                } catch (e) {
                    out.textContent = "error: " + (e.message || e);
                } finally {
                    btn.disabled = false;
                }
            });
            grid.appendChild(card);
        });
    } catch (e) {
        grid.innerHTML = "<div class='banner err'>Cannot load playbooks: "
            + escapeHtml(e.message) + "</div>";
    }
}

async function refreshList() {
    try {
        const recs = await api("GET", "/recordings");
        const tbody = el("recordingsTableBody");
        tbody.innerHTML = "";
        recs.forEach(r => {
            const tr = document.createElement("tr");
            tr.innerHTML =
                "<td><span class='badge " + statusBadgeClass(r.status) + "'>"
                    + r.status.toLowerCase() + "</span></td>"
                + "<td>" + escapeHtml(r.label) + "</td>"
                + "<td>" + fmtDate(r.startedAt) + "</td>"
                + "<td>" + fmtElapsed(r.elapsedMillis) + "</td>"
                + "<td>" + r.capturedEvents + "</td>"
                + "<td>" + r.actionCount + "</td>"
                + "<td>" + (r.savedFile
                    ? "<code>" + escapeHtml(r.savedFile) + "</code>"
                    : "—")
                + "</td>"
                + "<td>"
                + (r.savedFile
                    ? "<a class='btn' href='" + API + "/recordings/" + encodeURIComponent(r.id) + "/download'>download</a>"
                    : "<button data-id='" + escapeHtml(r.id) + "'>open</button>")
                + "</td>";
            const openBtn = tr.querySelector("button[data-id]");
            if (openBtn) {
                openBtn.addEventListener("click", () => {
                    CURRENT_ID = r.id;
                    renderLive(r);
                    if (!r.status || r.status === "RECORDING" || r.status === "READY") {
                        startPolling();
                    }
                });
            }
            tbody.appendChild(tr);
        });
    } catch (e) {
        showError("List refresh failed: " + e.message);
    }
    try {
        const archive = await api("GET", "/recordings/_archive");
        const tbody = el("archiveTableBody");
        tbody.innerHTML = "";
        archive.forEach(f => {
            const tr = document.createElement("tr");
            tr.innerHTML =
                "<td><code>" + escapeHtml(f.name) + "</code></td>"
                + "<td>" + fmtSize(f.sizeBytes) + "</td>"
                + "<td>" + fmtDate(new Date(f.lastModified).toISOString()) + "</td>"
                + "<td>"
                + "<a class='btn' href='" + API + "/recordings/" + encodeURIComponent(f.name.replace(/\.appmap\.json$/, '')) + "/download'>download</a>"
                + " <button data-del='" + escapeHtml(f.name) + "'>delete</button>"
                + "</td>";
            const delBtn = tr.querySelector("button[data-del]");
            if (delBtn) {
                delBtn.addEventListener("click", async () => {
                    if (!confirm("Delete " + f.name + "?")) return;
                    try {
                        await api("DELETE", "/recordings/_archive/" + encodeURIComponent(f.name));
                        refreshList();
                    } catch (e) {
                        showError("Delete failed: " + e.message);
                    }
                });
            }
            tbody.appendChild(tr);
        });
    } catch (e) {
        // archive listing is best-effort
    }
}

function bindButtons() {
    el("btnStart").addEventListener("click", async () => {
        const label = el("newLabel").value.trim();
        const description = el("newDescription").value.trim();
        try {
            const rec = await api("POST", "/recordings", { label, description });
            CURRENT_ID = rec.id;
            el("newLabel").value = "";
            el("newDescription").value = "";
            renderLive(rec);
            startPolling();
            refreshList();
        } catch (e) {
            showError(e.message);
        }
    });

    el("btnStop").addEventListener("click", async () => {
        if (!CURRENT_ID) return;
        try {
            const rec = await api("POST", "/recordings/" + CURRENT_ID + "/stop");
            renderLive(rec);
            refreshList();
        } catch (e) {
            showError(e.message);
        }
    });

    el("btnSave").addEventListener("click", async () => {
        if (!CURRENT_ID) return;
        try {
            const rec = await api("POST", "/recordings/" + CURRENT_ID + "/save");
            renderLive(rec);
            refreshList();
        } catch (e) {
            showError(e.message);
        }
    });

    el("btnCancel").addEventListener("click", async () => {
        if (!CURRENT_ID) return;
        const reason = prompt("Cancel reason (optional)", "user discarded") || "";
        try {
            const rec = await api("POST", "/recordings/" + CURRENT_ID + "/cancel", { reason });
            renderLive(rec);
            refreshList();
        } catch (e) {
            showError(e.message);
        }
    });

    el("btnAddAction").addEventListener("click", async () => {
        if (!CURRENT_ID) return;
        const kind = el("actionKind").value.trim() || "manual.note";
        const description = el("actionDescription").value.trim() || "(no description)";
        const reference = el("actionReference").value.trim() || null;
        try {
            const rec = await api("POST", "/recordings/" + CURRENT_ID + "/actions",
                    { kind, description, reference });
            el("actionKind").value = "";
            el("actionDescription").value = "";
            el("actionReference").value = "";
            renderLive(rec);
        } catch (e) {
            showError(e.message);
        }
    });

    el("btnRefresh").addEventListener("click", refreshList);
}

document.addEventListener("DOMContentLoaded", async () => {
    bindButtons();
    await refreshMode();
    await loadPlaybooks();
    await refreshList();
});
