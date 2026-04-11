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
    async uploadPoms(entries) {
        // entries: [{ file, path }, ...]  — `path` is the display/relative path
        // (webkitRelativePath for folder-picked files, file.name for individual-picked files)
        const form = new FormData();
        for (const e of entries) {
            form.append("files", e.file, e.file.name);
            form.append("paths", e.path);
        }
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
        if (res.status === 412) return { status: "not-generated" };
        if (res.status === 409) return { status: "stale" };
        if (!res.ok) throw new Error("Migration preview failed: " + res.status);
        const body = await res.json();
        return { status: "ok", preview: body };
    },
    async getCoordinates() {
        const res = await fetch("/api/bom/coordinates");
        return res.json();
    },
    async setCoordinates(coords) {
        const res = await fetch("/api/bom/coordinates", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(coords)
        });
        return res.json();
    }
};

const state = {
    report: null,
    scanMetadata: null,
    modules: [],
    selections: new Map(), // key -> { included, version, moduleName }
    // Map of displayPath -> { file, path }. Displayed in the pending list,
    // uploaded as (files[], paths[]) multipart pairs. Keyed by displayPath
    // so picking the same file (or same folder) twice deduplicates.
    pendingFiles: new Map(),
    // True once the user has successfully generated a BOM. Used to decide
    // whether to show the "stale" banner when they tweak settings afterwards.
    generatedOnce: false
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

// Build the display/upload path for a File. Folder-picked files get their
// webkitRelativePath (e.g. "my-project/service-a/pom.xml"). Individually
// picked files only expose .name ("pom.xml"), so we append an index suffix
// to disambiguate duplicates in the pending list.
function buildDisplayPath(file, fallbackIndex) {
    const rel = file.webkitRelativePath;
    if (rel && rel.length > 0) return rel;
    return file.name || ("pom-" + fallbackIndex + ".xml");
}

function addPendingFiles(files, { fromFolder }) {
    let addedCount = 0;
    let skippedNonPom = 0;
    let i = 0;
    for (const file of files) {
        i++;
        // A folder picker can return anything — filter to pom.xml only
        // (case-insensitive, also matches "POM.xml" from weird file systems).
        if (fromFolder) {
            const base = (file.name || "").toLowerCase();
            if (base !== "pom.xml") {
                skippedNonPom++;
                continue;
            }
        }

        let path = buildDisplayPath(file, state.pendingFiles.size + i);
        // De-dup within the current pending set. If the same relative path is
        // already pending, skip it silently. For the individual-file picker we
        // append " (2)", " (3)" suffixes to distinguish duplicates.
        if (state.pendingFiles.has(path)) {
            if (fromFolder) continue;
            let suffix = 2;
            let candidate = path + " (" + suffix + ")";
            while (state.pendingFiles.has(candidate)) {
                suffix++;
                candidate = path + " (" + suffix + ")";
            }
            path = candidate;
        }
        state.pendingFiles.set(path, { file, path });
        addedCount++;
    }
    return { addedCount, skippedNonPom };
}

function renderPendingFiles() {
    const list = $("selected-files-list");
    list.innerHTML = "";
    const entries = Array.from(state.pendingFiles.values());
    entries.forEach(({ path }) => {
        const li = document.createElement("li");
        const label = document.createElement("span");
        label.textContent = path;
        li.appendChild(label);
        const rm = document.createElement("button");
        rm.type = "button";
        rm.className = "file-remove";
        rm.textContent = "×";
        rm.title = "Remove";
        rm.addEventListener("click", () => {
            state.pendingFiles.delete(path);
            renderPendingFiles();
        });
        li.appendChild(rm);
        list.appendChild(li);
    });
    const count = entries.length;
    $("file-count").textContent = count === 0
        ? "No files selected"
        : `${count} file${count === 1 ? "" : "s"} selected`;
    $("upload-btn").disabled = count === 0;
    $("clear-files-btn").disabled = count === 0;
}

$("pom-file-input").addEventListener("change", (e) => {
    addPendingFiles(Array.from(e.target.files || []), { fromFolder: false });
    // Reset the input so the user can re-pick the same file again later if they remove it.
    e.target.value = "";
    renderPendingFiles();
});

$("pom-folder-input").addEventListener("change", (e) => {
    const picked = Array.from(e.target.files || []);
    const { addedCount, skippedNonPom } = addPendingFiles(picked, { fromFolder: true });
    e.target.value = "";
    renderPendingFiles();
    if (addedCount === 0 && skippedNonPom > 0) {
        alert("No pom.xml files found in that folder.");
    }
});

$("clear-files-btn").addEventListener("click", () => {
    state.pendingFiles.clear();
    renderPendingFiles();
});

$("upload-btn").addEventListener("click", async () => {
    if (state.pendingFiles.size === 0) return;
    const btn = $("upload-btn");
    btn.disabled = true;
    btn.textContent = "Scanning...";
    try {
        const entries = Array.from(state.pendingFiles.values());
        state.report = await api.uploadPoms(entries);
        state.scanMetadata = await api.getScanMetadata();
        state.pendingFiles.clear();
        renderPendingFiles();
        renderScanMetadata();
        renderCandidates();
        // After a fresh scan, re-fetch coordinates so any suggested groupId
        // from the newly-scanned POMs pre-fills the form.
        await loadCoordinates();
        // Hide any stale result panels since the report changed; the backend
        // also clears its lastGeneratedSignature on upload.
        state.generatedOnce = false;
        hideResultPanels();
        hideStaleBanner();
    } catch (err) {
        alert(err.message);
    } finally {
        btn.textContent = "Scan selected POMs";
        btn.disabled = state.pendingFiles.size === 0;
    }
});

function hideResultPanels() {
    $("result-panel").classList.add("hidden");
    $("import-snippet-panel").classList.add("hidden");
    $("migration-panel").classList.add("hidden");
}

// Called whenever the user changes settings (coordinates, modules, assignments)
// after a previous generation. Hides the stale result panels and shows a banner
// telling them to click Generate BOM again.
function markPreviewStale() {
    if (!state.generatedOnce) return;
    hideResultPanels();
    $("stale-banner").classList.remove("hidden");
}

function hideStaleBanner() {
    $("stale-banner").classList.add("hidden");
}

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
            markPreviewStale();
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
    markPreviewStale();
});

