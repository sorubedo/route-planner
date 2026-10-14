'use strict';
const $ = id => document.getElementById(id);
const P = window.Planner;
const map = new MapUI();
let state = { regions: [], checkpoints: [], manualPoints:[] }, draft = [], mode = '', history = [], result = null, playing = null, generation = 0;
let accepting = null, planningController = null;
const automatic=()=>$('modeAuto').checked;
const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const say = (s, error = false) => { $('status').textContent = s; $('status').className = error ? 'error' : ''; };
function stop() { if (playing) cancelAnimationFrame(playing); playing = null; map.runner(null); }
function invalidate() { planningController?.abort(); planningController=null; stop(); generation++; result = null; $('result').hidden = true; }
function save() { localStorage.setItem('route-planner-state-v1', JSON.stringify({ ...state, center: [map.getCenter().lat, map.getCenter().lng], planningMode:automatic()?'automatic':'manual',ordered:$('ordered').checked,roads:$('roads').checked,targetMeters:Number($('meters').value) })); }
function mutate(fn) {
  history.push(JSON.stringify(state)); if (history.length > 50) history.shift();
  invalidate(); fn(); save(); draw();
}
function draw() {
  map.draw({regions:automatic()?state.regions:[],checkpoints:automatic()?state.checkpoints:state.manualPoints.map(position=>({position})),draft,
    ...(result?{route:result.route,start:result.start,routeMode:result.mode,segments:result.segments}:{}),
    hint:mode==='area'?'依次点击区域顶点':mode==='point'?(automatic()?'点击插入演示打卡点':'点击插入路线点'):(automatic()?'自动规划 · 优先减少本圈重复路段':'手动插点 · 按原有路线逻辑循环')});
  document.body.classList.toggle('manual-mode',!automatic());
  $('point').textContent=automatic()?'插入打卡点':'插入路线点';
  $('note').textContent=automatic()?'可离线编辑与几何规划。蓝色为道路规划，橙色为几何回退；优先减少本圈重复路段，腾讯步行路线逐段规划；每轮最多 20 秒、最多三轮，只重试未成功路段。':'手动路线按插点顺序与原有算法循环，不按道路或打卡规则调整。';
  $('plan').textContent=automatic()?'随机起点 · 生成闭合路线':'按路线点生成循环预览';
  $('items').innerHTML = !automatic()?state.manualPoints.map((p,i)=>`<div class="row"><span>路线点 ${i+1}</span><button data-manual-delete="${i}">删除</button></div>`).join(''):state.regions.map((r, i) => `<div class="row"><span>区域 ${i + 1} · ${r.polygon.length} 个顶点</span><button data-region-toggle="${i}">${r.available === false ? '启用' : '停用'}</button><button data-region-delete="${i}">删除</button></div>`).join('')
    + state.checkpoints.map((p, i) => `<div class="row"><span>点 ${i + 1} · 顺序 ${p.sort}${p.nfcEnable ? ' · NFC' : ''}</span><button data-point-sort="${i}">顺序</button><button data-point-delete="${i}">删除</button></div>`).join('');
  $('finishArea').disabled = mode !== 'area' || draft.length < 3;
  $('area').classList.toggle('active', mode === 'area'); $('point').classList.toggle('active', mode === 'point');
  $('plan').disabled = draft.length>0 || (!automatic() && state.manualPoints.length<2);
}
map.onClick(p => {
  if (mode === 'area') { if (draft.length >= 80) return say('每个区域最多 80 个顶点', true); invalidate(); draft.push(p); draw(); }
  if (mode === 'point' && !automatic()) {
    if(state.manualPoints.length>=200)return say('最多 200 个路线点',true);
    mutate(()=>state.manualPoints.push(p));say('已插入路线点；按插入顺序循环，不处理打卡规则。');return;
  }
  if (mode === 'point') {
    if (state.checkpoints.length >= 24) return say('最多 24 个打卡点', true);
    mutate(() => state.checkpoints.push({ position: p, sort: state.checkpoints.length + 1, nfcEnable: false }));
    say('已插入打卡点。启用顺序打卡后按“顺序”数值访问。');
  }
});
$('area').onclick = () => { mode = mode === 'area' ? '' : 'area'; draw(); say('依次点击区域顶点，然后点“完成区域”。'); };
$('finishArea').onclick = () => {
  try {
    P.validatePolygon(draft); if (state.regions.length >= 8) throw new Error('最多 8 个区域');
    mutate(() => state.regions.push({ id: 'area-' + Date.now(), polygon: [...draft], available: true }));
    draft = []; mode = ''; draw(); say('区域已保存。生成路线时只选择可容纳所有点的启用区域。');
  } catch (e) { say(e.message, true); }
};
$('point').onclick = () => {
  if (draft.length) return say('请先完成区域，或撤销草稿顶点', true);
  mode = mode === 'point' ? '' : 'point'; draw(); say('点击地图插入打卡点。');
};
$('undo').onclick = () => {
  if (draft.length) { draft.pop(); invalidate(); draw(); }
  else if (history.length) { invalidate(); state = JSON.parse(history.pop()); save(); draw(); }
};
$('items').onclick = event => {
  const d = event.target.dataset;
  if(d.manualDelete!==undefined)mutate(()=>state.manualPoints.splice(+d.manualDelete,1));
  if (d.regionDelete !== undefined) mutate(() => state.regions.splice(+d.regionDelete, 1));
  if (d.pointDelete !== undefined) mutate(() => state.checkpoints.splice(+d.pointDelete, 1));
  if (d.regionToggle !== undefined) mutate(() => { const r = state.regions[+d.regionToggle]; r.available = r.available === false; });
  if (d.pointSort !== undefined) openDialog('设置打卡顺序', '输入顺序数值；相同数值保持列表先后。', state.checkpoints[+d.pointSort].sort, value => {
    if (value.trim() === '' || !Number.isFinite(Number(value))) throw new Error('请输入有效顺序数值');
    mutate(() => state.checkpoints[+d.pointSort].sort = Number(value));
  });
};
function openDialog(title, help, value, accept, text = true) {
  $('dialogTitle').textContent = title; $('dialogHelp').textContent = help;
  $('dialogInput').value = String(value); $('dialogInput').hidden = !text;
  accepting = accept; $('dialog').showModal();
}
$('dialogCancel').onclick = () => $('dialog').close();
$('dialogOk').onclick = () => { try { if (accepting) accepting($('dialogInput').value); $('dialog').close(); } catch (e) { $('dialogHelp').textContent = e.message; } };
$('mapSettings').onclick = () => { save(); invalidate(); window.Native.mapSettings(); };
$('help').onclick = () => openDialog('使用说明', '1. 点击“划分区域”，依次点地图顶点并完成，可绘制多个区域。2. 插入打卡点，按需设置顺序。3. 开启自动规划并生成路线。先在“地图设置”填写你自己的腾讯地图 Key。自动模式随机起点并经过打卡点，优先减少本圈重复路段。手动模式按插入的路线点生成闭环。没有区域时以地图中心附近的范围规划。腾讯步行逐段规划，每轮最多 20 秒、最多三轮，失败的路段单独回退到围栏内折线，不能保证避开建筑或水域。沿路线预览可查看行走顺序。地图加载会发送地图范围，步行请求会向腾讯发送每段起终点坐标。', '', null, false);
$('jump').onclick = () => openDialog('坐标跳转', '输入 WGS84 纬度,经度；显示到腾讯地图时自动转换为 GCJ-02。例如 39.999,116.326。GCJ-02 数据可通过导入自动转换。', '', value => {
  const p = value.split(/[,，\s]+/).filter(Boolean).map(Number);
  if (p.length !== 2 || !p.every(Number.isFinite) || Math.abs(p[0]) >= 85 || Math.abs(p[1]) > 180) throw new Error('坐标无效，请填写纬度,经度');
  map.setView(p, 17); save();
});
function demo() {
  invalidate(); draft = []; mode = '';
  state = { regions: [{ id: 'demo', available: true, polygon: [[39.997,116.322],[39.997,116.330],[40.0015,116.330],[40.0015,116.322]] }],
    manualPoints:[[39.9980819,116.3232452],[40.0010782,116.3239536],[40.0003586,116.3280318]],
    checkpoints: [{ position: [39.9980819,116.3232452], sort: 1 }, { position: [40.0010782,116.3239536], sort: 2 }, { position: [40.0003586,116.3280318], sort: 3 }] };
  map.fitBounds(state.regions[0].polygon, { padding: [25,25] }); save(); draw(); say('已加载演示区域与 3 个示例点；可清空后绘制自己的区域。');
  const token = generation;
}
$('satellite').onchange=()=>{if(window.Native)window.Native.satellite($('satellite').checked);};
for(const id of ['modeManual','modeAuto'])$(id).onchange=()=>{draft=[];mode='';invalidate();save();draw();say(automatic()?'自动规划：随机起点、全部打卡点、闭环；优先少走重复路段。':'手动插点：按插点顺序生成闭合路线。');};
$('demo').onclick = () => { history.push(JSON.stringify(state)); demo(); };
$('clear').onclick = () => { draft = []; mode = ''; mutate(() => { state = { regions: [], checkpoints: [], manualPoints:[] }; });say(automatic()?'已清空，未绘制区域时在地图中心附近生成闭环。':'已清空，请插入至少两个路线点。'); };
for (const id of ['ordered', 'roads', 'meters']) $(id).onchange = () => { invalidate();save();draw(); };

