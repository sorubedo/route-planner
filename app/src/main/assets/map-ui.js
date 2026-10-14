/* Android map bridge. Coordinates in the editor are WGS84. */
(function(root){
  'use strict';
  const P=root.Planner;
  class MapUI {
    constructor(){
      this.handler=null;
      document.body.classList.add('native-map');
      root.nativeMapClick=(lat,lng)=>this.handler?.(P.gcjToWgs([lat,lng]));
    }
    getCenter(){const p=P.gcjToWgs(JSON.parse(root.Native.center()));return{lat:p[0],lng:p[1]};}
    setView(p,zoom=16){root.Native.camera(JSON.stringify([P.wgsToGcj(p)]),zoom);}
    fitBounds(coords){if(coords.length)root.Native.camera(JSON.stringify(coords.map(P.wgsToGcj)),17);}
    onClick(fn){this.handler=fn;}
    draw(frame){
      const convert=path=>(path||[]).map(P.wgsToGcj);
      root.Native.draw(JSON.stringify({...frame,regions:frame.regions.map(r=>({...r,polygon:convert(r.polygon)})),
        draft:convert(frame.draft),checkpoints:frame.checkpoints.map(p=>({...p,position:P.wgsToGcj(p.position)})),
        route:convert(frame.route),segments:(frame.segments||[]).map(s=>({...s,route:convert(s.route)})),...(frame.start?{start:P.wgsToGcj(frame.start)}:{})}));
    }
    runner(p){const q=p?P.wgsToGcj(p):[0,0];root.Native.runner(q[0],q[1],!!p);}
  }
  root.MapUI=MapUI;
})(window);
