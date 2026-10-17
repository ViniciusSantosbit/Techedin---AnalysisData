import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../sw.js',import.meta.url),'utf8');
function worker(){
 const handlers={},notifications=[],opened=[];
 const self={addEventListener:(name,fn)=>handlers[name]=fn,location:{origin:'https://techedin.vercel.app'},registration:{scope:'https://techedin.vercel.app/',showNotification:async(title,options)=>notifications.push({title,options})},clients:{matchAll:async()=>[],openWindow:async url=>opened.push(url)}};
 vm.runInNewContext(source,{self,URL,caches:{},fetch:()=>{throw Error('Unexpected network')}});
 return {handlers,notifications,opened};
}
test('manifest has install identity and real PNG icons',()=>{
 const manifest=JSON.parse(fs.readFileSync(new URL('../manifest.webmanifest',import.meta.url),'utf8'));
 assert.equal(manifest.display,'standalone');assert.equal(manifest.id,'./');assert.equal(manifest.scope,'./');assert.ok(manifest.start_url.startsWith('./'));
 for(const icon of manifest.icons){const png=fs.readFileSync(new URL('../'+icon.src,import.meta.url));assert.equal(png.readUInt32BE(16),Number(icon.sizes.split('x')[0]));}
});
test('service worker never intercepts private API or writes',()=>{
 const {handlers}=worker();
 for(const request of [{method:'GET',url:'https://techedin.vercel.app/api/account/resume',mode:'cors'},{method:'POST',url:'https://techedin.vercel.app/api/account/career-chat',mode:'cors'}])
  handlers.fetch({request,respondWith:()=>assert.fail('Private traffic must not be cached')});
});
test('push always displays a generic banner and opens only our suggestions',async()=>{
 const {handlers,notifications,opened}=worker();let pending;
 handlers.push({data:{json:()=>({url:'https://evil.example',cv:'Private CV'})},waitUntil:p=>pending=p});await pending;
 assert.equal(notifications.length,1);assert.equal(notifications[0].options.tag,'techedin-jobs');
 assert.equal(JSON.stringify(notifications).includes('Private CV'),false);
 handlers.notificationclick({notification:{close(){}},waitUntil:p=>pending=p});await pending;
 assert.deepEqual(opened,['https://techedin.vercel.app/#recommendations-section']);
});
