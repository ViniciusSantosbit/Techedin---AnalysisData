export function resolveApiBase(location, override) {
    if (override) return override.replace(/\/$/, '');
    const url = new URL(location.href);
    if (['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
        url.port = '8080';
        return url.origin;
    }
    return url.origin;
}
