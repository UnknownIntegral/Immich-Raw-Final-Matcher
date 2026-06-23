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
                    :root { color-scheme:light; --bg:#f6f7f4; --panel:#fff; --ink:#202124; --muted:#646a73; --line:#d8dadd; --accent:#28635a; --accent-strong:#194840; --warn:#936400; --bad:#9a2f2f; }
                    * { box-sizing:border-box; } body { margin:0; font-family:"Segoe UI",sans-serif; background:var(--bg); color:var(--ink); }
                    header,main { max-width:1440px; margin:auto; padding:20px; } header { padding-bottom:6px; } h1,h2,h3,p { margin-top:0; } h1 { margin-bottom:4px; }
                    section { background:var(--panel); border:1px solid var(--line); border-radius:9px; padding:18px; margin-bottom:16px; }
                    .grid { display:grid; grid-template-columns:max-content minmax(100px,1fr) max-content minmax(100px,1fr) max-content; gap:10px; align-items:center; }
                    input,select { min-width:0; padding:8px; border:1px solid var(--line); border-radius:5px; } button { padding:8px 12px; border:0; border-radius:5px; background:var(--accent); color:#fff; cursor:pointer; } button.secondary { background:#e8efec; color:var(--accent-strong); } button:disabled { opacity:.55; cursor:default; }
                    .actions,.tabs { display:flex; gap:8px; flex-wrap:wrap; align-items:center; } .tabs button { background:#fff; color:var(--accent-strong); border:1px solid var(--line); } .tabs button.active { background:var(--accent); color:#fff; border-color:var(--accent); }
                    .progress-shell { display:flex; align-items:center; gap:12px; margin-top:14px; } .progress { flex:1; height:11px; border-radius:8px; overflow:hidden; background:#e7e9e7; } .progress > div { height:100%; width:0; background:var(--accent); transition:width .25s; } .progress.indeterminate > div { width:35%; animation:move 1.2s infinite ease-in-out; } @keyframes move { from { transform:translateX(-120%); } to { transform:translateX(320%); } }
                    .summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:10px; } .metric { padding:11px; border:1px solid var(--line); border-radius:6px; background:#fbfcf9; } .metric div:first-child { color:var(--muted); font-size:12px; } .metric div:last-child { font-size:21px; font-weight:650; margin-top:3px; }
                    .note,.muted { color:var(--muted); } .note { font-size:12px; margin-top:10px; } .status { min-height:20px; color:var(--muted); }
                    .table-wrap { overflow:auto; max-height:58vh; border:1px solid var(--line); border-radius:6px; } table { width:100%; border-collapse:collapse; font-size:13px; } th,td { padding:8px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; } th { position:sticky; top:0; background:#f0f2ef; z-index:1; } .path { max-width:300px; overflow-wrap:anywhere; } .score-high { color:#17603d; font-weight:650; } .score-mid { color:var(--warn); font-weight:650; } .score-low { color:var(--bad); font-weight:650; }
                    .review-wrap { border:1px solid var(--line); border-radius:6px; padding:0 14px; } .review-header { display:flex; justify-content:space-between; gap:12px; align-items:center; padding-top:14px; } .review-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; max-width:1280px; margin:0 auto; padding:14px 0 18px; align-items:start; } .review-asset { min-width:0; } .review-preview { display:flex; align-items:center; justify-content:center; height:clamp(280px,52vh,640px); padding:6px; border:1px solid var(--line); border-radius:7px; background:#f4f6f4; } .review-thumb { display:block; width:100%; height:100%; object-fit:contain; border-radius:4px; background:#e7e9e7; } .review-missing { width:100%; height:100%; display:grid; place-items:center; color:var(--muted); background:#e7e9e7; border-radius:4px; } .review-caption { padding:9px 2px 0; } .review-caption-label { color:var(--muted); font-size:12px; font-weight:650; text-transform:uppercase; letter-spacing:.04em; } .asset { display:grid; grid-template-columns:112px 1fr; gap:10px; min-width:0; } .thumb { width:112px; height:84px; object-fit:cover; border-radius:5px; background:#e7e9e7; } .asset-name { overflow-wrap:anywhere; font-weight:600; } .asset-id { color:var(--muted); font-size:12px; overflow-wrap:anywhere; } .comparison { grid-column:1 / -1; width:100%; max-width:1000px; margin:4px auto 2px; border:1px solid var(--line); border-radius:7px; overflow:hidden; } .comparison table { font-size:13px; } .comparison th { position:static; background:#f0f2ef; } .comparison th:first-child { width:24%; } .comparison td { width:38%; overflow-wrap:anywhere; font-weight:600; } .comparison td.match { color:#17603d; background:#e7f4eb; } .comparison td.close { color:#7a5500; background:#fff3c4; } .comparison td.mismatch { color:#922d23; background:#fbe9e7; } .comparison td.missing { color:var(--muted); font-weight:400; } .review-match-details { grid-column:1 / -1; max-width:960px; margin:2px auto 0; text-align:center; } .review-actions { grid-column:1 / -1; display:flex; justify-content:center; gap:8px; } .candidate-list { grid-column:1 / -1; display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:8px; } .candidate { border:1px solid var(--line); border-radius:6px; padding:8px; display:grid; grid-template-columns:72px 1fr; gap:8px; background:#fbfcf9; } .candidate.selected { border-color:var(--accent); box-shadow:inset 0 0 0 1px var(--accent); } .candidate .thumb { width:72px; height:58px; } .candidate button { grid-column:1 / -1; justify-self:start; } .history-controls { display:flex; gap:8px; flex-wrap:wrap; margin:10px 0; } .history-controls input { min-width:260px; }
                    @media (max-width:850px) { .grid { grid-template-columns:1fr; } .review-header { align-items:flex-start; flex-direction:column; } } @media (max-width:650px) { .review-grid { grid-template-columns:1fr; } .review-preview { height:clamp(250px,55vh,520px); } }
                  </style>
                </head>
                <body>
                  <header><h1>Photo Culling Assistant</h1><div class="muted">Immich-only matching, review, and safe tag planning.</div></header>
                  <main>
                    <section>
                      <h2>Scan Immich</h2>
                      <form id="scanForm" class="grid">
                        <label for="autoAccept">Auto-accept %</label><input id="autoAccept" type="number" min="1" max="100" value="90">
                        <label for="autoReject">Auto-reject %</label><input id="autoReject" type="number" min="0" max="99" value="50">
                        <button id="scanButton" type="submit">Scan Immich</button>
                      </form>
                      <div class="progress-shell" id="progressShell" hidden><div id="progress" class="progress indeterminate"><div></div></div><span id="progressText" class="status">Starting...</span></div>
                      <div class="actions" style="margin-top:14px"><button id="dryRunButton" class="secondary" disabled>Approve dry-run plan</button><button id="applyTagsButton" disabled>Apply approved plan</button><span id="planStatus" class="status"></span><span id="message" class="status"></span></div>
                    </section>
                    <section>
                      <h2>Scan summary</h2>
                      <div class="summary">
                        <div class="metric"><div>RAW files detected</div><div id="rawCount">0</div></div>
                        <div class="metric"><div>Final files detected</div><div id="finalCount">0</div></div>
                        <div class="metric"><div>Finals with matching RAW</div><div id="rawFoundCount">0</div></div>
                        <div class="metric"><div>Pairs needing review</div><div id="reviewCount">0</div></div>
                        <div class="metric"><div>Unused RAWs</div><div id="unusedCount">0</div></div>
                        <div class="metric"><div>Duplicate final photos</div><div id="duplicateCount">0</div></div>
                        <div class="metric"><div>Duplicate RAW photos</div><div id="duplicateRawCount">0</div></div>
                      </div>
                      <div class="note">Duplicate counts use exact Immich checksums. Possible duplicates use matching filenames only: finals <span id="possibleFinalCount">0</span>, RAWs <span id="possibleRawCount">0</span>.</div>
                    </section>
                    <section>
                      <div class="tabs"><button id="reviewTab" class="active">Review queue</button><label for="reviewSort">Sort review</label><select id="reviewSort"><option value="desc">Confidence: high to low</option><option value="asc">Confidence: low to high</option></select><button id="matchesTab">All results</button><button id="tagPlanTab">RAW tags</button><button id="finalTagPlanTab">Final tags</button><button id="historyTab">Decision history</button></div>
                      <div id="reviewView" class="review-wrap"></div>
                      <div id="matchesView" style="display:none"><div class="actions" style="margin:10px 0"><button id="matchesPrevious" class="secondary">Previous</button><span id="matchesPage" class="muted"></span><button id="matchesNext" class="secondary">Next</button></div><div id="matchesScroll" class="table-wrap"><table><thead><tr><th>Status</th><th>Score</th><th>Final</th><th>RAW</th><th>Reason</th></tr></thead><tbody id="matchesBody"></tbody></table></div></div>
                      <div id="tagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>RAW</th><th>Matched final</th><th>Score</th><th>Basis</th></tr></thead><tbody id="tagPlanBody"></tbody></table></div>
                      <div id="finalTagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>Final</th><th>Matched RAW</th><th>Score</th><th>Basis</th></tr></thead><tbody id="finalTagPlanBody"></tbody></table></div>
                      <div id="historyView" style="display:none"><div class="history-controls"><input id="historyAssetId" placeholder="Filter by Immich asset ID"><button id="historyRefresh" class="secondary">Refresh history</button></div><div class="table-wrap"><table><thead><tr><th>Time</th><th>Event</th><th>Final</th><th>RAW</th><th>Status</th><th>Details</th></tr></thead><tbody id="historyBody"></tbody></table></div></div>
                    </section>
                  </main>
                  <script>
                    const REVIEW_BUFFER = 30;
                    const MATCHES_PAGE_SIZE = 250;
                    let state = { session:null, reviewRows:[], matches:[], matchesOffset:0, matchCount:0, tagPlan:null, finalTagPlan:null, history:[], selectedRawByMatch:new Map() };
                    let activeTab = 'review';
                    let busy = false;
                    let reviewRequest = 0;
                    const thumbs = new Map();
                    const thumbnailRequests = new Map();
                    const thumbnailObservers = new Map();
                    const $ = id => document.getElementById(id);
                    const message = text => $('message').textContent = text || '';
                    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c]));
                    const scoreClass = score => score >= 90 ? 'score-high' : score >= 50 ? 'score-mid' : 'score-low';
                    $('scanForm').addEventListener('submit', startScan);
                    $('dryRunButton').addEventListener('click', writeDryRun);
                    $('applyTagsButton').addEventListener('click', applyTags);
                    $('matchesPrevious').addEventListener('click', () => loadMatches(Math.max(0, state.matchesOffset - MATCHES_PAGE_SIZE)));
                    $('matchesNext').addEventListener('click', () => loadMatches(state.matchesOffset + MATCHES_PAGE_SIZE));
                    ['review','matches','tagPlan','finalTagPlan','history'].forEach(tab => $(tab + 'Tab').addEventListener('click', () => showTab(tab)));
                    $('historyRefresh').addEventListener('click', loadHistory);
                    $('historyAssetId').addEventListener('keydown', event => { if(event.key === 'Enter') loadHistory(); });
                    $('reviewSort').addEventListener('change', () => refreshReview());
                    document.addEventListener('keydown', event => {
                      if (activeTab !== 'review' || busy) return;
                      if (event.key.toLowerCase() === 'z' && (event.ctrlKey || event.metaKey) && state.session?.canUndoLastReviewDecision) { event.preventDefault(); undoLastReviewDecision(); return; }
                      if (event.ctrlKey || event.metaKey || event.altKey) return;
                      if (['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName)) return;
                      const row = state.reviewRows[0];
                      if (!row) return;
                      const candidateNumber=Number(event.key);
                      const candidate=(row.candidates||[])[candidateNumber-1];
                      if(candidate && candidateNumber >= 1 && candidateNumber <= 5){event.preventDefault();state.selectedRawByMatch.set(row.index,candidate.rawAssetId);renderReview();return;}
                      const selectedRawAssetId=state.selectedRawByMatch.get(row.index)||row.rawAssetId||(row.candidates||[])[0]?.rawAssetId;
                      if (event.key.toLowerCase() === 'a' && selectedRawAssetId) { event.preventDefault(); updateStatus(row, 'ACCEPTED',selectedRawAssetId); }
                      if (event.key.toLowerCase() === 'r') { event.preventDefault(); updateStatus(row, 'REJECTED'); }
                    });

                    async function startScan(event) {
                      event.preventDefault(); const accept = Number($('autoAccept').value); const reject = Number($('autoReject').value);
                      if (!(reject < accept)) { message('Auto-reject must be lower than auto-accept.'); return; }
                      setBusy(true); showProgress('Starting Immich scan...', -1); message('');
                      try { const response = await apiFetch('/api/immich/scan', {method:'POST', body:new URLSearchParams({autoAccept:accept, autoReject:reject})}); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Scan failed'); await pollScan(); }
                      catch (error) { message(error.message); hideProgress(); setBusy(false); }
                    }
                    async function pollScan() {
                      while (true) {
                        await new Promise(resolve => setTimeout(resolve, 900));
                        const response = await apiFetch('/api/immich/scan/status'); const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Could not read scan status');
                        const job = data.job; showProgress(job.message, job.percent);
                        if (job.state === 'FAILED') throw new Error(job.error || 'Scan failed');
                        if (job.state === 'COMPLETE') { await loadSession(); await refreshReview(); hideProgress(); setBusy(false); message('Immich scan complete.'); return; }
                      }
                    }
                    async function loadSession() {
                      const response = await apiFetch('/api/session'); const data = await response.json();
                      if (!response.ok) throw new Error(data.error || 'Could not load scan results');
                      state.session = data.session; state.reviewRows = []; state.matches = []; state.matchesOffset = 0; state.matchCount = data.session.matchCount || 0; state.tagPlan = null; state.finalTagPlan = null; state.selectedRawByMatch = new Map(); render();
                    }
                    async function refreshReview(append = false) {
                      if (!state.session) return;
                      const request = ++reviewRequest;
                      if (!append) { state.reviewRows = []; if (activeTab === 'review') renderReview(); }
                      const limit = append ? REVIEW_BUFFER + state.reviewRows.length : REVIEW_BUFFER;
                      try {
                        const response = await apiFetch('/api/review?sort=' + encodeURIComponent($('reviewSort').value) + '&limit=' + limit);
                        const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Could not load review queue');
                        if (request !== reviewRequest) return;
                        state.session = data.session;
                        const rows = new Map((append ? state.reviewRows : []).map(row => [row.index, row]));
                        for (const row of data.matches || []) rows.set(row.index, row);
                        state.reviewRows = [...rows.values()].sort((a,b) => $('reviewSort').value === 'asc' ? a.score - b.score : b.score - a.score);
                        render();
                      } catch (error) { if (request === reviewRequest) message(error.message); }
                    }
                    async function writeDryRun() {
                      setBusy(true); message('Freezing dry-run plan...');
                      try { const response=await apiFetch('/api/dry-run',{method:'POST'}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Dry-run failed'); state.tagPlan=data.tagPlan; state.finalTagPlan=data.finalTagPlan||[]; if(state.session) state.session.activePlan=data.plan||null; render(); message(data.plan ? `Approved plan ${data.plan.id}.` : 'Dry-run plan approved.'); }
                      catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function applyTags() {
                      const plan=state.session?.activePlan;
                      if (!plan) { message('Approve a dry-run plan before applying tags.'); return; }
                      if (plan.operation?.state === 'COMPLETE') { message('This approved plan has already completed.'); return; }
                      if (!confirm(`Apply immutable plan ${plan.id}? This reconciles only the configured decision tags.`)) return;
                      setBusy(true); message('Applying Immich tags...');
                      try { const response=await apiFetch('/api/immich/apply-tags',{method:'POST',body:new URLSearchParams({planId:plan.id})}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Tag application failed'); if(state.session?.activePlan) state.session.activePlan.operation=data.operation; render(); message(`Plan applied: ${data.rawFoundTagged}/${data.rawFoundAssets} RAW Found finals and ${data.keeperTagged}/${data.keeperAssets} Keeper RAWs.`); }
                      catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function updateStatus(row,status,selectedRawAssetId) {
                      if (busy) return;
                      const rawAssetId=selectedRawAssetId || state.selectedRawByMatch.get(row.index) || row.rawAssetId;
                      setBusy(true);
                      try {
                        const form={index:row.index,status}; if(status==='ACCEPTED'&&rawAssetId) form.rawAssetId=rawAssetId;
                        const response=await apiFetch('/api/match/status',{method:'POST',body:new URLSearchParams(form)}); const data=await response.json();
                        if(!response.ok) throw new Error(data.error||'Review update failed');
                        state.session=data.session;
                        state.selectedRawByMatch.delete(row.index);
                        state.reviewRows=state.reviewRows.filter(candidate => candidate.index !== data.match.index);
                        const matchIndex=state.matches.findIndex(candidate => candidate.index === data.match.index);
                        if(matchIndex >= 0) state.matches[matchIndex]=data.match;
                        render(); message('Review status saved.');
                        if(state.reviewRows.length < REVIEW_BUFFER / 2) void refreshReview(true);
                      } catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function undoLastReviewDecision() {
                      if (busy || !state.session?.canUndoLastReviewDecision) return;
                      setBusy(true);
                      try {
                        const response=await apiFetch('/api/match/undo',{method:'POST'}); const data=await response.json();
                        if(!response.ok) throw new Error(data.error||'Undo failed');
                        state.session=data.session;
                        const matchIndex=state.matches.findIndex(candidate => candidate.index === data.match.index);
                        if(matchIndex >= 0) state.matches[matchIndex]=data.match;
                        await refreshReview(true); message('Last review decision undone.');
                      } catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function loadMatches(offset = 0) {
                      if (!state.session || offset < 0 || (state.matchCount && offset >= state.matchCount)) return;
                      try {
                        const response = await apiFetch('/api/matches?offset=' + offset + '&limit=' + MATCHES_PAGE_SIZE); const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Could not load results');
                        state.matches = data.matches || []; state.matchesOffset = data.offset || 0; state.matchCount = data.matchCount || 0; renderMatches();
                      } catch (error) { message(error.message); }
                    }
                    async function loadTagPlan() {
                      try {
                        const response = await apiFetch('/api/tag-plan'); const data = await response.json();
                        if (!response.ok) throw new Error(data.error || 'Could not load tag plan');
                        state.tagPlan = data.tagPlan || []; state.finalTagPlan = data.finalTagPlan || []; renderActiveTab();
                      } catch (error) { message(error.message); }
                    }
                    function render() { renderSummary(); renderActiveTab(); }
                    function renderSummary() {
                      const s=state.session||{};
                      ['rawCount','finalCount','rawFoundCount','reviewCount','unusedCount','duplicateCount','duplicateRawCount'].forEach(id => $(id).textContent=s[id]||0);
                      $('possibleFinalCount').textContent=s.possibleDuplicateFinalCount||0; $('possibleRawCount').textContent=s.possibleDuplicateRawCount||0;
                      $('autoAccept').value=s.autoAcceptThreshold||$('autoAccept').value; $('autoReject').value=s.autoRejectThreshold??$('autoReject').value;
                      const plan=s.activePlan; const completed=plan?.operation?.state==='COMPLETE';
                      $('dryRunButton').disabled=!state.session||busy; $('applyTagsButton').disabled=!plan||completed||busy;
                      $('planStatus').textContent=plan ? `Approved plan ${plan.id.slice(0,8)} (${plan.operation?.state||'READY'})` : (state.session ? 'Approve a dry-run plan before applying tags.' : '');
                    }
                    function renderActiveTab() { if(activeTab==='review') renderReview(); if(activeTab==='matches') renderMatches(); if(activeTab==='tagPlan') renderTagPlan(); if(activeTab==='finalTagPlan') renderFinalTagPlan(); if(activeTab==='history') renderHistory(); }
                    function renderReview() {
                      const view=$('reviewView'); const row=state.reviewRows[0];
                      const undoDisabled=!state.session?.canUndoLastReviewDecision||busy;
                      const header=`<div class="review-header"><strong>${state.session?.reviewCount||0} reviews remaining</strong><span class="muted">Keyboard: 1-5 choose RAW, A accept, R reject</span><button class="secondary" ${undoDisabled?'disabled':''} data-action="undo">Undo last decision</button></div>`;
                      if(!row) { view.innerHTML=header+(state.session?.reviewCount ? '<p class="muted">Loading review queue...</p>' : '<p class="muted">Nothing needs review. The middle score band will appear here after a scan.</p>'); view.querySelector('[data-action="undo"]')?.addEventListener('click',undoLastReviewDecision); return; }
                      const candidates=(row.candidates&&row.candidates.length?row.candidates:(row.rawAssetId?[{rawAssetId:row.rawAssetId,rawPath:row.rawPath,score:row.score,reason:row.reason}]:[]));
                      const selectedRawAssetId=state.selectedRawByMatch.get(row.index)||row.rawAssetId||candidates[0]?.rawAssetId||null;
                      const selected=candidates.find(candidate=>candidate.rawAssetId===selectedRawAssetId)||null;
                      const candidateCards=candidates.map((candidate,index)=>`<div class="candidate ${candidate.rawAssetId===selectedRawAssetId?'selected':''}">${thumbHtml(candidate.rawAssetId)}<div><strong>${index+1}. ${escapeHtml(candidate.rawPath)}</strong><div class="${scoreClass(candidate.score)}">${candidate.score}% match</div></div><button class="secondary" data-action="select" data-raw-asset-id="${escapeHtml(candidate.rawAssetId)}" ${busy?'disabled':''}>${candidate.rawAssetId===selectedRawAssetId?'Selected':'Use this RAW'}</button></div>`).join('');
                      view.innerHTML=header+`<div class="review-grid"><div class="review-asset"><div class="review-preview">${reviewThumbHtml(row.finishedAssetId, 'Final image preview')}</div><div class="review-caption"><div class="review-caption-label">Final image</div><div class="asset-name">${escapeHtml(row.finishedPath)}</div><div class="asset-id">Final asset: ${escapeHtml(row.finishedAssetId)}</div></div></div><div class="review-asset"><div class="review-preview">${selected?reviewThumbHtml(selected.rawAssetId, 'RAW image preview'):'<div class="review-missing">No RAW candidate</div>'}</div><div class="review-caption"><div class="review-caption-label">Selected RAW candidate</div><div class="asset-name">${escapeHtml(selected?.rawPath||'No RAW candidate')}</div><div class="asset-id">${selected?'RAW asset: '+escapeHtml(selected.rawAssetId):''}</div></div></div>${comparisonTable(row.finishedMetadata, selected?.rawMetadata||row.rawMetadata)}<div class="review-match-details"><div class="${scoreClass(selected?.score??row.score)}">${selected?.score??row.score}% match</div></div>${candidateCards?`<div class="candidate-list">${candidateCards}</div>`:''}<div class="review-actions"><button ${selected&&!busy?'':'disabled'} data-action="accept">Accept selected RAW</button><button class="secondary" ${busy?'disabled':''} data-action="reject">No matching RAW</button></div></div>`;
                      view.querySelector('[data-action="undo"]')?.addEventListener('click',undoLastReviewDecision);
                      view.querySelector('[data-action="accept"]')?.addEventListener('click',()=>updateStatus(row,'ACCEPTED',selectedRawAssetId));
                      view.querySelector('[data-action="reject"]')?.addEventListener('click',()=>updateStatus(row,'REJECTED'));
                      view.querySelectorAll('[data-action="select"]').forEach(button=>button.addEventListener('click',()=>{state.selectedRawByMatch.set(row.index,button.dataset.rawAssetId);renderReview();}));
                      observeThumbnails(view,view);
                    }
                    function renderMatches() {
                      const body=$('matchesBody'); body.innerHTML='';
                      for(const row of state.matches){const tr=document.createElement('tr');tr.innerHTML=`<td>${escapeHtml(row.statusLabel)}</td><td class="${scoreClass(row.score)}">${row.score}%</td><td><div class="asset">${thumbHtml(row.finishedAssetId)}<div class="path">${escapeHtml(row.finishedPath)}</div></div></td><td><div class="asset">${row.rawAssetId?thumbHtml(row.rawAssetId):''}<div class="path">${escapeHtml(row.rawPath||'')}</div></div></td><td>${escapeHtml(row.reason)}</td>`;body.appendChild(tr);}
                      const start=state.matchCount ? state.matchesOffset+1 : 0; const end=state.matchesOffset+state.matches.length;
                      $('matchesPage').textContent=state.matchCount ? `${start}-${end} of ${state.matchCount}` : 'No results loaded'; $('matchesPrevious').disabled=state.matchesOffset===0; $('matchesNext').disabled=end>=state.matchCount;
                      observeThumbnails($('matchesScroll'),body);
                    }
                    function renderTagPlan(){const body=$('tagPlanBody');if(state.tagPlan===null){body.innerHTML='<tr><td colspan="5" class="muted">Loading tag plan...</td></tr>';return;}body.innerHTML='';for(const row of state.tagPlan){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${escapeHtml(row.tag)}</strong></td><td class="path">${escapeHtml(row.rawPath)}</td><td class="path">${escapeHtml(row.matchedFinalPath||'')}</td><td>${row.score||0}%</td><td>${escapeHtml(row.basis)}</td>`;body.appendChild(tr);}}
                    function renderFinalTagPlan(){const body=$('finalTagPlanBody');if(state.finalTagPlan===null){body.innerHTML='<tr><td colspan="5" class="muted">Loading tag plan...</td></tr>';return;}body.innerHTML='';for(const row of state.finalTagPlan){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${escapeHtml(row.tag)}</strong></td><td class="path">${escapeHtml(row.finalPath)}</td><td class="path">${escapeHtml(row.matchedRawPath||'')}</td><td>${row.score||0}%</td><td>${escapeHtml(row.basis)}</td>`;body.appendChild(tr);}}
                    async function loadHistory(){try{const assetId=$('historyAssetId').value.trim();const url='/api/history?limit=250'+(assetId?'&assetId='+encodeURIComponent(assetId):'');const response=await apiFetch(url);const data=await response.json();if(!response.ok)throw new Error(data.error||'Could not load decision history');state.history=data.events||[];renderHistory();}catch(error){message(error.message);}}
                    function renderHistory(){const body=$('historyBody');body.innerHTML='';if(!state.history.length){body.innerHTML='<tr><td colspan="6" class="muted">No recorded decisions match this filter.</td></tr>';return;}for(const event of state.history){const prior=event.previousRawPath?` Previous suggestion: ${event.previousRawPath}.`:'';const tr=document.createElement('tr');tr.innerHTML=`<td>${escapeHtml(event.occurredAt||'')}</td><td><strong>${escapeHtml(event.eventType||'')}</strong><div class="muted">${escapeHtml(event.source||'')}</div></td><td class="path">${escapeHtml(event.finalPath||event.finalAssetId||'')}</td><td class="path">${escapeHtml(event.rawPath||event.rawAssetId||'')}</td><td>${escapeHtml(event.status||event.operationState||'')}</td><td>${escapeHtml((event.detail||'')+prior)}</td>`;body.appendChild(tr);}}
                    function thumbHtml(assetId){return `<img class="thumb" data-asset-id="${escapeHtml(assetId||'')}" alt="Asset thumbnail">`;}
                    function comparisonTable(finalMetadata,rawMetadata){const rows=[['Filename','filename'],['File type','fileType'],['Capture timestamp','captureTimestamp'],['Camera','cameraType'],['File size','fileSizeBytes'],['Modified timestamp','modifiedTimestamp']];return `<div class="comparison"><table><thead><tr><th>Image detail</th><th>Final image</th><th>Selected RAW</th></tr></thead><tbody>${rows.map(([label,key])=>{const finalValue=displayMetadata(finalMetadata?.[key],key);const rawValue=displayMetadata(rawMetadata?.[key],key);const state=comparisonState(finalMetadata?.[key],rawMetadata?.[key],key);return `<tr><th>${label}</th><td class="${state}">${escapeHtml(finalValue)}</td><td class="${state}">${escapeHtml(rawValue)}</td></tr>`;}).join('')}</tbody></table></div>`;}
                    function comparisonState(finalValue,rawValue,key){if(finalValue==null||finalValue===''||rawValue==null||rawValue==='')return 'missing';if(String(finalValue)===String(rawValue))return 'match';if(key==='captureTimestamp'||key==='modifiedTimestamp'){const finalTime=Date.parse(finalValue);const rawTime=Date.parse(rawValue);const closeWindow=key==='captureTimestamp'?5*60*1000:60*60*1000;if(Number.isFinite(finalTime)&&Number.isFinite(rawTime)&&Math.abs(finalTime-rawTime)<=closeWindow)return 'close';}return 'mismatch';}
                    function displayMetadata(value,key){if(value==null||value==='')return '—';if(key==='captureTimestamp'||key==='modifiedTimestamp'){const date=new Date(value);return Number.isNaN(date.valueOf())?String(value):date.toLocaleString(undefined,{dateStyle:'medium',timeStyle:'medium'});}if(key==='fileSizeBytes'){const bytes=Number(value);if(!Number.isFinite(bytes))return String(value);const units=['B','KB','MB','GB','TB'];let index=0;let size=bytes;while(size>=1024&&index<units.length-1){size/=1024;index++;}return `${size.toLocaleString(undefined,{maximumFractionDigits:index?1:0})} ${units[index]}`;}return String(value);}
                    function reviewThumbHtml(assetId,alt){return `<img class="review-thumb" data-asset-id="${escapeHtml(assetId||'')}" alt="${escapeHtml(alt)}">`;}
                    function observeThumbnails(scrollRoot,content){thumbnailObservers.get(scrollRoot)?.disconnect();const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;observer.unobserve(entry.target);loadThumbnail(entry.target);}}, {root:scrollRoot,threshold:0});thumbnailObservers.set(scrollRoot,observer);for(const image of content.querySelectorAll('img[data-asset-id]')){const id=image.dataset.assetId;if(thumbs.has(id)){image.src=thumbs.get(id);continue;}observer.observe(image);}}
                    async function loadThumbnail(image){const id=image.dataset.assetId;if(!id)return;let request=thumbnailRequests.get(id);if(!request){request=apiFetch('/api/immich/thumbnail?assetId='+encodeURIComponent(id)).then(response=>{if(!response.ok)throw new Error();return response.blob();}).then(blob=>{const url=URL.createObjectURL(blob);thumbs.set(id,url);return url;}).finally(()=>thumbnailRequests.delete(id));thumbnailRequests.set(id,request);}try{const url=await request;document.querySelectorAll(`img[data-asset-id="${CSS.escape(id)}"]`).forEach(img=>img.src=url);}catch{image.alt='Preview unavailable';}}
                    function showTab(tab){activeTab=tab;['review','matches','tagPlan','finalTagPlan','history'].forEach(name=>{$(name+'Tab').classList.toggle('active',name===tab);$(name+'View').style.display=name===tab?'':'none';});renderActiveTab();if(tab==='review'&&!state.reviewRows.length)void refreshReview();if(tab==='matches'&&!state.matches.length)void loadMatches();if((tab==='tagPlan'||tab==='finalTagPlan')&&state.tagPlan===null)void loadTagPlan();if(tab==='history')void loadHistory();}
                    function showProgress(text,percent){$('progressShell').hidden=false;$('progressText').textContent=text||'Working...';const bar=$('progress');bar.classList.toggle('indeterminate',percent==null||percent<0);bar.firstElementChild.style.width=percent>=0?percent+'%':'';}
                    function hideProgress(){$('progressShell').hidden=true;}
                    function setBusy(value){busy=value;$('scanButton').disabled=value;$('dryRunButton').disabled=value||!state.session;$('applyTagsButton').disabled=value||!state.session?.activePlan||state.session?.activePlan?.operation?.state==='COMPLETE';if(activeTab==='review')renderReview();}
                    async function apiFetch(url,options={},retry=true){const headers=new Headers(options.headers||{});const token=localStorage.getItem('pcaAccessToken');if(token)headers.set('X-PCA-Token',token);const response=await fetch(url,{...options,headers});if(response.status===401&&retry){const entered=prompt('Access token');if(entered!==null){localStorage.setItem('pcaAccessToken',entered);return apiFetch(url,options,false);}}return response;}
                    async function restoreOnLoad(){try{const statusResponse=await apiFetch('/api/status');const status=await statusResponse.json();if(!statusResponse.ok)throw new Error(status.error||'Could not restore app state');if(status.hasSession){await loadSession();await refreshReview();}const job=status.scanJob;if(job?.state==='RUNNING'){setBusy(true);showProgress(job.message,job.percent);await pollScan();}else if(job?.state==='INTERRUPTED'){message(job.error||job.message);}}catch(error){message(error.message);}}
                    render(); restoreOnLoad();
                  </script>
                </body>
                </html>
                """;
    }
}
