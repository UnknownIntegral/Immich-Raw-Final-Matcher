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
                    :root { color-scheme: light; --bg:#f6f7f4; --panel:#fff; --ink:#202124; --muted:#646a73; --line:#d8dadd; --accent:#28635a; --accent-strong:#194840; --warn:#936400; --bad:#9a2f2f; }
                    * { box-sizing:border-box; } body { margin:0; font-family:"Segoe UI",sans-serif; background:var(--bg); color:var(--ink); }
                    header, main { max-width:1440px; margin:auto; padding:20px; } header { padding-bottom:6px; } h1,h2,h3,p { margin-top:0; } h1 { margin-bottom:4px; }
                    section { background:var(--panel); border:1px solid var(--line); border-radius:9px; padding:18px; margin-bottom:16px; }
                    .grid { display:grid; grid-template-columns:max-content minmax(100px,1fr) max-content minmax(100px,1fr) max-content; gap:10px; align-items:center; }
                    input,select { min-width:0; padding:8px; border:1px solid var(--line); border-radius:5px; } button { padding:8px 12px; border:0; border-radius:5px; background:var(--accent); color:#fff; cursor:pointer; } button.secondary { background:#e8efec; color:var(--accent-strong); } button:disabled { opacity:.55; cursor:default; }
                    .actions,.tabs { display:flex; gap:8px; flex-wrap:wrap; align-items:center; } .tabs button { background:#fff; color:var(--accent-strong); border:1px solid var(--line); } .tabs button.active { background:var(--accent); color:#fff; border-color:var(--accent); }
                    .progress-shell { display:flex; align-items:center; gap:12px; margin-top:14px; } .progress { flex:1; height:11px; border-radius:8px; overflow:hidden; background:#e7e9e7; } .progress > div { height:100%; width:0; background:var(--accent); transition:width .25s; } .progress.indeterminate > div { width:35%; animation:move 1.2s infinite ease-in-out; } @keyframes move { from { transform:translateX(-120%); } to { transform:translateX(320%); } }
                    .summary { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:10px; } .metric { padding:11px; border:1px solid var(--line); border-radius:6px; background:#fbfcf9; } .metric div:first-child { color:var(--muted); font-size:12px; } .metric div:last-child { font-size:21px; font-weight:650; margin-top:3px; }
                    .note,.muted { color:var(--muted); } .note { font-size:12px; margin-top:10px; } .status { min-height:20px; color:var(--muted); }
                    .table-wrap { overflow:auto; max-height:58vh; border:1px solid var(--line); border-radius:6px; } table { width:100%; border-collapse:collapse; font-size:13px; } th,td { padding:8px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; } th { position:sticky; top:0; background:#f0f2ef; z-index:1; } .path { max-width:300px; overflow-wrap:anywhere; } .score-high { color:#17603d; font-weight:650; } .score-mid { color:var(--warn); font-weight:650; } .score-low { color:var(--bad); font-weight:650; }
                    .review-wrap { max-height:58vh; overflow-y:auto; border:1px solid var(--line); border-radius:6px; padding:0 14px; } .review-grid { display:grid; grid-template-columns:1fr 1fr auto; gap:14px; padding:14px 0; border-bottom:1px solid var(--line); align-items:start; } .asset { display:grid; grid-template-columns:112px 1fr; gap:10px; min-width:0; } .thumb { width:112px; height:84px; object-fit:cover; border-radius:5px; background:#e7e9e7; } .asset-name { overflow-wrap:anywhere; font-weight:600; } .asset-id { color:var(--muted); font-size:12px; overflow-wrap:anywhere; } .review-actions { display:flex; flex-direction:column; gap:8px; min-width:118px; }
                    @media (max-width:850px) { .grid { grid-template-columns:1fr; } .review-grid { grid-template-columns:1fr; } .review-actions { flex-direction:row; } }
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
                      <div class="progress-shell" id="progressShell" hidden><div id="progress" class="progress indeterminate"><div></div></div><span id="progressText" class="status">Starting…</span></div>
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
                      <div id="matchesView" class="table-wrap" style="display:none"><table><thead><tr><th>Status</th><th>Score</th><th>Final</th><th>RAW</th><th>Reason</th></tr></thead><tbody id="matchesBody"></tbody></table></div>
                      <div id="tagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>RAW</th><th>Matched final</th><th>Score</th><th>Basis</th></tr></thead><tbody id="tagPlanBody"></tbody></table></div>
                      <div id="finalTagPlanView" class="table-wrap" style="display:none"><table><thead><tr><th>Tag</th><th>Final</th><th>Matched RAW</th><th>Score</th><th>Basis</th></tr></thead><tbody id="finalTagPlanBody"></tbody></table></div>
                    </section>
                  </main>
                  <script>
                    let state = { matches: [], tagPlan: [], finalTagPlan: [], session: null };
                    let busy = false; const thumbs = new Map(); const thumbnailRequests = new Map(); const thumbnailObservers = new Map(); const $ = id => document.getElementById(id);
                    const message = text => $('message').textContent = text || '';
                    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c]));
                    const scoreClass = score => score >= 90 ? 'score-high' : score >= 50 ? 'score-mid' : 'score-low';
                    $('scanForm').addEventListener('submit', startScan);
                    $('dryRunButton').addEventListener('click', writeDryRun);
                    $('applyTagsButton').addEventListener('click', applyTags);
                    ['review','matches','tagPlan','finalTagPlan'].forEach(tab => $(tab + 'Tab').addEventListener('click', () => showTab(tab)));
                    $('reviewSort').addEventListener('change', () => { $('reviewView').scrollTop=0; renderReview(); });

                    async function startScan(event) {
                      event.preventDefault(); const accept = Number($('autoAccept').value); const reject = Number($('autoReject').value);
                      if (!(reject < accept)) { message('Auto-reject must be lower than auto-accept.'); return; }
                      setBusy(true); showProgress('Starting Immich scan…', -1); message('');
                      try { const response = await apiFetch('/api/immich/scan', {method:'POST', body:new URLSearchParams({autoAccept:accept, autoReject:reject})}); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Scan failed'); await pollScan(); }
                      catch (error) { message(error.message); hideProgress(); setBusy(false); }
                    }
                    async function pollScan() {
                      while (true) { await new Promise(resolve => setTimeout(resolve, 900)); const response = await apiFetch('/api/immich/scan/status'); const data = await response.json(); if (!response.ok) throw new Error(data.error || 'Could not read scan status'); const job = data.job; showProgress(job.message, job.percent); if (job.state === 'FAILED') throw new Error(job.error || 'Scan failed'); if (job.state === 'COMPLETE') { const sessionResponse = await apiFetch('/api/session'); state = await sessionResponse.json(); if (!sessionResponse.ok) throw new Error(state.error || 'Could not load scan results'); render(); hideProgress(); setBusy(false); message('Immich scan complete.'); return; } }
                    }
                    async function writeDryRun() { setBusy(true); message('Writing dry-run manifest…'); try { const response=await apiFetch('/api/dry-run',{method:'POST'}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Dry-run failed'); state.tagPlan=data.tagPlan; state.finalTagPlan=data.finalTagPlan||[]; render(); message(data.manifest ? `Dry-run manifest: ${data.manifest}` : 'Dry-run manifest written.'); } catch(error){message(error.message);} finally{setBusy(false);} }
                    async function applyTags() { if (!confirm('Apply the reviewed Immich tag plan?')) return; setBusy(true); message('Applying Immich tags…'); try { const response=await apiFetch('/api/immich/apply-tags',{method:'POST'}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Tag application failed'); message(`Tagged ${data.rawFoundTagged}/${data.rawFoundAssets} finals with RAW Found and ${data.keeperTagged}/${data.keeperAssets} RAWs as Keeper.`); } catch(error){message(error.message);} finally{setBusy(false);} }
                    async function updateStatus(index,status) { setBusy(true); try { const response=await apiFetch('/api/match/status',{method:'POST',body:new URLSearchParams({index,status})}); const data=await response.json(); if(!response.ok) throw new Error(data.error||'Review update failed'); state=data; render(); message('Review status updated.'); } catch(error){message(error.message);} finally{setBusy(false);} }
                    function render() { const s=state.session||{}; ['rawCount','finalCount','rawFoundCount','reviewCount','unusedCount','duplicateCount','duplicateRawCount'].forEach(id => $(id).textContent=s[id]||0); $('possibleFinalCount').textContent=s.possibleDuplicateFinalCount||0; $('possibleRawCount').textContent=s.possibleDuplicateRawCount||0; $('autoAccept').value=s.autoAcceptThreshold||$('autoAccept').value; $('autoReject').value=s.autoRejectThreshold??$('autoReject').value; $('dryRunButton').disabled=!state.session||busy; $('applyTagsButton').disabled=!state.session||busy; renderReview(); renderMatches(); renderTagPlan(); renderFinalTagPlan(); }
                    function renderReview() { const view=$('reviewView'); const direction=$('reviewSort').value==='asc'?1:-1; const rows=(state.matches||[]).filter(row=>row.status==='NEEDS_REVIEW').sort((a,b)=>direction*(a.score-b.score)); if(!rows.length){view.innerHTML='<p class="muted">Nothing needs review. The middle score band will appear here after a scan.</p>';return;} view.innerHTML=''; for(const row of rows){ const item=document.createElement('div'); item.className='review-grid'; item.innerHTML=`<div class="asset">${thumbHtml(row.finishedAssetId)}<div><div class="asset-name">${escapeHtml(row.finishedPath)}</div><div class="asset-id">Final asset: ${escapeHtml(row.finishedAssetId)}</div></div></div><div class="asset">${row.rawAssetId?thumbHtml(row.rawAssetId):'<div class="thumb"></div>'}<div><div class="asset-name">${escapeHtml(row.rawPath||'No RAW candidate')}</div><div class="asset-id">${row.rawAssetId?'RAW asset: '+escapeHtml(row.rawAssetId):''}</div><div class="${scoreClass(row.score)}">${row.score}% match</div><div class="muted">${escapeHtml(row.reason)}</div></div></div><div class="review-actions"><button ${row.rawAssetId?'':'disabled'} data-action="accept">Accept match</button><button class="secondary" data-action="reject">No matching RAW</button></div>`; item.querySelector('[data-action="accept"]')?.addEventListener('click',()=>updateStatus(row.index,'ACCEPTED')); item.querySelector('[data-action="reject"]')?.addEventListener('click',()=>updateStatus(row.index,'REJECTED')); view.appendChild(item); } observeThumbnails(view,view); }
                    function renderMatches() { const body=$('matchesBody'); body.innerHTML=''; for(const row of state.matches||[]){const tr=document.createElement('tr');tr.innerHTML=`<td>${escapeHtml(row.statusLabel)}</td><td class="${scoreClass(row.score)}">${row.score}%</td><td><div class="asset">${thumbHtml(row.finishedAssetId)}<div class="path">${escapeHtml(row.finishedPath)}</div></div></td><td><div class="asset">${row.rawAssetId?thumbHtml(row.rawAssetId):''}<div class="path">${escapeHtml(row.rawPath||'')}</div></div></td><td>${escapeHtml(row.reason)}</td>`;body.appendChild(tr);} observeThumbnails($('matchesView'),body); }
                    function renderTagPlan(){const body=$('tagPlanBody');body.innerHTML='';for(const row of state.tagPlan||[]){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${escapeHtml(row.tag)}</strong></td><td class="path">${escapeHtml(row.rawPath)}</td><td class="path">${escapeHtml(row.matchedFinalPath||'')}</td><td>${row.score||0}%</td><td>${escapeHtml(row.basis)}</td>`;body.appendChild(tr);}}
                    function renderFinalTagPlan(){const body=$('finalTagPlanBody');body.innerHTML='';for(const row of state.finalTagPlan||[]){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${escapeHtml(row.tag)}</strong></td><td class="path">${escapeHtml(row.finalPath)}</td><td class="path">${escapeHtml(row.matchedRawPath||'')}</td><td>${row.score||0}%</td><td>${escapeHtml(row.basis)}</td>`;body.appendChild(tr);}}
                    function thumbHtml(assetId){return `<img class="thumb" data-asset-id="${escapeHtml(assetId||'')}" alt="Asset thumbnail">`;}
                    function observeThumbnails(scrollRoot,content){thumbnailObservers.get(scrollRoot)?.disconnect();const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;observer.unobserve(entry.target);loadThumbnail(entry.target);}}, {root:scrollRoot,threshold:0});thumbnailObservers.set(scrollRoot,observer);for(const image of content.querySelectorAll('img[data-asset-id]')){const id=image.dataset.assetId;if(thumbs.has(id)){image.src=thumbs.get(id);continue;}observer.observe(image);}}
                    async function loadThumbnail(image){const id=image.dataset.assetId;if(!id)return;let request=thumbnailRequests.get(id);if(!request){request=apiFetch('/api/immich/thumbnail?assetId='+encodeURIComponent(id)).then(response=>{if(!response.ok)throw new Error();return response.blob();}).then(blob=>{const url=URL.createObjectURL(blob);thumbs.set(id,url);return url;}).finally(()=>thumbnailRequests.delete(id));thumbnailRequests.set(id,request);}try{const url=await request;document.querySelectorAll(`img[data-asset-id="${CSS.escape(id)}"]`).forEach(img=>img.src=url);}catch{image.alt='Preview unavailable';}}
                    function showTab(tab){['review','matches','tagPlan','finalTagPlan'].forEach(name=>{$(name+'Tab').classList.toggle('active',name===tab);$(name+'View').style.display=name===tab?'':'none';});if(tab==='review')observeThumbnails($('reviewView'),$('reviewView'));if(tab==='matches')observeThumbnails($('matchesView'),$('matchesBody'));}
                    function showProgress(text,percent){$('progressShell').hidden=false;$('progressText').textContent=text||'Working…';const bar=$('progress');bar.classList.toggle('indeterminate',percent==null||percent<0);bar.firstElementChild.style.width=percent>=0?percent+'%':'';}
                    function hideProgress(){$('progressShell').hidden=true;}
                    function setBusy(value){busy=value;$('scanButton').disabled=value;$('dryRunButton').disabled=value||!state.session;$('applyTagsButton').disabled=value||!state.session;}
                    async function apiFetch(url,options={},retry=true){const headers=new Headers(options.headers||{});const token=localStorage.getItem('pcaAccessToken');if(token)headers.set('X-PCA-Token',token);const response=await fetch(url,{...options,headers});if(response.status===401&&retry){const entered=prompt('Access token');if(entered!==null){localStorage.setItem('pcaAccessToken',entered);return apiFetch(url,options,false);}}return response;}
                    async function restoreOnLoad(){try{const statusResponse=await apiFetch('/api/status');const status=await statusResponse.json();if(!statusResponse.ok)throw new Error(status.error||'Could not restore app state');if(status.hasSession){const sessionResponse=await apiFetch('/api/session');const restored=await sessionResponse.json();if(!sessionResponse.ok)throw new Error(restored.error||'Could not restore scan session');state=restored;render();}const job=status.scanJob;if(job?.state==='RUNNING'){setBusy(true);showProgress(job.message,job.percent);await pollScan();}else if(job?.state==='INTERRUPTED'){message(job.error||job.message);}}catch(error){message(error.message);}}
                    render(); restoreOnLoad();
                  </script>
                </body>
                </html>
                """;
    }
}
