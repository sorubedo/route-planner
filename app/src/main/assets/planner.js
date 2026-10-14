/* Geometry is WGS84, [latitude, longitude]. No Android or map-SDK dependency. */
(function (root) {
  'use strict';
  const R = 6371008.8, RAD = Math.PI / 180;
  const fail = message => { throw new Error(message); };
  const valid = p => Array.isArray(p) && p.length === 2 && p.every(Number.isFinite) && Math.abs(p[0]) < 85 && Math.abs(p[1]) <= 180;
  const same = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) < 1e-10;
  const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  function distance(a, b) {
    const x = (b[1] - a[1]) * RAD * Math.cos((a[0] + b[0]) * RAD / 2);
    return R * Math.hypot(x, (b[0] - a[0]) * RAD);
  }
  const length = path => path.slice(1).reduce((n, p, i) => n + distance(path[i], p), 0);
  const cross = (a, b, c) => (b[1] - a[1]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[1] - a[1]);
  function project(p, a, b) {
    const scale = Math.cos(p[0] * RAD), dx = (b[1] - a[1]) * scale, dy = b[0] - a[0];
    const t = Math.max(0, Math.min(1, ((p[1] - a[1]) * scale * dx + (p[0] - a[0]) * dy) / (dx * dx + dy * dy || 1)));
    const point = lerp(a, b, t);
    return { point, t, distance: distance(p, point) };
  }
  function inside(p, polygon) {
    let yes = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const a = polygon[j], b = polygon[i];
      if (project(p, a, b).distance < .005) return true;
      if ((a[0] > p[0]) !== (b[0] > p[0]) && p[1] < (b[1] - a[1]) * (p[0] - a[0]) / (b[0] - a[0]) + a[1]) yes = !yes;
    }
    return yes;
  }
  function intersections(a, b, c, d) {
    const rx = b[1] - a[1], ry = b[0] - a[0], sx = d[1] - c[1], sy = d[0] - c[0];
    const denominator = rx * sy - ry * sx;
    if (Math.abs(denominator) < 1e-16) return [];
    const qx = c[1] - a[1], qy = c[0] - a[0];
    const t = (qx * sy - qy * sx) / denominator, u = (qx * ry - qy * rx) / denominator;
    return t >= -1e-9 && t <= 1 + 1e-9 && u >= -1e-9 && u <= 1 + 1e-9 ? [Math.max(0, Math.min(1, t))] : [];
  }
  function segmentInside(a, b, poly) {
    if (!inside(a, poly) || !inside(b, poly)) return false;
    const ts = [0, 1];
    for (let i = 0; i < poly.length; i++) ts.push(...intersections(a, b, poly[i], poly[(i + 1) % poly.length]));
    ts.sort((x, y) => x - y);
    // Check each interval, including narrow excursions through concave boundaries.
    return ts.slice(1).every((t, i) => inside(lerp(a, b, (t + ts[i]) / 2), poly));
  }
  const bounds = poly => [Math.min(...poly.map(p => p[0])), Math.min(...poly.map(p => p[1])), Math.max(...poly.map(p => p[0])), Math.max(...poly.map(p => p[1]))];
  function validatePolygon(poly) {
    if (!Array.isArray(poly) || poly.length < 3 || poly.length > 80 || !poly.every(valid)) fail('区域需要 3–80 个有效顶点');
    for (let i = 0; i < poly.length; i++) {
      if (distance(poly[i], poly[(i + 1) % poly.length]) < .1) fail('区域存在重复或过近顶点');
      for (let j = i + 1; j < poly.length; j++) {
        if (j === i + 1 || (i === 0 && j === poly.length - 1)) continue;
        const a = poly[i], b = poly[(i + 1) % poly.length], c = poly[j], d = poly[(j + 1) % poly.length];
        if (intersections(a, b, c, d).length || (Math.abs(cross(a, b, c)) < 1e-15 && project(c, a, b).distance < .005)
            || (Math.abs(cross(a, b, d)) < 1e-15 && project(d, a, b).distance < .005)) fail('区域边界不能自交或重叠');
      }
    }
    const [s, w, n, e] = bounds(poly);
    if (n - s > .05 || e - w > .07) fail('仅支持较小范围，请缩小区域');
    const origin = poly[0];
    const area = Math.abs(poly.reduce((sum, p, i) => sum + cross(origin, p, poly[(i + 1) % poly.length]), 0)) * R * R * RAD * RAD * Math.cos(origin[0] * RAD) / 2;
    if (area < 25) fail('区域面积过小，至少需要 25 平方米');
    return poly;
  }
  function randomPoint(poly, random = Math.random, margin = 3) {
    const [s, w, n, e] = bounds(poly);
    for (let i = 0; i < 2500; i++) {
      const p = [s + random() * (n - s), w + random() * (e - w)];
      if (inside(p, poly) && poly.every((a, j) => project(p, a, poly[(j + 1) % poly.length]).distance >= margin)) return p;
    }
    fail('区域过窄，无法取得有边界余量的随机起点');
  }
  function seeded(seed) {
    let x = seed >>> 0;
    return () => { x += 0x6D2B79F5; let t = Math.imul(x ^ x >>> 15, 1 | x); t ^= t + Math.imul(t ^ t >>> 7, 61 | t); return ((t ^ t >>> 14) >>> 0) / 4294967296; };
  }

  class Graph {
    constructor() { this.points = []; this.adj = []; this.edges = []; }
    node(p) { this.points.push(p); this.adj.push([]); return this.points.length - 1; }
    edge(a, b, road = false, directed = false, source = null) {
      if (a === b) return;
      const e = { a, b, weight: distance(this.points[a], this.points[b]), road, id: this.edges.length,
        base: source?.base ?? (Math.min(a,b)+':'+Math.max(a,b)),
        from: source?.from ?? (a<b?0:1), to: source?.to ?? (a<b?1:0),
        baseMeters: source?.baseMeters ?? distance(this.points[a],this.points[b]) };
      this.edges.push(e); this.adj[a].push({ to: b, e });
      if (!directed) this.adj[b].push({ to: a, e });
    }
    shortest(from, to, banned = -1, usage = null, penalty = 4) {
      const cost = Array(this.points.length).fill(Infinity), previous = [], previousEdges = [], heap = [];
      const push = v => { heap.push(v); let i = heap.length - 1; while (i && heap[(i - 1) >> 1][0] > v[0]) { heap[i] = heap[(i - 1) >> 1]; i = (i - 1) >> 1; } heap[i] = v; };
      const pop = () => { const first = heap[0], last = heap.pop(); if (heap.length) { let i = 0; while (i * 2 + 1 < heap.length) { let j = i * 2 + 1; if (j + 1 < heap.length && heap[j + 1][0] < heap[j][0]) j++; if (heap[j][0] >= last[0]) break; heap[i] = heap[j]; i = j; } heap[i] = last; } return first; };
      cost[from] = 0; push([0, from]);
      while (heap.length) {
        const [d, u] = pop(); if (d !== cost[u]) continue;
        if (u === to) {
          const nodes=[u], edges=[];
          while(nodes[0]!==from){edges.unshift(previousEdges[nodes[0]]);nodes.unshift(previous[nodes[0]]);}
          return {nodes,edges,length:edges.reduce((s,e)=>s+e.weight,0),cost:d};
        }
        for (const { to: v, e } of this.adj[u]) {
          const weight=e.weight+(usage?overlap(e,usage)*penalty:0);
          if(e.id!==banned && d+weight<cost[v]){cost[v]=d+weight;previous[v]=u;previousEdges[v]=e;push([cost[v],v]);}
        }
      }
      return null;
    }
  }
  function walkable(tags) {
    const h = tags.highway, foot = tags.foot;
    if (!h || /^(motorway|motorway_link|trunk|trunk_link|construction|proposed|raceway)$/.test(h) || tags.area === 'yes') return false;
    if (['no', 'private'].includes(foot)) return false;
    if (['no', 'private'].includes(tags.access) && !['yes', 'designated', 'permissive'].includes(foot)) return false;
    return ['footway', 'path', 'pedestrian', 'steps', 'track', 'residential', 'service', 'living_street', 'unclassified', 'tertiary', 'tertiary_link', 'secondary', 'secondary_link', 'primary', 'primary_link', 'cycleway'].includes(h);
  }
  function roadGraph(osm, poly) {
    const g = new Graph(), raw = new Map(), ids = new Map();
    if (!osm || !Array.isArray(osm.elements) || osm.remark) return g;
    if (osm.elements.length > 50000) fail('路网过大，请缩小范围');
    for (const e of osm.elements) if (e.type === 'node' && valid([e.lat, e.lon])) raw.set(e.id, [e.lat, e.lon]);
    const node = id => { if (!ids.has(id)) ids.set(id, g.node(raw.get(id))); return ids.get(id); };
    for (const way of osm.elements) {
      if (way.type !== 'way' || !walkable(way.tags || {}) || !Array.isArray(way.nodes)) continue;
      for (let i = 1; i < way.nodes.length; i++) {
        const a = way.nodes[i - 1], b = way.nodes[i];
        if (!raw.has(a) || !raw.has(b) || !segmentInside(raw.get(a), raw.get(b), poly)) continue;
        if (way.tags['oneway:foot'] === '-1') g.edge(node(b), node(a), true, true);
        else g.edge(node(a), node(b), true, way.tags['oneway:foot'] === 'yes');
      }
    }
    return g;
  }
  function attach(g, p, poly) {
    let best = null;
    for (const e of g.edges.filter(e => e.road)) {
      const snap = project(p, g.points[e.a], g.points[e.b]);
      if (snap.distance <= 60 && (!best || snap.distance < best.snap.distance) && segmentInside(p, snap.point, poly)) best = { e, snap };
    }
    if (!best) return null;
    // Preserve one-way foot edges. Connect through a new point on that edge.
    const { e, snap } = best;
    if (same(p, g.points[e.a])) return e.a;
    if (same(p, g.points[e.b])) return e.b;
    const q = same(snap.point, g.points[e.a]) ? e.a : same(snap.point, g.points[e.b]) ? e.b : g.node(snap.point);
    const reverse = g.adj[e.b].some(link => link.e === e && link.to === e.a);
    const middle=e.from+(e.to-e.from)*snap.t;
    g.edge(e.a,q,true,!reverse,{...e,to:middle});g.edge(q,e.b,true,!reverse,{...e,from:middle});
    if (same(p, snap.point)) return q;
    const id = g.node(p); g.edge(q, id, false);
    return id;
  }
  function geometryGraph(poly, anchors) {
    const g = new Graph(); [...anchors, ...poly].forEach(p => g.node(p));
    for (let i = 0; i < g.points.length; i++) for (let j = i + 1; j < g.points.length; j++) {
      const a=g.points[i],b=g.points[j];
      // Do not add a shortcut over an intermediate collinear anchor: reuse stays measurable.
      const containsAnchor=g.points.some((p,k)=>k!==i&&k!==j&&project(p,a,b).distance<.05&&distance(a,p)>.1&&distance(b,p)>.1);
      if(!containsAnchor && segmentInside(a,b,poly))g.edge(i,j);
    }
    return g;
  }
  function overlap(e,usage){
    const lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);
    return (usage.get(e.base)||[]).reduce((sum,[a,b])=>sum+Math.max(0,Math.min(hi,b)-Math.max(lo,a)),0)*e.baseMeters;
  }
  function remember(edges,usage){
    let repeated=0;
    for(const e of edges){
      repeated+=overlap(e,usage);
      const spans=[...(usage.get(e.base)||[]),[Math.min(e.from,e.to),Math.max(e.from,e.to)]].sort((a,b)=>a[0]-b[0]),merged=[];
      for(const span of spans){const last=merged.at(-1);if(last&&span[0]<=last[1]+1e-10)last[1]=Math.max(last[1],span[1]);else merged.push([...span]);}
      usage.set(e.base,merged);
    }
    return repeated;
  }
  function tour(g, ids, ordered, sorts, avoidRepeats = true) {
    const start = ids[0], checkpoints = ids.slice(1);
    const paths = new Map();
    for (const a of ids) for (const b of ids) {
      const p = g.shortest(a, b); if (!p) fail('路网不连通'); paths.set(a + ':' + b, p);
    }
    let order;
    if (ordered) order = checkpoints.map((id, i) => ({ id, sort: sorts[i], i })).sort((a, b) => a.sort - b.sort || a.i - b.i).map(x => x.i);
    else {
      order = []; const left = checkpoints.map((_, i) => i); let last = start;
      while (left.length) { left.sort((a, b) => paths.get(last + ':' + checkpoints[a]).length - paths.get(last + ':' + checkpoints[b]).length); const index = left.shift(); last = checkpoints[index]; order.push(index); }
      // Reinsert each checkpoint at the cheapest position; works with directed roads too.
      let improved = true, rounds = 0;
      const cost = xs => { const seq = [start, ...xs.map(i => checkpoints[i]), start]; return seq.slice(1).reduce((s, b, i) => s + paths.get(seq[i] + ':' + b).length, 0); };
      while (improved && rounds++ < 20) {
        improved = false;
        for (let i = 0; i < order.length && !improved; i++) for (let j = 0; j < order.length && !improved; j++) {
          const next = [...order], [p] = next.splice(i, 1); next.splice(j, 0, p);
          if (cost(next) + .01 < cost(order)) { order = next; improved = true; }
        }
      }
    }
    const visits=[start,...order.map(i=>checkpoints[i]),start],route=[g.points[start]],offsets=[],usage=new Map();
    let repeatedMeters=0;
    for (let i = 1; i < visits.length; i++) {
      const leg=avoidRepeats?g.shortest(visits[i-1],visits[i],-1,usage):paths.get(visits[i-1]+':'+visits[i]);
      repeatedMeters+=remember(leg.edges,usage);
      route.push(...leg.nodes.slice(1).map(id => g.points[id]));
      if (i < visits.length - 1) offsets.push({ checkpoint: order[i - 1], vertex: route.length - 1 });
    }
    return { route, offsets, repeatedMeters };
  }
  function roadLoop(g,start,target,random,avoidRepeats=true){
    let best=null;const edges=g.edges.filter(e=>e.road);
    const chosen=edges.length<=50?edges:Array.from({length:50},()=>edges[Math.floor(random()*edges.length)]);
    for(const e of chosen){
      const outward=g.shortest(start,e.a);if(!outward)continue;
      const usage=new Map();let repeatedMeters=remember(outward.edges,usage);
      repeatedMeters+=remember([e],usage);
      const back=g.shortest(e.b,start,e.id,avoidRepeats?usage:null);if(!back)continue;
      repeatedMeters+=remember(back.edges,usage);
      const route=[...outward.nodes,e.b,...back.nodes.slice(1)].map(id=>g.points[id]),len=length(route);
      if(len<80 || repeatedMeters/len>.49)continue;
      const score=Math.abs(len-target)+repeatedMeters*4;
      if(!best||score<best.score)best={route,offsets:[],score,repeatedMeters};
    }
    return best;
  }
  function geometricLoop(poly, start, target) {
    // Keep the random preflight start on the closed ring, avoiding a repeated access spoke.
    const [s,w,n,e]=bounds(poly),scale=Math.min(distance([s,w],[n,w]),distance([s,w],[s,e]));
    const radius=Math.min(target/(2*Math.PI),scale*.35);
    for(let k=0;k<9;k++)for(let direction=0;direction<12;direction++){
      const r=radius*Math.pow(.75,k),heading=direction*Math.PI/6;
      const center=[start[0]-r/R/RAD*Math.sin(heading),start[1]-r/R/RAD/Math.cos(start[0]*RAD)*Math.cos(heading)];
      const route=[start];
      for(let i=1;i<24;i++){
        const a=heading+i*Math.PI/12;
        route.push([center[0]+r/R/RAD*Math.sin(a),center[1]+r/R/RAD/Math.cos(start[0]*RAD)*Math.cos(a)]);
      }
      route.push(start);
      if(length(route)>=50&&route.slice(1).every((p,i)=>segmentInside(route[i],p,poly)))return{route,offsets:[],repeatedMeters:0};
    }
    // Concave/small region: choose three well separated interior anchors via visibility graph.
    const candidates = poly.map((p, i) => lerp(p, poly[(i + 1) % poly.length], .5)).filter(p => inside(p, poly));
    candidates.sort((a, b) => distance(start, b) - distance(start, a));
    const anchors = [start, ...candidates.slice(0, 3)], g = geometryGraph(poly, anchors);
    const result = tour(g, anchors.map((_, i) => i), false, []);
    if (length(result.route) < 20) fail('区域过小，无法生成合理的闭环');
    return { ...result, offsets: [] };
  }
  function plan(config, osm = null) {
    const random = config.random || Math.random, avoidRepeats=config.avoidRepeats!==false;
    const points = config.checkpoints || [], regions = config.regions || [];
    if (points.length > 24 || regions.length > 8) fail('最多支持 24 个打卡点和 8 个区域');
    if (!points.every(p => valid(p.position) && Number.isFinite(p.sort ?? 0))) fail('打卡点坐标或顺序无效');
    if (!valid(config.center)) fail('中心坐标无效');
    const target = config.targetMeters ?? 1000;
    if (!Number.isFinite(target) || target < 100 || target > 5000) fail('期望单圈长度需为 100–5000 米');
    regions.forEach(r => validatePolygon(r.polygon));
    const usable = regions.filter(r => r.available !== false && points.every(p => inside(p.position, r.polygon))
      && (!config.regionId || r.id === config.regionId) && (!config.start || inside(config.start, r.polygon)));
    if (regions.length && !usable.length) fail('没有同时容纳起点与所有打卡点的可用区域；不跨越互不相连的围栏');
    const region = usable.length ? usable[Math.floor(random() * usable.length)] : null;
    let poly;
    if (region) poly = region.polygon;
    else {
      const extent = Math.max(400, target / 3) / R / RAD;
      const c = config.center;
      poly = [[c[0] - extent, c[1] - extent / Math.cos(c[0] * RAD)], [c[0] - extent, c[1] + extent / Math.cos(c[0] * RAD)],
        [c[0] + extent, c[1] + extent / Math.cos(c[0] * RAD)], [c[0] + extent, c[1] - extent / Math.cos(c[0] * RAD)]];
      if (!points.every(p => inside(p.position, poly))) fail('无限定区域时，打卡点需在地图中心的校园范围内');
    }
    const g = roadGraph(osm, poly);
    let start = config.start;
    if (start && (!valid(start) || !inside(start, poly))) fail('固定起点不在区域内');
    if (!start) {
      const edges=g.edges.filter(e=>e.road), total=edges.reduce((sum,e)=>sum+e.weight,0);
      for(let attempt=0;attempt<100 && edges.length && !start;attempt++) {
        let offset=random()*total, edge=edges.at(-1);
        for(const e of edges) { offset-=e.weight; if(offset<=0){edge=e;break;} }
        const candidate=lerp(g.points[edge.a],g.points[edge.b],random());
        if(poly.every((a,i)=>project(candidate,a,poly[(i+1)%poly.length]).distance>=3)) start=candidate;
      }
      if(!start) start=randomPoint(poly,random);
    }
    let result = null, mode = 'geometry', reason = '未取得可用步行路网';
    if (g.edges.length) {
      const ids = [start, ...points.map(p => p.position)].map(p => attach(g, p, poly));
      if (ids.every(id => id !== null)) {
        try {
          result = points.length >= 2 ? tour(g, ids, config.ordered === true, points.map(p => p.sort ?? 0),avoidRepeats) : roadLoop(g, ids[0], target, random,avoidRepeats);
          if (result && points.length === 1) {
            const ringAnchors=result.route.slice(1,-1).filter((p,i,xs)=>i%Math.max(1,Math.ceil(xs.length/6))===0);
            const loopIds=ringAnchors.map(p=>attach(g,p,poly)).filter(id=>id!==null);
            const all=[ids[0],ids[1],...loopIds];
            const ringTour=tour(g,all,false,[],avoidRepeats);
            result={...ringTour,offsets:ringTour.offsets.filter(v=>v.checkpoint===0)};
          }
          if (result) mode = 'roads'; else reason = '步行路网没有可用闭环';
        } catch (e) { reason = e.message; }
      } else reason = '起点或打卡点距可用道路超过 60 米，或连接段越界';
    }
    if (!result) {
      if (points.length >= 2) result = tour(geometryGraph(poly, [start, ...points.map(p => p.position)]), [0, ...points.map((_, i) => i + 1)], config.ordered === true, points.map(p => p.sort ?? 0),avoidRepeats);
      else {
        result = geometricLoop(poly, start, target);
        if (points.length === 1) {
          const anchors=[start,points[0].position,...result.route.slice(1,-1).filter((p,i)=>i%4===0)];
          const ringTour=tour(geometryGraph(poly,anchors),anchors.map((_,i)=>i),false,[],avoidRepeats);
          result={...ringTour,offsets:ringTour.offsets.filter(v=>v.checkpoint===0)};
        }
      }
    }
    result.route = result.route.filter((p, i, xs) => !i || !same(p, xs[i - 1]));
    // Offsets are rebuilt after de-duplication; only ordered leg-end visits are promised.
    let scan = 0;
    const visitOrder = config.ordered ? points.map((p, i) => ({ p, i })).sort((a, b) => (a.p.sort ?? 0) - (b.p.sort ?? 0) || a.i - b.i).map(x => x.i)
      : result.offsets.map(x => x.checkpoint);
    result.offsets = visitOrder.map(checkpoint => {
      const vertex = result.route.findIndex((p, i) => i >= scan && same(p, points[checkpoint].position));
      if (vertex < 0) fail('规划没有依次到达打卡点'); scan = vertex;
      return { checkpoint, vertex };
    });
    const meters = length(result.route);
    if (meters < 20 || !same(result.route[0], result.route.at(-1)) || !result.route.slice(1).every((p, i) => segmentInside(result.route[i], p, poly))) fail('闭合或围栏检查失败');
    if (!points.every(cp => result.route.some(p => same(p, cp.position)))) fail('规划遗漏打卡点');
    const warnings = [];
    const repeatedMeters=measureRepeats(result.route);
    if (mode === 'geometry') warnings.push(reason + '；采用围栏内几何折线，未验证建筑、湖泊或通行条件');
    else warnings.push('道路来自 OSM；打卡点与道路之间最多 60 米连接段仍需人工确认可通行');
    if (points.some(p => p.nfcEnable)) warnings.push('存在 NFC 打卡点，路线经过坐标不等于完成 NFC 验证');
    if(repeatedMeters>5)warnings.push('约 '+Math.round(repeatedMeters)+' 米重复路段；围栏、顺序或道路连通性限制下无法完全消除');
    if (meters < target * .65) warnings.push('区域或路网限制使单圈较短，可循环跑多圈');
    return { schemaVersion: 1, coordinateSystem: 'WGS84', mode, regionId: region?.id ?? null, start, route: result.route,
      visits: result.offsets, checkpoints: points, meters, repeatedMeters, reuseRatio:repeatedMeters/meters,avoidRepeats,targetMeters: target, warnings, closed: true };
  }
  function measureRepeats(path){
    let repeated=0;
    for(let i=1;i<path.length;i++){
      const a=path[i-1],b=path[i],meters=distance(a,b);if(meters<.01)continue;
      const spans=[];
      for(let j=1;j<i;j++){
        const c=path[j-1],d=path[j];
        const scale=Math.cos(a[0]*RAD),dx=(b[1]-a[1])*scale,dy=b[0]-a[0],den=dx*dx+dy*dy;
        const t=p=>((p[1]-a[1])*scale*dx+(p[0]-a[0])*dy)/den;
        const tc=t(c),td=t(d),lo=Math.max(0,Math.min(tc,td)),hi=Math.min(1,Math.max(tc,td));
        if(hi<=lo)continue;
        const perpendicular=p=>Math.abs(((p[1]-a[1])*scale*dy-(p[0]-a[0])*dx)/Math.sqrt(den))*R*RAD;
        if(perpendicular(c)<.15&&perpendicular(d)<.15)spans.push([lo,hi]);
      }
      spans.sort((x,y)=>x[0]-y[0]);let covered=0,end=0;
      for(const [lo,hi]of spans){covered+=Math.max(0,hi-Math.max(end,lo));end=Math.max(end,hi);}
      repeated+=covered*meters;
    }
    return repeated;
  }
  // Reference implementation for the manual route regression tests.
  function manualPlan(anchors){
    if(!Array.isArray(anchors)||anchors.length<2||anchors.length>200||!anchors.every(valid))fail('手动路线需要 2–200 个有效点');
    const md=(a,b)=>{
      const lat1=a[0]*RAD,lat2=b[0]*RAD,x=Math.sin((lat2-lat1)/2),y=Math.sin((b[1]-a[1])*RAD/2);
      const h=x*x+Math.cos(lat1)*Math.cos(lat2)*y*y;return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
    };
    const clean=[];for(const p of anchors)if(!clean.length||md(clean.at(-1),p)>=.5)clean.push(p);
    if(clean.length>2&&md(clean[0],clean.at(-1))<.5)clean.pop();
    if(clean.length<2)fail('路线点重合');
    const tangent=(prev,at,next)=>{
      const scale=Math.cos(at[0]*RAD),x=(next[1]-prev[1])*scale,y=next[0]-prev[0],norm=Math.hypot(x,y);
      const magnitude=.5*Math.min(md(prev,at),md(at,next))/111195;
      return norm<1e-12?[0,0]:[y/norm*magnitude,x/norm*magnitude/scale];
    };
    const route=[],n=clean.length;
    for(let i=0;i<n;i++){
      const from=clean[i],to=clean[(i+1)%n],start=tangent(clean[(i+n-1)%n],from,to),end=tangent(from,to,clean[(i+2)%n]);
      const count=Math.max(8,Math.min(250,Math.ceil(md(from,to)/1.5)));
      for(let k=0;k<count;k++){const t=k/count,t2=t*t,t3=t2*t,a=2*t3-3*t2+1,b=t3-2*t2+t,c=-2*t3+3*t2,d=t3-t2;
        route.push([a*from[0]+b*start[0]+c*to[0]+d*end[0],a*from[1]+b*start[1]+c*to[1]+d*end[1]]);}
    }
    route.push(clean[0]);const meters=route.slice(1).reduce((sum,p,i)=>sum+md(route[i],p),0);if(meters<30||meters>20000)fail('手动闭环长度需要 30–20000 米');
    return{schemaVersion:1,coordinateSystem:'WGS84',mode:'manual',start:clean[0],route,visits:[],checkpoints:[],meters,warnings:[],closed:true};
  }
  // Common mainland-China map conversion; a numerical inverse, not a display offset.
  function wgsToGcj(p) {
    const [lat, lng] = p; if (lng < 72.004 || lng > 137.8347 || lat < .8293 || lat > 55.8271) return [...p];
    const x = lng - 105, y = lat - 35, pi = Math.PI;
    let dlat = -100 + 2*x + 3*y + .2*y*y + .1*x*y + .2*Math.sqrt(Math.abs(x));
    dlat += (20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3 + (20*Math.sin(y*pi)+40*Math.sin(y/3*pi))*2/3 + (160*Math.sin(y/12*pi)+320*Math.sin(y*pi/30))*2/3;
    let dlng = 300 + x + 2*y + .1*x*x + .1*x*y + .1*Math.sqrt(Math.abs(x));
    dlng += (20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3 + (20*Math.sin(x*pi)+40*Math.sin(x/3*pi))*2/3 + (150*Math.sin(x/12*pi)+300*Math.sin(x/30*pi))*2/3;
    const rad = lat*RAD, magic = 1-.006693421622965943*Math.sin(rad)**2, sqrt = Math.sqrt(magic);
    return [lat+dlat*180/((6378245*(1-.006693421622965943)/(magic*sqrt))*pi), lng+dlng*180/(6378245/sqrt*Math.cos(rad)*pi)];
  }
  function gcjToWgs(p) {
    let q = [...p]; for (let i = 0; i < 8; i++) { const f = wgsToGcj(q); q = [q[0]+p[0]-f[0], q[1]+p[1]-f[1]]; } return q;
  }
  const api = { plan, inside, segmentInside, validatePolygon, distance, length, bounds, seeded, randomPoint, roadGraph, walkable, measureRepeats, manualPlan, wgsToGcj, gcjToWgs };
  if (typeof module !== 'undefined') module.exports = api; else root.Planner = api;
})(typeof window !== 'undefined' ? window : globalThis);
