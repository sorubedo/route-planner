'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');
const client=require('../src/main/assets/road-client.js');
test('native result and segment progress reach the request',async()=>{
  const progress=[],cancelled=[];
  const native={plan(config,id,roads){assert.equal(roads,true);assert.equal(JSON.parse(config).targetMeters,500);client.nativePlanProgress(id,2,3,4);client.nativePlanReply(id,true,JSON.stringify({mode:'mixed',segments:[{mode:'roads'},{mode:'geometry'}]}));},cancelPlan(id){cancelled.push(id);}};
  const result=await client.plan({targetMeters:500},{native,onProgress:p=>progress.push(p)});
  assert.equal(result.mode,'mixed');assert.deepEqual(progress,[{attempt:2,completed:3,total:4}]);assert.equal(cancelled.length,1);
});
test('editing cancels the native job and late responses cannot publish',async()=>{
  const controller=new AbortController();let id,cancels=0;
  const native={plan(config,request){id=request;},cancelPlan(){cancels++;}};
  const promise=client.plan({},{native,signal:controller.signal});controller.abort();
  await assert.rejects(promise,{name:'AbortError'});client.nativePlanReply(id,true,'{"mode":"roads"}');assert.equal(cancels,1);
});
test('native failures reject and geometry-only option is forwarded',async()=>{
  const native={plan(config,id,roads){assert.equal(roads,false);client.nativePlanReply(id,false,'围栏无效');},cancelPlan(){}};
  await assert.rejects(client.plan({},{native,roads:false}),/围栏无效/);
});
test('an already cancelled request never starts native planning',async()=>{
  const controller=new AbortController();controller.abort();
  await assert.rejects(client.plan({},{signal:controller.signal,native:{plan(){assert.fail('should not start');}}}),{name:'AbortError'});
});
