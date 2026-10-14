/* Native Android owns the shared Java planner, deadlines and Tencent SDK requests. */
(function(root){
  'use strict';
  let sequence=0;
  const pending=new Map();
  const abortError=()=>Object.assign(new Error('道路规划已取消'),{name:'AbortError'});
  function nativePlanReply(id,ok,value){
    const request=pending.get(id);if(!request)return;
    if(!ok)return request.reject(new Error(value));
    try{request.resolve(JSON.parse(value));}catch(error){request.reject(error);}
  }
  function nativePlanProgress(id,attempt,completed,total){pending.get(id)?.onProgress?.({attempt,completed,total});}
  async function plan(config,options={}){
    const native=options.native??root.Native;
     if(!native)throw new Error('请在 Android 应用中使用腾讯步行规划');
    if(options.signal?.aborted)throw abortError();
    const id=++sequence;
    let abort;
    try{return await new Promise((resolve,reject)=>{
      abort=()=>reject(abortError());
      pending.set(id,{resolve,reject,onProgress:options.onProgress});
      options.signal?.addEventListener('abort',abort,{once:true});
      native.plan(JSON.stringify(config),id,options.roads!==false);
    });}finally{
      pending.delete(id);options.signal?.removeEventListener('abort',abort);native.cancelPlan(id);
    }
  }
  const api={plan,nativePlanReply,nativePlanProgress};
  if(typeof module!=='undefined')module.exports=api;
  else{root.RoadClient=api;root.nativePlanReply=nativePlanReply;root.nativePlanProgress=nativePlanProgress;}
})(typeof window!=='undefined'?window:globalThis);
