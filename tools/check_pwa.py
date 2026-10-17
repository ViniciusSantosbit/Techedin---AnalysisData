"""Check installation assets, an actual service worker and offline privacy on localhost."""
import functools,http.server,json,threading
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
root=Path(__file__).resolve().parents[1]
class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map={**http.server.SimpleHTTPRequestHandler.extensions_map,'.webmanifest':'application/manifest+json'}
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(root)))
threading.Thread(target=server.serve_forever,daemon=True).start();origin=f'http://localhost:{server.server_port}/frontend/'
try:
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(service_workers='allow')
  context.route('**/api/**',lambda route:route.fulfill(content_type='application/json',body=json.dumps({'authenticated':False,'account':None} if '/auth/session' in route.request.url else {'dataKind':'real','jobs':[],'sources':[]})))
  context.route('https://cdn.jsdelivr.net/**',lambda route:route.abort())
  page=context.new_page();page.goto(origin,wait_until='domcontentloaded')
  page.evaluate('async()=>{await navigator.serviceWorker.ready;}')
  page.wait_for_function('navigator.serviceWorker.controller !== null')
  page.locator('#install-app').click();expect(page.locator('#install-help')).to_be_visible()
  expect(page.locator('#install-help')).to_contain_text('Tela de Início');page.locator('#install-help-close').click()
  urls=page.evaluate('async()=>{const keys=await caches.keys();return (await Promise.all(keys.map(async k=>(await (await caches.open(k)).keys()).map(r=>r.url)))).flat();}')
  assert len(urls)==8,urls
  assert not any('/api/' in url or 'index.html' in url for url in urls),urls
  context.unroute('**/api/**')
  context.set_offline(True);page.goto(origin+'offline-check')
  expect(page.locator('h1')).to_have_text('Vamos reconectar?')
  assert page.evaluate("async()=>{try{await fetch('/api/account/resume');return false;}catch{return true;}}")
  browser.close();print('PWA validado: manifest/ícones, service worker real, instruções de instalação, tela offline e ausência de cache privado.')
finally:server.shutdown()
