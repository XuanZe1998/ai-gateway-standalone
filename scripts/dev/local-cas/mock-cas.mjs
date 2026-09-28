// 文件说明：mock-cas：辅助脚本与自动化逻辑。
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';

export const portalOrigin = 'https://localhost:39443';
export const appOrigin = 'https://localhost:39444';
const entry = `${appOrigin}/api/auth/cas/login?target=/admin/auth/callback`;
const sessions = new Map();
const tickets = new Map();
const nonce = () => randomBytes(32).toString('base64url');
const escape = value => value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const cookie = value => `CAS_DEMO_TGC=${value}; Path=/; HttpOnly; Secure; SameSite=Lax${value ? '' : '; Max-Age=0'}`;
const cookieValue = req => (req.headers.cookie ?? '').split(';').map(v=>v.trim()).find(v=>v.startsWith('CAS_DEMO_TGC='))?.slice(13);
const active = req => (sessions.get(cookieValue(req)) ?? 0) > Date.now();

function validService(value) {
  try {
    const url = new URL(value);
    return url.origin === appOrigin && url.pathname === '/api/auth/cas/callback'
      && !url.hash && !url.username && !url.password
      && [...url.searchParams.keys()].length === 1
      && /^[A-Za-z0-9_-]{32,128}$/.test(url.searchParams.get('state') ?? '');
  } catch { return false; }
}
function send(res, status, body, type = 'text/html; charset=utf-8', headers = {}) {
  res.writeHead(status, {'Content-Type': type, 'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    'Referrer-Policy':'no-referrer', ...headers});
  res.end(body);
}
const redirect = (res, url, headers = {}) => send(res, 302, '', 'text/plain', {Location:url, ...headers});
function page(res, loggedIn, error = '') {
  // Native form POSTs use Origin: null under no-referrer. Preserve the origin
  // for same-origin forms; CAS redirects keep the default no-referrer policy.
  send(res, 200, `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>校园门户 · 本地模拟</title>
  <style>body{margin:0;background:#f3f6fb;color:#16243d;font:16px/1.8 system-ui}main{max-width:760px;margin:9vh auto;padding:40px;background:white;border:1px solid #dce4ef;border-radius:20px}small{color:#866200;background:#fff3ca;padding:6px 12px;border-radius:8px}h1{font-size:30px}p{color:#516176}a,button{display:inline-block;background:#185ce0;color:white;border:0;padding:13px 22px;border-radius:9px;text-decoration:none;font:inherit;cursor:pointer}.card{background:#f3f6fb;padding:20px;border-radius:12px;margin:22px 0}footer{font-size:13px;color:#768297;margin-top:28px}</style>
  <main><small>本地模拟 · 不连接学校真实认证系统</small><h1>校园统一门户</h1>
  ${error ? `<p>${escape(error)}</p>` : ''}
  ${loggedIn ? `<div class="card"><b>张老师（模拟） · 已登录门户</b><br>计算机学院（模拟）<br>统一账号：mock_teacher01<br>平台账号：MOCK_T1001</div><p>已有门户登录会话。点击下方应用，经 CAS 验证后免密进入教师页面。</p><a href="${entry}">进入 AI 算力平台</a>` : `<p>演示从“老师已登录学校门户”开始。下方按钮仅建立本地模拟登录会话，不需要学校账号或密码。</p><form method="post" action="/demo/login"><button>进入已登录老师场景</button></form>`}
  <footer>CAS 2.0 · HTTPS · 一次性票据 · 本项目独立 Session<br>停止/重置请使用项目附带的 PowerShell 脚本。</footer></main></html>`, 'text/html; charset=utf-8', {'Referrer-Policy':'same-origin'});
}
const failure = (res, code) => send(res, 200, `<cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas"><cas:authenticationFailure code="${code}">Local demo validation failed</cas:authenticationFailure></cas:serviceResponse>`, 'application/xml');

const server = https.createServer({key:readFileSync(process.env.DEMO_TLS_KEY),cert:readFileSync(process.env.DEMO_TLS_CERT)}, (req,res) => {
  const url = new URL(req.url, portalOrigin);
  if (req.headers.host !== 'localhost:39443') return send(res, 400, 'Invalid host');
  if (req.method === 'GET' && url.pathname === '/health') return send(res,200,'{"status":"UP"}','application/json');
  if (req.method === 'GET' && url.pathname === '/') return page(res,active(req));
  if (req.method === 'POST' && url.pathname === '/demo/login') {
    if (req.headers.origin !== portalOrigin) return send(res,403,'Invalid origin');
    const id=nonce(); sessions.set(id,Date.now()+30*60*1000);
    return redirect(res,'/',{'Set-Cookie':cookie(id)});
  }
  if (req.method === 'GET' && url.pathname === '/cas/login') {
    const service=url.searchParams.get('service');
    if (!validService(service)) return send(res,400,'Unregistered service');
    if (!active(req)) return page(res,false,'当前没有有效的模拟门户会话，请先建立已登录老师场景，然后从门户重新进入应用。');
    const ticket=`ST-${nonce()}`; tickets.set(ticket,{service,expires:Date.now()+60_000});
    const target=new URL(service); target.searchParams.set('ticket',ticket);
    return redirect(res,target.href);
  }
  if (req.method === 'GET' && url.pathname === '/cas/serviceValidate') {
    const ticket=url.searchParams.get('ticket'); const record=tickets.get(ticket);
    tickets.delete(ticket); // Even a service mismatch consumes the ticket.
    if (!record || record.expires < Date.now()) return failure(res,'INVALID_TICKET');
    if (record.service !== url.searchParams.get('service') || !validService(record.service)) return failure(res,'INVALID_SERVICE');
    return send(res,200,`<cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas"><cas:authenticationSuccess><cas:user>mock_teacher01</cas:user><cas:attributes><cas:account>mock_teacher01</cas:account><cas:localAccount>MOCK_T1001</cas:localAccount><cas:typeCode>MOCK_TEACHER</cas:typeCode><cas:typeName>教师（模拟）</cas:typeName><cas:name>张老师（模拟）</cas:name><cas:deptName>计算机学院（模拟）</cas:deptName></cas:attributes></cas:authenticationSuccess></cas:serviceResponse>`,'application/xml; charset=utf-8');
  }
  if (req.method === 'GET' && url.pathname === '/cas/logout') {
    const service=url.searchParams.get('service');
    if (service && service !== `${appOrigin}/admin/login`) return send(res,400,'Unregistered logout target');
    sessions.delete(cookieValue(req));
    return redirect(res, service || '/', {'Set-Cookie':cookie('')});
  }
  send(res,404,'Not found');
});
setInterval(() => {
  for (const [id,expiry] of sessions) if (expiry < Date.now()) sessions.delete(id);
  for (const [id,record] of tickets) if (record.expires < Date.now()) tickets.delete(id);
},60_000).unref();
server.listen(39443,'127.0.0.1',()=>console.log('Local mock portal/CAS listening on https://localhost:39443'));
