// Minimal bom-migrate web UI.
// Fetches discovery report from /api, lets user include/exclude candidates,
// assign them to modules, confirm versions, and generate the BOM.
//
// This is a hand-written vanilla JS SPA, meant to be rebuildable into a proper
// React+Vite app later. No external runtime dependencies.

const api = {
    async getReport() {
        const res = await fetch("/api/discovery");
        if (res.status === 204) return null;
        return res.json();
    },
    async getScanMetadata() {
        const res = await fetch("/api/scan/metadata");
        if (res.status === 204) return null;
        return res.json();
    },
    async getModules() { return (await fetch("/api/modules")).json(); },
    async setModules(modules) {
        const res = await fetch("/api/modules", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(modules)
        });
        return res.json();
    },
    async confirm(assignments) {
        const res = await fetch("/api/discovery/confirm", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ assignments })
        });
        return res.json();
    },
    async generate(versionFormat) {
        const res = await fetch("/api/bom/generate", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ versionFormat })
        });
        return res.json();
    }
};

const state = {
    report: null,
    scanMetadata: null,
    modules: [],
    selections: new Map() // key -> { included, version, moduleName }
};

function $(id) { return document.getElementById(id); }

function renderScanMetadata() {
    const summary = $("sources-summary");
    const list = $("sources-list");
    const warnings = $("sources-warnings");
    list.innerHTML = "";
    warnings.innerHTML = "";

    const meta = state.scanMetadata;
    if (!meta) {
        summary.textContent = "No scan metadata available.";
        return;
    }

    const scanned = meta.scannedSources || [];
    const skippedLang = meta.skippedByLanguage || [];
    const skippedNoPom = meta.skippedNoPom || [];
    const failed = meta.failedClones || [];

    const parts = [`${scanned.length} POM file${scanned.length === 1 ? "" : "s"} analysed`];
    if (skippedLang.length > 0) parts.push(`${skippedLang.length} repo(s) skipped by language filter`);
    if (skippedNoPom.length > 0) parts.push(`${skippedNoPom.length} repo(s) had no pom.xml`);
    if (failed.length > 0) parts.push(`${failed.length} repo(s) failed to clone`);
    summary.textContent = parts.join(" · ");

    scanned.forEach(source => {
        const li = document.createElement("li");
        li.textContent = source;
        list.appendChild(li);
    });

    const warningSections = [];
    if (skippedLang.length > 0) {
        warningSections.push(buildWarningSection("Skipped (not JVM primary language)", skippedLang));
    }
    if (skippedNoPom.length > 0) {
        warningSections.push(buildWarningSection("Skipped (no pom.xml found)", skippedNoPom));
    }
    if (failed.length > 0) {
        const items = failed.map(f => `${f.repoName}: ${f.reason}`);
        warningSections.push(buildWarningSection("Failed to clone", items));
    }
    warningSections.forEach(section => warnings.appendChild(section));
}

function buildWarningSection(title, items) {
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = `${title} (${items.length})`;
    details.appendChild(summary);
    const ul = document.createElement("ul");
    items.forEach(item => {
        const li = document.createElement("li");
        li.textContent = item;
        ul.appendChild(li);
    });
    details.appendChild(ul);
    return details;
}

function renderModules() {
    const list = $("module-list");
    list.innerHTML = "";
    state.modules.forEach((mod, idx) => {
        const chip = document.createElement("div");
        chip.className = "module-chip";
        chip.innerHTML = `<span>${mod.name}</span><button data-idx="${idx}">×</button>`;
        chip.querySelector("button").addEventListener("click", () => {
            state.modules.splice(idx, 1);
            renderModules();
        });
        list.appendChild(chip);
    });
}