$("save-modules-btn").addEventListener("click", async () => {
    await api.setModules(state.modules);
    alert("Modules saved.");
    markPreviewStale();
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
            markPreviewStale();
        });
        tr.querySelector("input[type=text]").addEventListener("change", e => {
            sel.version = e.target.value;
            markPreviewStale();
        });
        const sel_el = tr.querySelector("select");
        if (sel_el) {
            sel_el.addEventListener("change", e => {
                sel.moduleName = e.target.value;
                markPreviewStale();
            });
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

function escapeHtml(str) {
    return str
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
}

function renderDiff(diffLines) {
    // Collapse long runs of UNCHANGED lines with a "… N unchanged lines …" marker.
    // Keeps 3 lines of context around each change.
    const CONTEXT = 3;
    const rendered = [];
    let i = 0;
    while (i < diffLines.length) {
        const line = diffLines[i];
        if (line.status === "UNCHANGED") {
            // Look ahead: how many unchanged lines in a row?
            let j = i;
            while (j < diffLines.length && diffLines[j].status === "UNCHANGED") j++;
            const run = j - i;
            const isStart = i === 0;
            const isEnd = j === diffLines.length;

            if (run > CONTEXT * 2 + 1 && !isStart && !isEnd) {
                // Emit CONTEXT, then ellipsis, then CONTEXT
                for (let k = 0; k < CONTEXT; k++) rendered.push(diffLines[i + k]);
                rendered.push({ status: "COLLAPSED", hiddenCount: run - CONTEXT * 2 });
                for (let k = 0; k < CONTEXT; k++) rendered.push(diffLines[j - CONTEXT + k]);
            } else if (run > CONTEXT && isStart) {
                rendered.push({ status: "COLLAPSED", hiddenCount: run - CONTEXT });
                for (let k = 0; k < CONTEXT; k++) rendered.push(diffLines[j - CONTEXT + k]);
            } else if (run > CONTEXT && isEnd) {
                for (let k = 0; k < CONTEXT; k++) rendered.push(diffLines[i + k]);
                rendered.push({ status: "COLLAPSED", hiddenCount: run - CONTEXT });
            } else {
                for (let k = 0; k < run; k++) rendered.push(diffLines[i + k]);
            }
            i = j;
        } else {
            rendered.push(line);
            i++;
        }
    }

    const html = rendered.map(l => {
        if (l.status === "COLLAPSED") {
            return `<tr class="diff-collapsed"><td colspan="3">… ${l.hiddenCount} unchanged line${l.hiddenCount === 1 ? "" : "s"} …</td></tr>`;
        }
        const cls = l.status === "ADDED" ? "diff-added"
            : l.status === "REMOVED" ? "diff-removed"
                : "diff-unchanged";
        const marker = l.status === "ADDED" ? "+" : l.status === "REMOVED" ? "-" : " ";
        const oldN = l.oldNumber > 0 ? l.oldNumber : "";
        const newN = l.newNumber > 0 ? l.newNumber : "";
        return `<tr class="${cls}">
            <td class="diff-line-num">${oldN}</td>
            <td class="diff-line-num">${newN}</td>
            <td class="diff-line"><span class="diff-marker">${marker}</span>${escapeHtml(l.content)}</td>
        </tr>`;
    }).join("");

    const table = document.createElement("table");
    table.className = "diff-table";
    table.innerHTML = html;
    return table;
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

        // Tab bar: diff (default) | full modified POM
        const viewWrapper = document.createElement("div");
        viewWrapper.className = "preview-view";

        const tabBar = document.createElement("div");
        tabBar.className = "preview-tabs";

        const diffTab = document.createElement("button");
        diffTab.type = "button";
        diffTab.className = "preview-tab active";
        diffTab.textContent = "Diff";

        const fullTab = document.createElement("button");
        fullTab.type = "button";
        fullTab.className = "preview-tab";
        fullTab.textContent = "Full pom.xml";

        const copyBtn = document.createElement("button");
        copyBtn.type = "button";
        copyBtn.className = "copy-btn preview-copy";
        copyBtn.textContent = "Copy full";
        copyBtn.addEventListener("click", () => copyToClipboard(svc.modifiedContent, copyBtn));

        tabBar.appendChild(diffTab);
        tabBar.appendChild(fullTab);
        tabBar.appendChild(copyBtn);
        viewWrapper.appendChild(tabBar);

        const diffView = document.createElement("div");
        diffView.className = "preview-content";
        diffView.appendChild(renderDiff(svc.diffLines || []));

        const fullView = document.createElement("div");
        fullView.className = "preview-content hidden";
        const pre = document.createElement("pre");
        pre.textContent = svc.modifiedContent;
        fullView.appendChild(pre);

        viewWrapper.appendChild(diffView);
        viewWrapper.appendChild(fullView);

        diffTab.addEventListener("click", () => {
            diffTab.classList.add("active");
            fullTab.classList.remove("active");
            diffView.classList.remove("hidden");
            fullView.classList.add("hidden");
        });
        fullTab.addEventListener("click", () => {
            fullTab.classList.add("active");
            diffTab.classList.remove("active");
            fullView.classList.remove("hidden");
            diffView.classList.add("hidden");
        });

        details.appendChild(viewWrapper);
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

    // Ensure coordinates are persisted before generation (user may have typed in the form without clicking Save)
    await api.setCoordinates({
        groupId: $("coord-group-id").value.trim() || "com.example",
        artifactId: $("coord-artifact-id").value.trim() || "my-bom",
        version: $("coord-version").value.trim() || "1.0.0"
    });

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

    // Fetch and render the migration preview (uses the just-generated BOM).
    // We just generated so the signature matches; hide the stale banner.
    hideStaleBanner();
    state.generatedOnce = true;
    try {
        const result = await api.getMigrationPreview();
        if (result && result.status === "ok") {
            const preview = result.preview;
            $("import-snippet-panel").classList.remove("hidden");
            $("migration-panel").classList.remove("hidden");
            renderImportSnippet(preview.bomImportSnippet);
            renderMigrationPreview(preview);
        }
    } catch (err) {
        console.error(err);
    }
});

/* ---------- coordinates form ---------- */

async function loadCoordinates() {
    const coords = await api.getCoordinates();
    $("coord-group-id").value = coords.groupId || "";
    $("coord-artifact-id").value = coords.artifactId || "";
    $("coord-version").value = coords.version || "";
}

$("save-coordinates-btn").addEventListener("click", async () => {
    const btn = $("save-coordinates-btn");
    btn.disabled = true;
    try {
        await api.setCoordinates({
            groupId: $("coord-group-id").value.trim(),
            artifactId: $("coord-artifact-id").value.trim(),
            version: $("coord-version").value.trim()
        });
        btn.textContent = "Saved ✓";
        setTimeout(() => { btn.textContent = "Save coordinates"; btn.disabled = false; }, 1200);
        markPreviewStale();
    } catch (err) {
        btn.disabled = false;
        alert("Failed to save: " + err.message);
    }
});

// Changing the version format invalidates the generated BOM too — the BOM's
// own properties and layout depend on this choice.
document.querySelectorAll('input[name="version-format"]').forEach(input => {
    input.addEventListener("change", markPreviewStale);
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
        await loadCoordinates();
    } catch (err) {
        console.error(err);
        alert("Failed to load session state: " + err.message);
    }
}

init();
