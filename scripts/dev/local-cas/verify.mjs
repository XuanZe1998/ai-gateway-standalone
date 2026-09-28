// Protocol/integration checks; no model inference, no production identities.
import https from 'node:https';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';

const runtime = fileURLToPath(new URL('../../../.local-cas-demo/', import.meta.url));
const agent = new https.Agent({ca:readFileSync(path.join(runtime,'ca.crt'))});
const portal = 'https://localhost:39443';
const app = 'https://localhost:39444';
const checks=[];
function browser() {
  const cookies=new Map();
  const request = async function(url, method='GET', headers={}) {
    const target=new URL(url);
    assert.ok([portal,app].includes(target.origin),'Test cannot access non-demo origins');
    return await new Promise((resolve,reject)=>{
      const req=https.request(target,{agent,method,headers:{Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; '),...headers}},res=>{
        for(const item of res.headers['set-cookie']??[]) {
          const pair=item.split(';')[0]; const index=pair.indexOf('=');
          if(/max-age=0/i.test(item)) cookies.delete(pair.slice(0,index));
          else cookies.set(pair.slice(0,index),pair.slice(index+1));
        }
        let body=''; res.setEncoding('utf8');res.on('data',chunk=>body+=chunk);
        res.on('end',()=>resolve({status:res.statusCode,body,location:res.headers.location,headers:res.headers}));
      });
      req.setTimeout(12000,()=>req.destroy(new Error('Request timed out')));
      req.on('error',reject);req.end();
    });
  };
  request.cookie = name => cookies.get(name);
  return request;
}
async function check(name,fn){await fn();checks.push(name);console.log(`PASS ${name}`);}
async function establish(request){assert.equal((await request(`${portal}/demo/login`,'POST',{Origin:portal})).status,302);}
async function begin(request){
  const login=await request(`${app}/api/auth/cas/login?target=/admin/auth/callback`);
  assert.equal(login.status,302);
  const cas=await request(login.location);
  assert.equal(cas.status,302);
  return cas.location;
}
const request=browser();
try {
  await check('Portal form preserves same-origin POST origin without accepting null or missing origins',async()=>{
    const page=await request(portal);
    assert.equal(page.status,200);
    assert.equal(page.headers['referrer-policy'],'same-origin');
    assert.match(page.body,/<form method="post" action="\/demo\/login">/);
    assert.equal((await request(`${portal}/demo/login`,'POST',{Origin:'null'})).status,403);
    assert.equal((await request(`${portal}/demo/login`,'POST')).status,403);
    const login=await request(`${portal}/demo/login`,'POST',{Origin:portal});
    assert.equal(login.status,302);
    assert.equal(login.headers['referrer-policy'],'no-referrer');
    await request(`${portal}/cas/logout`);
    const service=`${app}/api/auth/cas/callback?state=${'A'.repeat(43)}`;
    const casPage=await request(`${portal}/cas/login?service=${encodeURIComponent(service)}`);
    assert.equal(casPage.headers['referrer-policy'],'same-origin');
  });
  await check('SPA deep link and its built JavaScript are served',async()=>{
    const page=await request(`${app}/admin/teacher`);
    assert.equal(page.status,200);
    assert.match(page.body,/id="app"/);
    const script=page.body.match(/<script[^>]+src="([^"]+)"/);
    assert.ok(script,'Expected built SPA script');
    const asset=await request(new URL(script[1],app).href);
    assert.equal(asset.status,200);
    assert.match(asset.headers['content-type'],/javascript/);
  });
  await check('Portal session is independent of application session',async()=>{
    await establish(request);
    assert.match((await request(portal)).body,/张老师（模拟） · 已登录门户/);
    assert.equal((await request(`${app}/api/auth/session`)).status,401);
  });
  await check('CAS redirect and real gateway callback create a teacher session',async()=>{
    const callback=await begin(request);
    const response=await request(callback);
    assert.equal(response.status,302, response.body);
    assert.equal(response.location,'/admin/auth/callback');
  });
  let session;
  await check('Session returns exact teacher identity without ADMIN',async()=>{
    const response=await request(`${app}/api/auth/session`);
    assert.equal(response.status,200,response.body);
    session=JSON.parse(response.body).data;
    assert.equal(session.account,'MOCK_T1001');
    assert.equal(session.displayName,'张老师（模拟）');
    assert.equal(session.departmentName,'计算机学院（模拟）');
    assert.deepEqual([...session.roles].sort(),['TEACHER','USER']);
    assert.ok(session.csrf.token);
  });
  await check('Teacher API works and ADMIN API is denied',async()=>{
    const devices=await request(`${app}/api/teacher/devices`);
    assert.equal(devices.status,200,devices.body);
    assert.ok(Array.isArray(JSON.parse(devices.body).data));
    assert.equal((await request(`${app}/api/admin/quotas`)).status,403);
  });
  await check('Repeated session reads preserve authentication',async()=>{
    assert.equal((await request(`${app}/api/auth/session`)).status,200);
  });
  await check('Logout requires CSRF then invalidates both application and CAS sessions',async()=>{
    assert.equal((await request(`${app}/api/auth/cas/logout`,'POST')).status,403);
    // Match Axios in the actual SPA: it sends the raw XSRF-TOKEN cookie as a header.
    const response=await request(`${app}/api/auth/cas/logout`,'POST',{'X-XSRF-TOKEN':decodeURIComponent(request.cookie('XSRF-TOKEN'))});
    assert.equal(response.status,200,response.body);
    assert.equal((await request(JSON.parse(response.body).data.logoutUrl)).status,302);
    assert.equal((await request(`${app}/api/auth/session`)).status,401);
    const login=await request(`${app}/api/auth/cas/login`);
    const cas=await request(login.location);
    assert.equal(cas.status,200);
    assert.match(cas.body,/当前没有有效的模拟门户会话/);
  });
  await check('CAS rejects unregistered callbacks and cross-origin demo login',async()=>{
    assert.equal((await request(`${portal}/cas/login?service=${encodeURIComponent('https://example.invalid/callback')}`)).status,400);
    assert.equal((await request(`${portal}/demo/login`,'POST',{Origin:'https://example.invalid'})).status,403);
    assert.equal((await request(`${portal}/cas/logout?service=https://example.invalid`)).status,400);
  });
  await check('CAS ticket is bound to full service and consumed once',async()=>{
    const isolated=browser();await establish(isolated);
    const service=`${app}/api/auth/cas/callback?state=${'A'.repeat(43)}`;
    const issue=await isolated(`${portal}/cas/login?service=${encodeURIComponent(service)}`);
    const ticket=new URL(issue.location).searchParams.get('ticket');
    const validate=`${portal}/cas/serviceValidate?service=${encodeURIComponent(service)}&ticket=${ticket}`;
    assert.match((await isolated(validate)).body,/authenticationSuccess/);
    assert.match((await isolated(validate)).body,/INVALID_TICKET/);
    const second=await isolated(`${portal}/cas/login?service=${encodeURIComponent(service)}`);
    const secondTicket=new URL(second.location).searchParams.get('ticket');
    assert.match((await isolated(`${portal}/cas/serviceValidate?service=${encodeURIComponent(service+'X')}&ticket=${secondTicket}`)).body,/INVALID_SERVICE/);
  });
  await check('Gateway rejects modified state without creating a session',async()=>{
    const isolated=browser();await establish(isolated);
    const callback=new URL(await begin(isolated));callback.searchParams.set('state','tampered');
    const response=await isolated(callback.href);
    assert.ok(response.status>=400,response.body);
    assert.equal((await isolated(`${app}/api/auth/session`)).status,401);
  });
  await check('Gateway rejects invalid ticket and repeated callback',async()=>{
    const isolated=browser();await establish(isolated);
    const callback=new URL(await begin(isolated));callback.searchParams.set('ticket','ST-not-valid');
    assert.ok((await isolated(callback.href)).status>=400);
    assert.ok((await isolated(callback.href)).status>=400);
    assert.equal((await isolated(`${app}/api/auth/session`)).status,401);
  });
  writeFileSync(path.join(runtime,'verification.json'),JSON.stringify({at:new Date().toISOString(),scope:'protocol-and-http',browserUi:'not-verified',passed:true,checks},null,2));
  console.log(`All ${checks.length} checks passed. Browser UI verification is separate.`);
} catch(error) {
  writeFileSync(path.join(runtime,'verification.json'),JSON.stringify({at:new Date().toISOString(),scope:'protocol-and-http',browserUi:'not-verified',passed:false,checks,error:String(error)},null,2));
  throw error;
} finally {agent.destroy();}
