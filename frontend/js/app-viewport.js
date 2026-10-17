/* Installed touch app only: ordinary browser tabs retain their zoom behavior. */
(() => {
    const viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) return;
    const original = viewport.getAttribute('content');
    const displayMode = window.matchMedia('(display-mode: standalone)');
    const touchPointer = window.matchMedia('(any-pointer: coarse)');
    let locked = false;
    function sync() {
        const installed = displayMode.matches || navigator.standalone === true;
        const touch = navigator.maxTouchPoints > 0 || touchPointer.matches;
        locked = installed && touch;
        document.documentElement.classList.toggle('pwa-touch-app', locked);
        viewport.setAttribute('content', locked
            ? original + ', maximum-scale=1, user-scalable=no'
            : original);
    }
    sync();
    displayMode.addEventListener?.('change', sync);
    touchPointer.addEventListener?.('change', sync);
    window.addEventListener('pageshow', sync);
    window.addEventListener('appinstalled', sync);
    const preventZoom = event => {
        if (locked && event.cancelable) event.preventDefault();
    };
    document.addEventListener('gesturestart', preventZoom, { passive: false });
    document.addEventListener('gesturechange', preventZoom, { passive: false });
    document.addEventListener('touchmove', event => {
        if (event.touches.length > 1) preventZoom(event);
    }, { passive: false });
})();
