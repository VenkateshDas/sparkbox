// Minimal interaction/reducer checks. No browser dependencies; layout is not verified here.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const elements = new Map(), handlers = {};
const element = id => {
  if (!elements.has(id)) elements.set(id, {innerHTML:'',textContent:'',className:'',checked:false,open:false,
    style:{},classList:{toggle(){}},showModal(){this.open=true;},close(){this.open=false;},
    addEventListener(){},scrollIntoView(){},getBoundingClientRect(){return {left:0,right:500,top:0,bottom:500};}});
  return elements.get(id);
};
const sandbox = {window:{},document:{getElementById:element,body:{classList:{toggle(){}}},
  addEventListener(type,fn){handlers[type]=fn;},createElement(){return {click(){}};}},
  location:{hash:''},history:{replaceState(){}},setTimeout(){},Blob,URL,console};
sandbox.window.addEventListener=()=>{};
sandbox.window.scrollTo=()=>{};
vm.createContext(sandbox);
for(const file of ['snapshot.js','app.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),sandbox);
const run = code => vm.runInContext(code,sandbox);
assert.match(element('content').innerHTML,/475/);
for(const section of ['Overview','Vault','Habits','Recall','Scout','Costs','Health']){
  run(`go('${section}')`);
  assert.ok(element('content').innerHTML.length>500,section);
  assert.ok(!element('content').innerHTML.includes('NaN'),section);
}
run('demo=true');
for(const section of ['Overview','Vault','Habits','Recall','Scout','Costs','Health']){
  run(`go('${section}')`);
  assert.ok(!element('content').innerHTML.includes('NaN'),section+' demo');
}
run("range='7'; model='All'; purpose='All'");
const seven=run('rows().length');
run("range='30'");
assert.ok(run('rows().length')>seven);
assert.equal(run('totals(rows()).known + totals(rows()).missing'),run('rows().length'));
assert.equal(run('totals(rows()).input + totals(rows()).output'),run('rows().reduce((n,r)=>n+r.input+r.output,0)'));
run("go('Vault');query='';kind='Sources';quality='title_only'");
assert.equal(run('filteredNotes().length'),67);
run("quality='failed'");assert.equal(run('filteredNotes().length'),6);
run("kind='All';quality='All';query='no-note-with-this-made-up-title-xyz'");
assert.equal(run('filteredNotes().length'),0);
handlers.click({target:{closest:()=>({dataset:{note:'0'}})}});
assert.equal(element('detail').open,true,'note row opens details');
element('detail').close();
handlers.click({target:{closest:()=>({dataset:{request:'demo-0001'}})}});
assert.equal(element('detail').open,true,'request row opens details');
assert.match(element('detailContent').innerHTML,/Synthetic request/);
assert.equal(run(`esc('<img src=x onerror="bad">')`),'&lt;img src=x onerror=&quot;bad&quot;&gt;');
assert.equal(run('V.total'),run('Object.values(V.counts).reduce((a,b)=>a+b,0)'));
run("go('Costs');demo=true;range='30'");
assert.match(run('costs()'),/What does each workflow consume/);
assert.match(run('costs()'),/Token consumption over time/);
const micros=code=>Math.round(run(code)*1e6);
assert.equal(micros('Object.keys(workflowStages).reduce((sum,w)=>sum+totals(rows().filter(r=>r.workflow===w)).cost,0)'),micros('totals(rows()).cost'),'workflow partitions reconcile');
assert.equal(micros('[...new Set(rows().map(r=>r.stage))].reduce((sum,stage)=>sum+totals(rows().filter(r=>r.stage===stage)).cost,0)'),micros('totals(rows()).cost'),'stage partitions reconcile');
assert.ok(run('operationStats(rows()).priced.length')>0);
assert.ok(run('operationStats(rows()).tokenOps.length')>0);
assert.ok(run('operationStats(rows()).priced.every(op=>op.status==="Completed" && rows().filter(r=>r.operationId===op.id).every(r=>r.cost!==null))'));
assert.ok(run('demoRequests.some(r=>r.workflow==="Capture processing"&&r.feature==="Cards")'));
assert.ok(run('demoRequests.some(r=>r.workflow==="Scout interaction"&&r.feature==="Cards")'));
assert.equal(run('new Set(demoRequests.map(r=>r.id)).size'),run('demoRequests.length'));
assert.ok(run('demoRequests.filter(r=>r.retry).every(r=>r.attempt>0)'));
handlers.click({target:{closest:()=>({dataset:{workflow:'Capture processing'}})}});
assert.ok(run('rows().every(r=>r.workflow==="Capture processing")'));
handlers.change({target:{id:'model',value:'Fast model'}});
assert.ok(run('operationStats(rows()).partial')>0,'model filtering excludes partial operations from unit averages');
assert.ok(run('operationStats(rows()).full.every(op=>demoRequests.filter(r=>r.operationId===op.id).every(r=>rows().some(v=>v.id===r.id)))'));
handlers.change({target:{id:'workflow',value:'All'}});
handlers.change({target:{id:'model',value:'Audio model'}});
assert.equal(run('totals(rows()).tokenKnown'),0);
assert.match(run('costs()'),/Unavailable/,'audio without token fields stays unavailable');
assert.equal(run('operationStats([]).costPerComplete'),null);
assert.equal(run('operationStats([]).tokensPerComplete'),null);
element('detail').close();
handlers.click({target:{closest:()=>({dataset:{operation:'op-1-0'}})}});
assert.equal(element('detail').open,true);
assert.match(element('detailContent').innerHTML,/Saved locally/);
assert.match(element('detailContent').innerHTML,/0 AI tokens/);
assert.match(element('detailContent').innerHTML,/All operation requests shown/);
run("go('Scout');model='All';purpose='All'");
assert.ok(run('rows(true).every(r=>r.workflow==="Scout interaction")'));
assert.ok(run('rows(true).some(r=>r.feature==="Cards")'),'Scout totals include its card tool calls');
console.log('Passed: existing 7-view checks plus workflow/stage cost reconciliation, token coverage, operation cohorts, partial filters, card attribution, retries, audio unknowns and operation drill-downs.');
