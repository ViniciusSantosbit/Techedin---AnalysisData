export const registration = window.isSecureContext && 'serviceWorker' in navigator
    ? navigator.serviceWorker.register('./sw.js', {updateViaCache:'none'}).catch(() => null) : Promise.resolve(null);
let installPrompt;
const installButton = document.getElementById('install-app'), help = document.getElementById('install-help');
function standalone() { return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true; }
if (standalone()) installButton.hidden = true;
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); installPrompt = event; });
window.addEventListener('appinstalled', () => { installPrompt = null; installButton.hidden = true; help.close(); });
installButton.addEventListener('click', async () => { if (installPrompt) { await installPrompt.prompt(); installPrompt = null; } else help.showModal(); });
document.getElementById('install-help-close').addEventListener('click', () => help.close());
export function pushSupport() {
    if (!window.isSecureContext) return 'Os alertas precisam de HTTPS. No computador, localhost é permitido para desenvolvimento.';
    const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
    if (ios && !standalone()) return 'No iPhone/iPad, adicione o app à Tela de Início e abra pelo ícone para ativar os alertas (iOS/iPadOS 16.4 ou posterior).';
    if (!('serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window)) return 'Este navegador não oferece notificações push. Use um navegador compatível.';
    return '';
}
export function publicKeyBytes(value) { return Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')), char => char.charCodeAt(0)); }
