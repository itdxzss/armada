(() => {
 if (location.origin !== 'http://3.110.124.52') throw new Error('wrong environment');
 window.__perf2Api = async (path, method='GET', body) => {
   if (location.origin !== 'http://3.110.124.52' || !path.startsWith('/api/')) throw new Error('wrong API target');
   const raw=document.cookie.split('; ').find(x=>x.startsWith('authorized-token='));
   const session=raw ? JSON.parse(decodeURIComponent(raw.slice(raw.indexOf('=')+1))) : null;
   if(!session?.accessToken) throw new Error('existing login required');
   const headers={Authorization:'Bearer '+session.accessToken};
   if(body!==undefined && !(body instanceof FormData)) headers['Content-Type']='application/json';
   const response=await fetch(path,{method,headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
   return {httpStatus:response.status,...await response.json()};
 };
 return {origin:location.origin,helperReady:true,tokenExported:false};
})()