function manualResult(){
  const data=JSON.parse(window.Native.manual(JSON.stringify(state.manualPoints.map(P.wgsToGcj))));
  if(data.error)throw new Error(data.error);
  const route=data.route.map(P.gcjToWgs);
  return{schemaVersion:1,coordinateSystem:'WGS84',mode:'manual',route,start:route[0],meters:data.meters,visits:[],checkpoints:[],warnings:[],closed:true};
}
$('plan').onclick = async () => {
  if(draft.length)return;
  invalidate(); const current=generation; planningController=new AbortController(); const signal=planningController.signal;
  const config={...JSON.parse(JSON.stringify(state)),center:[map.getCenter().lat,map.getCenter().lng],ordered:$('ordered').checked,targetMeters:Number($('meters').value),avoidRepeats:true};
  $('plan').disabled=true;
  try {
    let planned;
    if(!automatic())planned=manualResult();
    else {
      say('正在准备闭合路线…');
      planned=await RoadClient.plan(config,{signal,roads:$('roads').checked,onProgress:({attempt,completed,total})=>{
        if(current!==generation)return;
        say('腾讯步行规划（'+attempt+'/3）：已保留 '+completed+'/'+total+' 段道路，每轮最多 20 秒');
      }});
    }
    if(current!==generation)return;
    result=planned;
    $('result').hidden=false;
    const roadCount=result.roadSegments??(result.mode==='roads'?result.segments?.length??0:0);
    const fallbackCount=(result.segments?.length??0)-roadCount;
    $('routeMode').textContent=result.mode==='manual'?'手动插点循环':result.mode==='roads'?'腾讯步行道路':result.mode==='mixed'?'道路 + 分段回退':'围栏内折线';
    $('routeLength').textContent=(result.meters/1000).toFixed(2)+' km';
    $('visitCount').textContent=result.mode==='manual'?state.manualPoints.length+' 个路线点 · 闭合':config.checkpoints.length+' 个打卡点 · '+roadCount+' 段道路 / '+fallbackCount+' 段折线';
    $('warnings').textContent=result.warnings.join('；');
    const text=result.mode==='manual'?'手动路线已生成，按插点顺序循环。':
      '自动路线已生成：'+roadCount+' 段道路、'+fallbackCount+' 段围栏内折线；重复约 '+Math.round(result.repeatedMeters??0)+' 米。';
    say(text);window.Native?.toast(text);
    draw();map.fitBounds(result.route,{padding:[20,20]});
  }catch(e){if(current===generation)say(e.message,true);}
  finally{if(current===generation){planningController=null;draw();}}
};
$('play').onclick = () => {
  if (!result) return; stop();
  const offsets=[0];result.route.slice(1).forEach((p,i)=>offsets.push(offsets.at(-1)+P.distance(result.route[i],p)));
  const begin=performance.now(),snapshot=result;let lastFrame=0;
  function tick(now){
    if(result!==snapshot)return;
    if(now-lastFrame>=70){
      const d=((now-begin)*snapshot.meters/20000)%snapshot.meters;
      let i=1;while(i<offsets.length-1&&offsets[i]<d)i++;
      const t=(d-offsets[i-1])/(offsets[i]-offsets[i-1]||1),a=snapshot.route[i-1],b=snapshot.route[i];
      map.runner([a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t]);lastFrame=now;
    }
    playing=requestAnimationFrame(tick);
  }
  playing=requestAnimationFrame(tick);say('正在循环预览，每圈 20 秒。');
};
$('export').onclick = async () => {
  if (!result) return;
  const payload = { ...result, planningMode:automatic()?'automatic':'manual',manualPoints:state.manualPoints,regions: state.regions, ordered: $('ordered').checked, mapPreview: { coordinateSystem: 'GCJ-02',
    interpolation: 'linear', start: P.wgsToGcj(result.start), route: result.route.map(P.wgsToGcj) } };
  const text = JSON.stringify(payload, null, 2);
  if (window.Native) window.Native.copy(text);
  else { try { await navigator.clipboard.writeText(text); say('规划 JSON 已复制。'); } catch (_) { openDialog('规划 JSON', '复制以下内容，可保存为 JSON 文件。', text, null); } }
};
$('import').onclick = () => openDialog('导入区域 / 打卡点', '粘贴本应用导出的 JSON，或 {"coordinateSystem":"GCJ-02","regions":[{"id":"a","polygon":[[纬度,经度],…]}],"targetPoints":[{"lat":纬度,"lng":经度,"sort":1}],"clockMode":2}。2/3 为顺序模式。没有区域可传空数组。', '', value => {
  if (value.length > 2000000) throw new Error('导入数据过大');
  const input = JSON.parse(value);
  if (!['WGS84','GCJ-02'].includes(input.coordinateSystem)) throw new Error('必须明确 coordinateSystem：WGS84 或 GCJ-02');
  const convert = p => input.coordinateSystem === 'GCJ-02' ? P.gcjToWgs(p) : p;
  const regions = (input.regions || []).map((r, i) => ({ id: String(r.id ?? 'import-'+i), available: r.available !== false, polygon: r.polygon.map(convert) }));
  const checkpoints = (input.checkpoints || input.targetPoints || []).map((p, i) => ({ position: convert(p.position || [p.lat,p.lng]), sort: p.sort ?? i+1, nfcEnable: p.nfcEnable === true }));
  const manualPoints=(input.manualPoints||[]).map(convert);
  if(manualPoints.length && (manualPoints.length>200 || !manualPoints.every(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)&&Math.abs(p[0])<85&&Math.abs(p[1])<=180)))throw new Error('手动路线点无效');
  const coords = [...manualPoints,...regions.flatMap(r => r.polygon), ...checkpoints.map(p => p.position)];
  const center = coords[0] || [map.getCenter().lat,map.getCenter().lng];
  const ordered = input.ordered === true || [2,3].includes(input.clockMode);
  // Validate structure and constraints without requiring a feasible tour at import time.
  if (regions.length > 8 || checkpoints.length > 24) throw new Error('最多 8 个区域与 24 个点');
  regions.forEach(r => P.validatePolygon(r.polygon));
  if (!checkpoints.every(p => Array.isArray(p.position) && p.position.length===2 && p.position.every(Number.isFinite) && Math.abs(p.position[0])<85 && Math.abs(p.position[1])<=180 && Number.isFinite(p.sort))) throw new Error('打卡点坐标或顺序无效');
  draft = []; mode = '';
  mutate(() => { state = { regions, checkpoints,manualPoints }; }); $('ordered').checked=ordered;
  $('modeManual').checked=input.planningMode==='manual';$('modeAuto').checked=input.planningMode!=='manual';draw();
  if (coords.length) map.fitBounds(coords, { padding:[25,25], maxZoom:17 }); else map.setView(center,16);
  save(); say('导入成功。');
});
try {
  const saved = JSON.parse(localStorage.getItem('route-planner-state-v1'));
  if (saved && Array.isArray(saved.regions) && Array.isArray(saved.checkpoints)) {
    state = { regions:saved.regions, checkpoints:saved.checkpoints,manualPoints:saved.manualPoints||[] };
    $('modeManual').checked=saved.planningMode==='manual';$('modeAuto').checked=saved.planningMode!=='manual';
    $('ordered').checked=saved.ordered===true;$('roads').checked=saved.roads!==false;if(Number.isFinite(saved.targetMeters))$('meters').value=saved.targetMeters; if (saved.center) map.setView(saved.center,16); draw();
  }
  else demo();
} catch (_) { demo(); }
