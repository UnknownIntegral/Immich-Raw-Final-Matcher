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
                    .review-wrap { border:1px solid var(--line); border-radius:6px; padding:0 14px; } .review-header { display:flex; justify-content:space-between; gap:12px; align-items:center; padding-top:14px; } .review-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; max-width:1280px; margin:0 auto; padding:14px 0 18px; align-items:start; } .review-asset { min-width:0; } .review-preview { display:flex; align-items:center; justify-content:center; height:clamp(280px,52vh,640px); padding:6px; border:1px solid var(--line); border-radius:7px; background:#f4f6f4; } .review-thumb { display:block; width:100%; height:100%; object-fit:contain; border-radius:4px; background:#e7e9e7; } .review-missing { width:100%; height:100%; display:grid; place-items:center; color:var(--muted); background:#e7e9e7; border-radius:4px; } .review-caption { padding:9px 2px 0; } .review-caption-label { color:var(--muted); font-size:12px; font-weight:650; text-transform:uppercase; letter-spacing:.04em; } .asset { display:grid; grid-template-columns:112px 1fr; gap:10px; min-width:0; } .thumb { width:112px; height:84px; object-fit:cover; border-radius:5px; background:#e7e9e7; } .asset-name { overflow-wrap:anywhere; font-weight:600; } .asset-id { color:var(--muted); font-size:12px; overflow-wrap:anywhere; } .review-match-details { grid-column:1 / -1; max-width:960px; margin:2px auto 0; text-align:center; } .review-actions { grid-column:1 / -1; display:flex; justify-content:center; gap:8px; }
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
                      <div class="actions" style="margin-top:14px"><button id="dryRunButton" class="secondary" disabled>Write dry-run manifest</button><button id="applyTagsButton" disabled>Apply Immich tags</button><span id="message" class="status"></span></div>
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
                      <div class="tabs"><button id="reviewTab" class="active">Review queue</button><label for="reviewSort">Sort review</label><select id="reviewSort"><option value="desc">Confidence: high to low</option><option value="asc">Confidence: low to high</option></select><button id="matchesTab">All results</button><button id="tagPlanTab">RAW tags</button><button id="finalTagPlanTab">Final tags</button></div>
                      <div id="reviewView" class="review-wrap"></div>
                      <div id="matchesView" style="display:none"><div class="actions" style="margin:10px 0"><button id="matchesPrevious" class="secondary">Previous</button><span id="matchesPage" class="muted"></span><button id="matchesNext" class="secondary">Next</button></div><div id="matchesScroll" class="table-wrap"><table><thead><tr><th>Status</th><th>Score</th><th>Final</th><th>RAW</th><th>Reason</th></tr></thead><tbody id="matchesBody"></tbody></table></div></div>
                      <div id="tagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>RAW</th><th>Matched final</th><th>Score</th><th>Basis</th></tr></thead><tbody id="tagPlanBody"></tbody></table></div>
                      <div id="finalTagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>Final</th><th>Matched RAW</th><th>Score</th><th>Basis</th></tr></thead><tbody id="finalTagPlanBody"></tbody></table></div>
                    </section>
                  </main>
                  <script>
                    const REVIEW_BUFFER = 30;
                    const MATCHES_PAGE_SIZE = 250;
                    let state = { session:null, reviewRows:[], matches:[], matchesOffset:0, matchCount:0, tagPlan:null, finalTagPlan:null };
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
                    ['review','matches','tagPlan','finalTagPlan'].forEach(tab => $(tab + 'Tab').addEventListener('click', () => showTab(tab)));
                    $('reviewSort').addEventListener('change', () => refreshReview());
                    document.addEventListener('keydown', event => {
                      if (activeTab !== 'review' || busy) return;
                      if (event.key.toLowerCase() === 'z' && (event.ctrlKey || event.metaKey) && state.session?.canUndoLastReviewDecision) { event.preventDefault(); undoLastReviewDecision(); return; }
                      if (event.ctrlKey || event.metaKey || event.altKey) return;
                      if (['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName)) return;
                      const row = state.reviewRows[0];
                      if (!row) return;
                      if (event.key.toLowerCase() === 'a' && row.rawAssetId) { event.preventDefault(); updateStatus(row, 'ACCEPTED'); }
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
                      state.session = data.session; state.reviewRows = []; state.matches = []; state.matchesOffset = 0; state.matchCount = data.session.matchCount || 0; state.tagPlan = null; state.finalTagPlan = null; render();
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
                      setBusy(true); message('Writing dry-run manifest...');
                      try { const response=await apiFetch('/api/dry-run',{method:'POST'}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Dry-run failed'); state.tagPlan=data.tagPlan; state.finalTagPlan=data.finalTagPlan||[]; render(); message(data.manifest ? `Dry-run manifest: ${data.manifest}` : 'Dry-run manifest written.'); }
                      catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function applyTags() {
                      if (!confirm('Apply the reviewed Immich tag plan?')) return;
                      setBusy(true); message('Applying Immich tags...');
                      try { const response=await apiFetch('/api/immich/apply-tags',{method:'POST'}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Tag application failed'); message(`Tagged ${data.rawFoundTagged}/${data.rawFoundAssets} finals with RAW Found and ${data.keeperTagged}/${data.keeperAssets} RAWs as Keeper.`); }
                      catch(error){message(error.message);} finally{setBusy(false);}
                    }
                    async function updateStatus(row,status) {
                      if (busy) return;
                      setBusy(true);
                      try {
                        const response=await apiFetch('/api/match/status',{method:'POST',body:new URLSearchParams({index:row.index,status})}); const data=await response.json();
                        if(!response.ok) throw new Error(data.error||'Review update failed');
                        state.session=data.session;
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
                      $('dryRunButton').disabled=!state.session||busy; $('applyTagsButton').disabled=!state.session||busy;
                    }
                    function renderActiveTab() { if(activeTab==='review') renderReview(); if(activeTab==='matches') renderMatches(); if(activeTab==='tagPlan') renderTagPlan(); if(activeTab==='finalTagPlan') renderFinalTagPlan(); }
                    function renderReview() {
                      const view=$('reviewView'); const row=state.reviewRows[0];
                      const undoDisabled=!state.session?.canUndoLastReviewDecision||busy;
                      const header=`<div class="review-header"><strong>${state.session?.reviewCount||0} reviews remaining</strong><span class="muted">Keyboard: A accept, R reject</span><button class="secondary" ${undoDisabled?'disabled':''} data-action="undo">Undo last decision</button></div>`;
                      if(!row) { view.innerHTML=header+(state.session?.reviewCount ? '<p class="muted">Loading review queue...</p>' : '<p class="muted">Nothing needs review. The middle score band will appear here after a scan.</p>'); view.querySelector('[data-action="undo"]')?.addEventListener('click',undoLastReviewDecision); return; }
                      view.innerHTML=header+`<div class="review-grid"><div class="review-asset"><div class="review-preview">${reviewThumbHtml(row.finishedAssetId, 'Final image preview')}</div><div class="review-caption"><div class="review-caption-label">Final image</div><div class="asset-name">${escapeHtml(row.finishedPath)}</div><div class="asset-id">Final asset: ${escapeHtml(row.finishedAssetId)}</div></div></div><div class="review-asset"><div class="review-preview">${row.rawAssetId?reviewThumbHtml(row.rawAssetId, 'RAW image preview'):'<div class="review-missing">No RAW candidate</div>'}</div><div class="review-caption"><div class="review-caption-label">RAW candidate</div><div class="asset-name">${escapeHtml(row.rawPath||'No RAW candidate')}</div><div class="asset-id">${row.rawAssetId?'RAW asset: '+escapeHtml(row.rawAssetId):''}</div></div></div><div class="review-match-details"><div class="${scoreClass(row.score)}">${row.score}% match</div><div class="muted">${escapeHtml(row.reason)}</div></div><div class="review-actions"><button ${row.rawAssetId&&!busy?'':'disabled'} data-action="accept">Accept match</button><button class="secondary" ${busy?'disabled':''} data-action="reject">No matching RAW</button></div></div>`;
                      view.querySelector('[data-action="undo"]')?.addEventListener('click',undoLastReviewDecision);
                      view.querySelector('[data-action="accept"]')?.addEventListener('click',()=>updateStatus(row,'ACCEPTED'));
                      view.querySelector('[data-action="reject"]')?.addEventListener('click',()=>updateStatus(row,'REJECTED'));
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
                    function thumbHtml(assetId){return `<img class="thumb" data-asset-id="${escapeHtml(assetId||'')}" alt="Asset thumbnail">`;}
                    function reviewThumbHtml(assetId,alt){return `<img class="review-thumb" data-asset-id="${escapeHtml(assetId||'')}" alt="${escapeHtml(alt)}">`;}
                    function observeThumbnails(scrollRoot,content){thumbnailObservers.get(scrollRoot)?.disconnect();const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;observer.unobserve(entry.target);loadThumbnail(entry.target);}}, {root:scrollRoot,threshold:0});thumbnailObservers.set(scrollRoot,observer);for(const image of content.querySelectorAll('img[data-asset-id]')){const id=image.dataset.assetId;if(thumbs.has(id)){image.src=thumbs.get(id);continue;}observer.observe(image);}}
                    async function loadThumbnail(image){const id=image.dataset.assetId;if(!id)return;let request=thumbnailRequests.get(id);if(!request){request=apiFetch('/api/immich/thumbnail?assetId='+encodeURIComponent(id)).then(response=>{if(!response.ok)throw new Error();return response.blob();}).then(blob=>{const url=URL.createObjectURL(blob);thumbs.set(id,url);return url;}).finally(()=>thumbnailRequests.delete(id));thumbnailRequests.set(id,request);}try{const url=await request;document.querySelectorAll(`img[data-asset-id="${CSS.escape(id)}"]`).forEach(img=>img.src=url);}catch{image.alt='Preview unavailable';}}
                    function showTab(tab){activeTab=tab;['review','matches','tagPlan','finalTagPlan'].forEach(name=>{$(name+'Tab').classList.toggle('active',name===tab);$(name+'View').style.display=name===tab?'':'none';});renderActiveTab();if(tab==='review'&&!state.reviewRows.length)void refreshReview();if(tab==='matches'&&!state.matches.length)void loadMatches();if((tab==='tagPlan'||tab==='finalTagPlan')&&state.tagPlan===null)void loadTagPlan();}
                    function showProgress(text,percent){$('progressShell').hidden=false;$('progressText').textContent=text||'Working...';const bar=$('progress');bar.classList.toggle('indeterminate',percent==null||percent<0);bar.firstElementChild.style.width=percent>=0?percent+'%':'';}
                    function hideProgress(){$('progressShell').hidden=true;}
                    function setBusy(value){busy=value;$('scanButton').disabled=value;$('dryRunButton').disabled=value||!state.session;$('applyTagsButton').disabled=value||!state.session;if(activeTab==='review')renderReview();}
                    async function apiFetch(url,options={},retry=true){const headers=new Headers(options.headers||{});const token=localStorage.getItem('pcaAccessToken');if(token)headers.set('X-PCA-Token',token);const response=await fetch(url,{...options,headers});if(response.status===401&&retry){const entered=prompt('Access token');if(entered!==null){localStorage.setItem('pcaAccessToken',entered);return apiFetch(url,options,false);}}return response;}
                    async function restoreOnLoad(){try{const statusResponse=await apiFetch('/api/status');const status=await statusResponse.json();if(!statusResponse.ok)throw new Error(status.error||'Could not restore app state');if(status.hasSession){await loadSession();await refreshReview();}const job=status.scanJob;if(job?.state==='RUNNING'){setBusy(true);showProgress(job.message,job.percent);await pollScan();}else if(job?.state==='INTERRUPTED'){message(job.error||job.message);}}catch(error){message(error.message);}}
                    render(); restoreOnLoad();
                  </script>
                </body>
                </html>
                """;
    }
}
