const { test } = require('node:test');
const assert = require('node:assert/strict');
const P = require('../src/main/assets/planner.js');
const xy = (x, y) => [39.99 + y / 111195, 116.32 + x / (111195 * Math.cos(39.99 * Math.PI / 180))];
const square = [[0,0],[600,0],[600,600],[0,600]].map(([x,y]) => xy(x,y));
const u = [[0,0],[600,0],[600,600],[400,600],[400,200],[200,200],[200,600],[0,600]].map(([x,y]) => xy(x,y));
const region = polygon => ({ id: 'a', polygon, available:true });
const point = (x,y,sort=0) => ({ position:xy(x,y), sort });
const config = (checkpoints=[], polygon=square) => ({ center:xy(300,300), regions:[region(polygon)], checkpoints, targetMeters:1000, random:P.seeded(42) });
function check(result, poly, count) {
  assert.deepEqual(result.route[0], result.route.at(-1));
  assert.equal(result.visits.length, count);
  assert(result.meters > 20);
  for (let i=1;i<result.route.length;i++) assert(P.segmentInside(result.route[i-1],result.route[i],poly), 'every entire segment stays inside');
}
function roads(coords, wayNodes, tags={highway:'footway'}) {
  return { elements: [...coords.map(([x,y],i) => ({type:'node',id:i+1,lat:xy(x,y)[0],lon:xy(x,y)[1]})),
    ...wayNodes.map((nodes,i) => ({type:'way',id:100+i,nodes,tags}))] };
}
const ring = roads([[100,100],[500,100],[500,500],[100,500]], [[1,2,3,4,1]]);
test('0/1/multiple checkpoints generate contained closed loops', () => {
  for (const points of [[],[point(150,150)],[point(150,150),point(450,450),point(150,450)]]) check(P.plan(config(points)),square,points.length);
});
test('concave fence requires detour, not a line across the gap', () => {
  assert(!P.segmentInside(xy(100,500),xy(500,500),u));
  const c = {...config([point(500,500),point(100,500)],u),start:xy(100,400),ordered:true};
  const r = P.plan(c); check(r,u,2); assert(r.route.length > 4);
});
test('ordered visits follow sort and preserve equal-sort input order', () => {
  const r = P.plan({...config([point(150,150,30),point(450,450,10),point(150,450,10)]),ordered:true});
  assert.deepEqual(r.visits.map(v=>v.checkpoint),[1,2,0]);
  assert(r.visits.every((v,i,vs)=>!i || v.vertex >= vs[i-1].vertex));
});
test('duplicate checkpoints and checkpoint at start remain visitable', () => {
  const c = {...config([point(100,100,1),point(100,100,2),point(500,500,3)]),start:xy(100,100),ordered:true};
  check(P.plan(c),square,3); const r = P.plan(c,ring); assert.equal(r.mode,'roads'); check(r,square,3);
});
test('real road graph is preferred for 0/1/multiple points', () => {
  for (const points of [[],[point(500,500)],[point(100,100),point(500,500)]]) {
    const r=P.plan({...config(points),start:xy(100,100)},ring); assert.equal(r.mode,'roads'); check(r,square,points.length);
  }
});
test('directed foot streets retain direction when snapped', () => {
  const directed = roads([[100,100],[500,100],[500,500],[100,500]], [[1,2,3,4,1]], {highway:'footway','oneway:foot':'yes'});
  const r=P.plan({...config([point(500,300),point(100,400)]),start:xy(100,100),ordered:true},directed);
  assert.equal(r.mode,'roads'); check(r,square,2);
});
test('road disconnected islands and distant checkpoints fall back explicitly', () => {
  const islands=roads([[100,100],[200,100],[500,500],[500,400]],[[1,2],[3,4]]);
  const r=P.plan({...config([point(100,100),point(500,500)]),start:xy(100,100)},islands);
  assert.equal(r.mode,'geometry'); assert.match(r.warnings[0],/不连通/); check(r,square,2);
  assert.equal(P.plan(config([point(300,300),point(500,500)]),ring).mode,'geometry');
});
test('access and foot restrictions are respected', () => {
  assert(!P.walkable({highway:'motorway'})); assert(!P.walkable({highway:'footway',foot:'no'}));
  assert(!P.walkable({highway:'service',access:'private'})); assert(P.walkable({highway:'service',access:'private',foot:'yes'}));
  assert(!P.walkable({highway:'pedestrian',area:'yes'}));
});
test('unavailable/disjoint regions cannot silently escape their fence', () => {
  const c=config([point(100,100),point(500,500)]); c.regions[0].available=false;
  assert.throws(()=>P.plan(c),/可用区域/);
  const left=[[0,0],[200,0],[200,600],[0,600]].map(([x,y])=>xy(x,y));
  const right=[[400,0],[600,0],[600,600],[400,600]].map(([x,y])=>xy(x,y));
  assert.throws(()=>P.plan({...config(c.checkpoints),regions:[region(left),{...region(right),id:'b'}]}),/可用区域/);
});
test('multiple regions randomly selected; fixed start is preserved', () => {
  const ids=new Set();
  for(let seed=0;seed<50;seed++) ids.add(P.plan({...config(),regions:[region(square),{...region(square),id:'b'}],random:P.seeded(seed)}).regionId);
  assert.equal(ids.size,2);
  const r=P.plan({...config([point(150,150)]),start:xy(300,300),regionId:'a'}); assert.deepEqual(r.start,xy(300,300));
});
test('no region, NFC points, incomplete Overpass data and invalid inputs', () => {
  const r=P.plan({...config([{...point(150,150),nfcEnable:true}]),regions:[]});
  assert.equal(r.regionId,null); assert(r.warnings.some(s=>s.includes('NFC')));
  assert.equal(P.plan(config(),{...ring,remark:'timeout'}).mode,'geometry');
  assert.throws(()=>P.plan({...config(),targetMeters:NaN}),/长度/);
  assert.throws(()=>P.plan({...config(),checkpoints:[{position:[NaN,116],sort:1}]}),/坐标/);
  assert.throws(()=>P.validatePolygon([[0,0],[.001,.001],[0,.001],[.001,0]]),/自交/);
  assert.throws(()=>P.plan({...config(),start:xy(800,800)}),/区域/);
});
test('GCJ-02 round trip is sub-metre, with unchanged coordinates outside China', () => {
  const wgs=[39.999,116.326], gcj=P.wgsToGcj(wgs);
  assert(P.distance(wgs,gcj)>100); assert(P.distance(wgs,P.gcjToWgs(gcj))<.1);
  assert.deepEqual(P.wgsToGcj([51.5,-.1]),[51.5,-.1]);
});
test('public OSM campus snapshot: zero/single/three-point road routes', () => {
  const osm=require('./fixtures/demo-roads.json');
  const polygon=[[39.997,116.322],[39.997,116.330],[40.0015,116.330],[40.0015,116.322]];
  const cps=[{position:[39.9980819,116.3232452],sort:3},{position:[40.0010782,116.3239536],sort:1},{position:[40.0003586,116.3280318],sort:2}];
  for(const points of [[],cps.slice(0,1),cps]) {
    const r=P.plan({center:[39.999,116.326],regions:[region(polygon)],checkpoints:points,targetMeters:1000,random:P.seeded(7),ordered:true},osm);
    assert.equal(r.mode,'roads'); check(r,polygon,points.length);
  }
});
test('60 seeds × concave/convex × 0/1/5 checkpoints: no omitted point or escaping segment', () => {
  for(let seed=0;seed<60;seed++) for(const polygon of [square,u]) for(const count of [0,1,5]) {
    const random=P.seeded(seed), points=Array.from({length:count},(_,i)=>({position:P.randomPoint(polygon,random),sort:count-i}));
    const r=P.plan({...config(points,polygon),random,ordered:true}); check(r,polygon,count);
  }
});
test('reuse penalty chooses a fresh road detour while retaining ordered checkpoints',()=>{
  const asymmetric=roads([[100,100],[500,100],[600,600],[100,500]],[[1,2,3,4,1]]);
  for(const start of [xy(100,100),xy(300,100)]){
    const c={...config([point(500,100,1),point(100,500,2)]),start,ordered:true};
    const before=P.plan({...c,avoidRepeats:false},asymmetric),after=P.plan({...c,avoidRepeats:true},asymmetric);
    assert(before.repeatedMeters>190);assert(after.repeatedMeters<1);
    assert(after.meters>before.meters);assert(after.meters<before.meters*1.5);
    assert.deepEqual(after.visits.map(v=>v.checkpoint),[0,1]);check(after,square,2);
  }
});
test('dead ends preserve required checkpoints and report unavoidable repeated roads',()=>{
  const dead=roads([[100,100],[300,100],[500,100]],[[1,2,3]]);
  const r=P.plan({...config([point(300,100,1),point(500,100,2)]),start:xy(100,100),ordered:true},dead);
  assert.equal(r.mode,'roads');assert(r.repeatedMeters>390);
  assert(r.warnings.some(s=>s.includes('无法完全消除')));check(r,square,2);
});
test('reuse measurement detects partial and reverse overlap, not just identical vertices',()=>{
  assert(Math.abs(P.measureRepeats([xy(0,0),xy(100,0),xy(50,0)])-50)<.1);
  assert(P.measureRepeats([xy(0,0),xy(100,0),xy(100,100),xy(0,100),xy(0,0)])<.1);
});
test('manual planning only follows supplied anchors and has no checkpoint semantics',()=>{
  const anchors=[xy(100,100),xy(500,100),xy(300,450)];
  const r=P.manualPlan(anchors);assert.equal(r.mode,'manual');assert.deepEqual(r.start,anchors[0]);
  assert.deepEqual(r.route[0],r.route.at(-1));assert.equal(r.visits.length,0);assert.equal(r.checkpoints.length,0);
  assert(anchors.every(p=>r.route.some(q=>P.distance(p,q)<.01)));
  assert.throws(()=>P.manualPlan([anchors[0]]),/2–200/);
  assert.throws(()=>P.manualPlan([anchors[0],anchors[0]]),/重合/);
});
