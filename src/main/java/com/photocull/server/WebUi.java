package com.photocull.server;

public final class WebUi {
    private WebUi() {
    }

    public static String html() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Photo Culling Assistant</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f7f7f4;
                      --panel: #ffffff;
                      --ink: #202124;
                      --muted: #646a73;
                      --line: #d8dadd;
                      --accent: #28635a;
                      --accent-strong: #194840;
                      --warn: #936400;
                      --bad: #9a2f2f;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: "Segoe UI", system-ui, sans-serif;
                      background: var(--bg);
                      color: var(--ink);
                    }
                    header {
                      padding: 18px 24px 12px;
                      border-bottom: 1px solid var(--line);
                      background: #ffffff;
                    }
                    h1 {
                      margin: 0;
                      font-size: 22px;
                      font-weight: 650;
                    }
                    main {
                      padding: 18px 24px 28px;
                      display: grid;
                      gap: 16px;
                    }
                    section {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 6px;
                      padding: 14px;
                    }
                    h2 {
                      margin: 0 0 12px;
                      font-size: 15px;
                    }
                    .grid {
                      display: grid;
                      grid-template-columns: 150px minmax(260px, 1fr) 120px 130px;
                      gap: 10px;
                      align-items: center;
                    }
                    label {
                      color: var(--muted);
                      font-size: 13px;
                    }
                    input {
                      width: 100%;
                      min-height: 34px;
                      border: 1px solid var(--line);
                      border-radius: 4px;
                      padding: 6px 8px;
                      font: inherit;
                    }
                    button {
                      min-height: 34px;
                      border: 1px solid var(--accent);
                      background: var(--accent);
                      color: white;
                      border-radius: 4px;
                      padding: 6px 12px;
                      font: inherit;
                      cursor: pointer;
                    }
                    button.secondary {
                      background: #ffffff;
                      color: var(--accent-strong);
                    }
                    button:disabled {
                      opacity: 0.55;
                      cursor: default;
                    }
                    .actions {
                      display: flex;
                      gap: 8px;
                      flex-wrap: wrap;
                      align-items: center;
                    }
                    .summary {
                      display: grid;
                      grid-template-columns: repeat(8, minmax(100px, 1fr));
                      gap: 8px;
                    }
                    .metric {
                      border: 1px solid var(--line);
                      border-radius: 6px;
                      padding: 10px;
                      background: #fbfbf9;
                    }
                    .metric div:first-child {
                      color: var(--muted);
                      font-size: 12px;
                    }
                    .metric div:last-child {
                      font-size: 20px;
                      font-weight: 650;
                      margin-top: 3px;
                    }
                    .tabs {
                      display: flex;
                      gap: 8px;
                      margin-bottom: 10px;
                    }
                    .tabs button {
                      min-height: 30px;
                      background: #ffffff;
                      color: var(--accent-strong);
                    }
                    .tabs button.active {
                      background: var(--accent);
                      color: white;
                    }
                    .table-wrap {
                      overflow: auto;
                      border: 1px solid var(--line);
                      border-radius: 6px;
                      max-height: 58vh;
                    }
                    table {
                      width: 100%;
                      border-collapse: collapse;
                      font-size: 13px;
                    }
                    th, td {
                      padding: 8px;
                      border-bottom: 1px solid var(--line);
                      text-align: left;
                      vertical-align: top;
                    }
                    th {
                      position: sticky;
                      top: 0;
                      background: #f0f2ef;
                      z-index: 1;
                    }
                    tr.selected {
                      background: #e8f1ee;
                    }
                    .score-high { color: #17603d; font-weight: 650; }
                    .score-mid { color: var(--warn); font-weight: 650; }
                    .score-low { color: var(--bad); font-weight: 650; }
                    .path {
                      max-width: 420px;
                      word-break: break-all;
                    }
                    .muted {
                      color: var(--muted);
                    }
                    .status {
                      min-height: 20px;
                      color: var(--muted);
                    }
                    @media (max-width: 900px) {
                      .grid, .summary { grid-template-columns: 1fr; }
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Photo Culling Assistant</h1>
                    <div class="muted">Local web UI on port 8356. Dry-run first; tag finals and RAWs in Immich.</div>
                  </header>
                  <main>
                    <section>
                      <h2>Scan</h2>
                      <form id="scanForm" class="grid">
                        <label for="rawRoot">RAW root</label>
                        <input id="rawRoot" name="rawRoot" placeholder="/raws or C:\\Photos\\RAW">
                        <label for="threshold">Auto-accept %</label>
                        <input id="threshold" name="threshold" type="number" min="0" max="100" value="90">
                        <label for="finalRoot">Final images</label>
                        <input id="finalRoot" name="finalRoot" placeholder="/finals or C:\\Photos\\Edited">
                        <div></div>
                        <button id="scanButton" type="submit">Scan</button>
                      </form>
                      <div class="actions" style="margin-top: 12px;">
                        <button id="acceptButton" class="secondary" disabled>Accept selected</button>
                        <button id="rejectButton" class="secondary" disabled>Reject selected</button>
                        <button id="dryRunButton" disabled>Write dry-run manifest</button>
                        <button id="immichScanButton" class="secondary">Scan Immich</button>
                        <button id="applyTagsButton" disabled>Apply all Immich tags</button>
                        <span id="message" class="status"></span>
                      </div>
                    </section>
                    <section>
                      <h2>Summary</h2>
                      <div class="summary">
                        <div class="metric"><div>RAWs</div><div id="rawCount">0</div></div>
                        <div class="metric"><div>Finals</div><div id="finalCount">0</div></div>
                        <div class="metric"><div>Matches</div><div id="matchCount">0</div></div>
                        <div class="metric"><div>Review</div><div id="reviewCount">0</div></div>
                        <div class="metric"><div>Keeper RAWs</div><div id="keeperCount">0</div></div>
                        <div class="metric"><div>not used RAWs</div><div id="unusedCount">0</div></div>
                        <div class="metric"><div>RAW Found finals</div><div id="rawFoundCount">0</div></div>
                        <div class="metric"><div>No RAW finals</div><div id="noRawCount">0</div></div>
                      </div>
                    </section>
                    <section>
                      <div class="tabs">
                        <button id="matchesTab" class="active">Matches</button>
                        <button id="tagPlanTab">RAW Tags</button>
                        <button id="finalTagPlanTab">Final Tags</button>
                      </div>
                      <div id="matchesView" class="table-wrap">
                        <table>
                          <thead><tr><th>Status</th><th>Score</th><th>Final image</th><th>RAW match</th><th>Reason</th></tr></thead>
                          <tbody id="matchesBody"></tbody>
                        </table>
                      </div>
                      <div id="tagPlanView" class="table-wrap" style="display:none;">
                        <table>
                          <thead><tr><th>Tag</th><th>RAW asset</th><th>RAW</th><th>Matched final asset</th><th>Matched final</th><th>Score</th><th>Basis</th></tr></thead>
                          <tbody id="tagPlanBody"></tbody>
                        </table>
                      </div>
                      <div id="finalTagPlanView" class="table-wrap" style="display:none;">
                        <table>
                          <thead><tr><th>Tag</th><th>Final asset</th><th>Final image</th><th>Matched RAW asset</th><th>Matched RAW</th><th>Score</th><th>Basis</th></tr></thead>
                          <tbody id="finalTagPlanBody"></tbody>
                        </table>
                      </div>
                    </section>
                  </main>
                  <script>
                    let selectedIndex = null;
                    let state = { matches: [], tagPlan: [], finalTagPlan: [], session: null };

                    const $ = (id) => document.getElementById(id);
                    const message = (text) => $('message').textContent = text || '';
                    const scoreClass = (score) => score >= 90 ? 'score-high' : score >= 70 ? 'score-mid' : 'score-low';

                    $('scanForm').addEventListener('submit', async (event) => {
                      event.preventDefault();
                      selectedIndex = null;
                      setBusy(true);
                      message('Scanning. Large libraries can take a while...');
                      try {
                        const body = new URLSearchParams(new FormData(event.target));
                        const response = await apiFetch('/api/scan', { method: 'POST', body });
                        const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Scan failed');
                        state = data;
                        render();
                        message('Scan complete.');
                      } catch (error) {
                        message(error.message);
                      } finally {
                        setBusy(false);
                      }
                    });

                    $('acceptButton').addEventListener('click', () => updateSelected('ACCEPTED'));
                    $('rejectButton').addEventListener('click', () => updateSelected('REJECTED'));
                    $('dryRunButton').addEventListener('click', async () => {
                      setBusy(true);
                      message('Writing dry-run manifest...');
                      try {
                        const response = await apiFetch('/api/dry-run', { method: 'POST' });
                        const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Dry-run failed');
                        state.tagPlan = data.tagPlan;
                        state.finalTagPlan = data.finalTagPlan || [];
                        renderTagPlan();
                        renderFinalTagPlan();
                        message(data.manifest ? `Dry-run manifest: ${data.manifest}` : 'Dry-run manifest written.');
                      } catch (error) {
                        message(error.message);
                      } finally {
                        setBusy(false);
                      }
                    });
                    $('immichScanButton').addEventListener('click', async () => {
                      selectedIndex = null;
                      setBusy(true);
                      message('Scanning Immich assets. This can take a while...');
                      try {
                        const body = new URLSearchParams({ threshold: $('threshold').value || '90' });
                        const response = await apiFetch('/api/immich/scan', { method: 'POST', body });
                        const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Immich scan failed');
                        state = data;
                        render();
                        message('Immich scan complete. Review matches before applying tags.');
                      } catch (error) {
                        message(error.message);
                      } finally {
                        setBusy(false);
                      }
                    });
                    $('applyTagsButton').addEventListener('click', async () => {
                      if (!confirm('Apply RAW Found / No RAW tags to final images and Keeper / not used tags to RAW assets in Immich?')) return;
                      setBusy(true);
                      message('Applying Immich tags...');
                      try {
                        const response = await apiFetch('/api/immich/apply-tags', { method: 'POST' });
                        const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Apply tags failed');
                        message(`Tagged finals: ${data.rawFoundTagged}/${data.rawFoundAssets} RAW Found, ${data.noRawTagged}/${data.noRawAssets} No RAW. RAWs: ${data.keeperTagged}/${data.keeperAssets} Keeper, ${data.unusedTagged}/${data.unusedAssets} not used. Manifest: ${data.manifest}`);
                      } catch (error) {
                        message(error.message);
                      } finally {
                        setBusy(false);
                      }
                    });

                    $('matchesTab').addEventListener('click', () => showTab('matches'));
                    $('tagPlanTab').addEventListener('click', () => showTab('tagPlan'));
                    $('finalTagPlanTab').addEventListener('click', () => showTab('finalTagPlan'));

                    async function updateSelected(status) {
                      if (selectedIndex === null) return;
                      setBusy(true);
                      try {
                        const body = new URLSearchParams({ index: selectedIndex, status });
                        const response = await apiFetch('/api/match/status', { method: 'POST', body });
                        const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Update failed');
                        state = data;
                        selectedIndex = null;
                        render();
                        message('Review status updated.');
                      } catch (error) {
                        message(error.message);
                      } finally {
                        setBusy(false);
                      }
                    }

                    function render() {
                      const s = state.session || {};
                      $('rawCount').textContent = s.rawCount || 0;
                      $('finalCount').textContent = s.finalCount || 0;
                      $('matchCount').textContent = s.matchCount || 0;
                      $('reviewCount').textContent = s.reviewCount || 0;
                      $('keeperCount').textContent = s.keeperCount || 0;
                      $('unusedCount').textContent = s.unusedCount || 0;
                      $('rawFoundCount').textContent = s.rawFoundCount || 0;
                      $('noRawCount').textContent = s.noRawCount || 0;
                      $('dryRunButton').disabled = !state.session;
                      $('applyTagsButton').disabled = !state.session || (!(state.tagPlan || []).length && !(state.finalTagPlan || []).length);
                      renderMatches();
                      renderTagPlan();
                      renderFinalTagPlan();
                    }

                    function renderMatches() {
                      const body = $('matchesBody');
                      body.innerHTML = '';
                      for (const row of state.matches || []) {
                        const tr = document.createElement('tr');
                        tr.dataset.index = row.index;
                        if (row.index === selectedIndex) tr.classList.add('selected');
                        tr.innerHTML = `
                          <td>${escapeHtml(row.statusLabel)}</td>
                          <td class="${scoreClass(row.score)}">${row.score}</td>
                          <td class="path">${escapeHtml(row.finishedPath)}</td>
                          <td class="path">${escapeHtml(row.rawPath || '')}</td>
                          <td>${escapeHtml(row.reason || '')}</td>
                        `;
                        tr.addEventListener('click', () => {
                          selectedIndex = row.index;
                          $('acceptButton').disabled = !row.rawPath;
                          $('rejectButton').disabled = false;
                          renderMatches();
                        });
                        body.appendChild(tr);
                      }
                    }

                    function renderTagPlan() {
                      const body = $('tagPlanBody');
                      body.innerHTML = '';
                      for (const row of state.tagPlan || []) {
                        const tr = document.createElement('tr');
                        tr.innerHTML = `
                          <td><strong>${escapeHtml(row.tag)}</strong></td>
                          <td class="path">${escapeHtml(row.rawAssetId || '')}</td>
                          <td class="path">${escapeHtml(row.rawPath)}</td>
                          <td class="path">${escapeHtml(row.matchedFinalAssetId || '')}</td>
                          <td class="path">${escapeHtml(row.matchedFinalPath || '')}</td>
                          <td>${row.score || 0}</td>
                          <td>${escapeHtml(row.basis || '')}</td>
                        `;
                        body.appendChild(tr);
                      }
                    }

                    function renderFinalTagPlan() {
                      const body = $('finalTagPlanBody');
                      body.innerHTML = '';
                      for (const row of state.finalTagPlan || []) {
                        const tr = document.createElement('tr');
                        tr.innerHTML = `
                          <td><strong>${escapeHtml(row.tag)}</strong></td>
                          <td class="path">${escapeHtml(row.finalAssetId || '')}</td>
                          <td class="path">${escapeHtml(row.finalPath)}</td>
                          <td class="path">${escapeHtml(row.matchedRawAssetId || '')}</td>
                          <td class="path">${escapeHtml(row.matchedRawPath || '')}</td>
                          <td>${row.score || 0}</td>
                          <td>${escapeHtml(row.basis || '')}</td>
                        `;
                        body.appendChild(tr);
                      }
                    }

                    function showTab(tab) {
                      $('matchesTab').classList.toggle('active', tab === 'matches');
                      $('tagPlanTab').classList.toggle('active', tab === 'tagPlan');
                      $('finalTagPlanTab').classList.toggle('active', tab === 'finalTagPlan');
                      $('matchesView').style.display = tab === 'matches' ? '' : 'none';
                      $('tagPlanView').style.display = tab === 'tagPlan' ? '' : 'none';
                      $('finalTagPlanView').style.display = tab === 'finalTagPlan' ? '' : 'none';
                    }

                    function setBusy(busy) {
                      $('scanButton').disabled = busy;
                      $('immichScanButton').disabled = busy;
                      $('dryRunButton').disabled = busy || !state.session;
                      $('applyTagsButton').disabled = busy || !state.session || (!(state.tagPlan || []).length && !(state.finalTagPlan || []).length);
                      $('acceptButton').disabled = busy || selectedIndex === null;
                      $('rejectButton').disabled = busy || selectedIndex === null;
                    }

                    async function apiFetch(url, options = {}, retry = true) {
                      const headers = new Headers(options.headers || {});
                      const token = localStorage.getItem('pcaAccessToken');
                      if (token) headers.set('X-PCA-Token', token);
                      const response = await fetch(url, { ...options, headers });
                      if (response.status === 401 && retry) {
                        const entered = prompt('Access token');
                        if (entered !== null) {
                          localStorage.setItem('pcaAccessToken', entered);
                          return apiFetch(url, options, false);
                        }
                      }
                      return response;
                    }

                    function escapeHtml(value) {
                      return String(value ?? '').replace(/[&<>"']/g, (c) => ({
                        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
                      }[c]));
                    }

                    render();
                  </script>
                </body>
                </html>
                """;
    }
}
