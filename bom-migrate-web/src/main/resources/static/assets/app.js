// Minimal bom-migrate web UI.
// Flow:
//   1. User picks pom.xml files via the native file picker, uploads, backend scans
//   2. User reviews candidates and defines modules
//   3. User clicks Generate BOM
//   4. UI fetches the migration preview and shows the import snippet + per-service
//      modified POMs (copy-paste only, never writes back to disk).
//
// Hand-written vanilla JS SPA, meant to be rebuildable into a proper
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
    async uploadPoms(files) {
        const form = new FormData();
        for (const f of files) form.append("files", f, f.name);
        const res = await fetch("/api/scan/upload", { method: "POST", body: form });
        if (!res.ok) throw new Error("Upload failed: " + res.status);
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
    },
    async getMigrationPreview() {
        const res = await fetch("/api/migration/preview");
        if (res.status === 412) return null;
        if (!res.ok) throw new Error("Migration preview failed: " + res.status);
        return res.json();
    }
};

const state = {
    report: null,
    scanMetadata: null,
    modules: [],
    selections: new Map(), // key -> { included, version, moduleName }
    pendingFiles: []       // File objects selected but not yet uploaded
};

function $(id) { return document.getElementById(id); }

/* ---------- copy-to-clipboard ---------- */

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

/* ---------- upload panel ---------- */

function renderPendingFiles() {
    const list = $("selected-files-list");
    list.innerHTML = "";
    state.pendingFiles.forEach(f => {
        const li = document.createElement("li");
        li.textContent = f.name;
        list.appendChild(li);
    });
    const count = state.pendingFiles.length;
    $("file-count").textContent = count === 0
        ? "No files selected"
        : `${count} file${count === 1 ? "" : "s"} selected`;
    $("upload-btn").disabled = count === 0;
}

$("pom-file-input").addEventListener("change", (e) => {
    state.pendingFiles = Array.from(e.target.files || []);
    renderPendingFiles();
});

$("upload-btn").addEventListener("click", async () => {
    if (state.pendingFiles.length === 0) return;
    const btn = $("upload-btn");
    btn.disabled = true;
    btn.textContent = "Scanning...";
    try {
        state.report = await api.uploadPoms(state.pendingFiles);
        state.scanMetadata = await api.getScanMetadata();
        state.pendingFiles = [];
        $("pom-file-input").value = "";
        renderPendingFiles();
        renderScanMetadata();
        renderCandidates();
        // Hide any stale result panels since the report changed
        $("result-panel").classList.add("hidden");
        $("import-snippet-panel").classList.add("hidden");
        $("migration-panel").classList.add("hidden");
    } catch (err) {
        alert(err.message);
    } finally {
        btn.textContent = "Scan selected POMs";
        btn.disabled = state.pendingFiles.length === 0;
    }
});

/* ---------- scan metadata panel ---------- */

function renderScanMetadata() {
    const summary = $("sources-summary");
    const list = $("sources-list");
    const warnings = $("sources-warnings");
    list.innerHTML = "";
    warnings.innerHTML = "";

    const meta = state.scanMetadata;
    if (!meta) {
        summary.textContent = "No sources scanned yet.";
        return;
    }

    const scanned = meta.scannedSources || [];
    const skippedLang = meta.skippedByLanguage || [];
    const skippedNoPom = meta.skippedNoPom || [];
    const failed = meta.failedClones || [];

    if (scanned.length === 0 && skippedLang.length === 0 && skippedNoPom.length === 0 && failed.length === 0) {
        summary.textContent = "No sources scanned yet.";
        return;
    }

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

/* ---------- modules panel ---------- */

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
            renderCandidates();
        });
        list.appendChild(chip);
    });
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

/* ---------- candidates table ---------- */

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

function selectedVersionFormat() {
    const checked = document.querySelector('input[name="version-format"]:checked');
    return checked ? checked.value : "INLINE";
}

/* ---------- generated BOM + import snippet + migration preview ---------- */

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

function renderImportSnippet(snippet) {
    const container = $("import-snippet-container");
    container.innerHTML = "";
    container.appendChild(renderGeneratedFile("Add to your service's pom.xml", snippet));
}

function renderMigrationPreview(preview) {
    const summary = $("migration-summary");
    const output = $("migration-output");
    output.innerHTML = "";

    const services = preview.services || [];
    if (services.length === 0) {
        summary.textContent = "No services were scanned. Upload POM files to see the migration preview.";
        return;
    }

    const totalStrips = services.reduce((a, s) => a + s.stripCount, 0);
    const totalFlags = services.reduce((a, s) => a + s.flagCount, 0);
    summary.textContent = `${services.length} service${services.length === 1 ? "" : "s"} · ${totalStrips} version tag${totalStrips === 1 ? "" : "s"} would be stripped · ${totalFlags} flagged for review`;

    services.forEach(svc => {
        const details = document.createElement("details");
        details.className = "service-preview";

        const dsum = document.createElement("summary");
        dsum.innerHTML = `
            <strong>${svc.displayName}</strong>
            <span class="counts">
                <span class="count count-strip">${svc.stripCount} strip</span>
                <span class="count count-flag">${svc.flagCount} flag</span>
                <span class="count count-skip">${svc.skipCount} skip</span>
            </span>
        `;
        details.appendChild(dsum);

        if (svc.flagged && svc.flagged.length > 0) {
            const flaggedBox = document.createElement("div");
            flaggedBox.className = "flagged-list";
            const fh = document.createElement("h4");
            fh.textContent = "Flagged (manual review needed)";
            flaggedBox.appendChild(fh);
            const ul = document.createElement("ul");
            svc.flagged.forEach(f => {
                const li = document.createElement("li");
                li.innerHTML = `<code>${f.groupId}:${f.artifactId}</code> — service has <code>${f.serviceVersion}</code>, BOM has <code>${f.bomVersion || "?"}</code>`;
                ul.appendChild(li);
            });
            flaggedBox.appendChild(ul);
            details.appendChild(flaggedBox);
        }

        details.appendChild(renderGeneratedFile("Modified pom.xml", svc.modifiedContent));
        output.appendChild(details);
    });
}

/* ---------- generate button ---------- */

$("generate-btn").addEventListener("click", async () => {
    if (!state.report || state.report.candidates.length === 0) {
        alert("Nothing to generate — scan some POMs first.");
        return;
    }

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

    // Generated BOM files
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

    // Fetch and render the migration preview (uses the just-generated BOM)
    try {
        const preview = await api.getMigrationPreview();
        if (preview) {
            $("import-snippet-panel").classList.remove("hidden");
            $("migration-panel").classList.remove("hidden");
            renderImportSnippet(preview.bomImportSnippet);
            renderMigrationPreview(preview);
        }
    } catch (err) {
        console.error(err);
    }
});

/* ---------- init ---------- */

async function init() {
    try {
        state.report = await api.getReport();
        state.scanMetadata = await api.getScanMetadata();
        state.modules = await api.getModules();
        if (state.modules.length === 0) {
            state.modules = [{ name: "default" }];
        }
        renderScanMetadata();
        renderModules();
        renderCandidates();
        renderPendingFiles();
    } catch (err) {
        console.error(err);
        alert("Failed to load session state: " + err.message);
    }
}

init();
