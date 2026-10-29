(async()=>{
 if(location.origin!=='http://3.110.124.52')throw new Error('wrong environment');
 const results=[];
 for(const accountId of [1720,1737,1709,2187]){
  const response=await window.__perf2Api('/api/accounts/'+accountId+'/online','POST');
  results.push({observedAt:new Date().toISOString(),accountId,response});
 }
 return results;
})()