function renderCandidates() {
    const tbody = $("candidate-tbody");
    tbody.innerHTML = "";
    if (!state.report) return;

    state.report.candidates.forEach(c => {
        const key = c.groupId + ":" + c.artifactId;
        const sel = state.selections.get(key) || {
            included: true,
            version: c.suggestedVersion,
            moduleName: state.modules[0]?.name || ""
        };
        state.selections.set(key, sel);

        const tr = document.createElement("tr");

        const versionsText = Object.entries(c.versions)
            .map(([v, n]) => `${v} (${n})`).join(", ");

        tr.innerHTML = `
            <td><input type="checkbox" ${sel.included ? "checked" : ""}/></td>
            <td>${c.groupId}:<strong>${c.artifactId}</strong></td>
            <td>${c.serviceCount}/${c.totalServicesScanned}</td>
            <td><small>${versionsText}</small></td>
            <td class="conflict-${c.conflictSeverity}">${c.conflictSeverity}</td>
            <td><input type="text" value="${sel.version}"/></td>
            <td>${renderModuleSelect(sel.moduleName)}</td>
        `;

        tr.querySelector("input[type=checkbox]").addEventListener("change", e => {
            sel.included = e.target.checked;
        });
        tr.querySelector("input[type=text]").addEventListener("change", e => {
            sel.version = e.target.value;
        });
        const sel_el = tr.querySelector("select");
        if (sel_el) {
            sel_el.addEventListener("change", e => { sel.moduleName = e.target.value; });
        }

        tbody.appendChild(tr);
    });

    $("summary").textContent =
        `${state.report.candidates.length} candidates across ${state.report.totalServicesScanned} services`;
}

function renderModuleSelect(currentName) {
    if (state.modules.length === 0) return "<em>no modules</em>";
    const options = state.modules
        .map(m => `<option value="${m.name}" ${m.name === currentName ? "selected" : ""}>${m.name}</option>`)
        .join("");
    return `<select>${options}</select>`;
}

async function init() {
    state.report = await api.getReport();
    state.scanMetadata = await api.getScanMetadata();
    state.modules = await api.getModules();
    if (state.modules.length === 0) {
        state.modules = [{ name: "default" }];
    }
    renderScanMetadata();
    renderModules();
    renderCandidates();
}

$("add-module-btn").addEventListener("click", () => {
    const name = $("new-module-name").value.trim();
    if (!name) return;
    state.modules.push({ name });
    $("new-module-name").value = "";
    renderModules();
    renderCandidates();
});

$("save-modules-btn").addEventListener("click", async () => {
    await api.setModules(state.modules);
    alert("Modules saved.");
});

function selectedVersionFormat() {
    const checked = document.querySelector('input[name="version-format"]:checked');
    return checked ? checked.value : "INLINE";
}

async function copyToClipboard(text, button) {
    try {
        await navigator.clipboard.writeText(text);
        const original = button.textContent;
        button.textContent = "Copied!";
        button.classList.add("copied");
        setTimeout(() => {
            button.textContent = original;
            button.classList.remove("copied");
        }, 1500);
    } catch (err) {
        // Fallback: create a temporary textarea and use document.execCommand
        const ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand("copy"); } catch (e) { /* ignore */ }
        document.body.removeChild(ta);
        button.textContent = "Copied!";
        setTimeout(() => { button.textContent = "Copy"; }, 1500);
    }
}

function renderGeneratedFile(path, content) {
    const wrapper = document.createElement("div");
    wrapper.className = "generated-file";

    const header = document.createElement("div");
    header.className = "generated-file-header";

    const heading = document.createElement("h3");
    heading.textContent = path;
    header.appendChild(heading);

    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "copy-btn";
    copyBtn.textContent = "Copy";
    copyBtn.addEventListener("click", () => copyToClipboard(content, copyBtn));
    header.appendChild(copyBtn);

    wrapper.appendChild(header);

    const pre = document.createElement("pre");
    pre.textContent = content;
    wrapper.appendChild(pre);

    return wrapper;
}

$("generate-btn").addEventListener("click", async () => {
    // Build assignments from selections
    const assignments = [];
    for (const c of state.report.candidates) {
        const key = c.groupId + ":" + c.artifactId;
        const sel = state.selections.get(key);
        if (!sel || !sel.included) continue;
        const module = state.modules.find(m => m.name === sel.moduleName) || state.modules[0];
        assignments.push({
            candidate: c,
            module,
            confirmedVersion: sel.version
        });
    }

    await api.setModules(state.modules);
    await api.confirm(assignments);
    const result = await api.generate(selectedVersionFormat());

    $("result-panel").classList.remove("hidden");
    $("result-output").innerHTML = "";
    if (result.written && result.written.length > 0) {
        const note = document.createElement("p");
        note.innerHTML = `<strong>Wrote to disk:</strong> ${result.written.join(", ")}`;
        $("result-output").appendChild(note);
    }
    Object.entries(result.files).forEach(([path, content]) => {
        $("result-output").appendChild(renderGeneratedFile(path, content));
    });
});

init().catch(err => {
    console.error(err);
    alert("Failed to load discovery report: " + err.message);
});
