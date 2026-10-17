import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../js/app-viewport.js', import.meta.url), 'utf8');
function setup({ installed = false, ios = false, touch = true } = {}) {
    const events = {}, windowEvents = {}, classes = new Set();
    let content = 'width=device-width, initial-scale=1.0';
    const original = content;
    const modes = {};
    const document = {
        querySelector: () => ({ getAttribute: () => content, setAttribute: (_, value) => { content = value; } }),
        documentElement: { classList: { toggle: (name, active) => active ? classes.add(name) : classes.delete(name) } },
        addEventListener: (name, callback) => { events[name] = callback; }
    };
    const window = {
        matchMedia: query => modes[query] = {
            matches: query.includes('display-mode') ? installed : touch,
            addEventListener: (_, callback) => { modes[query].change = callback; }
        },
        addEventListener: (name, callback) => { windowEvents[name] = callback; }
    };
    vm.runInNewContext(source, { document, window, navigator: { standalone: ios, maxTouchPoints: touch ? 1 : 0 } });
    const gesture = (name, touches = 2) => {
        let prevented = false;
        events[name]({ cancelable: true, touches: Array(touches), preventDefault: () => { prevented = true; } });
        return prevented;
    };
    return { get content() { return content; }, original, classes, gesture, modes, windowEvents };
}

test('ordinary mobile browser preserves viewport and gestures, even after installation', () => {
    const app = setup();
    app.windowEvents.appinstalled();
    assert.equal(app.content, app.original);
    assert.equal(app.classes.size, 0);
    assert.equal(app.gesture('gesturestart'), false);
    assert.equal(app.gesture('touchmove'), false);
});
test('installed Android and iOS touch apps restrict zoom while retaining single-finger scrolling', () => {
    for (const options of [{ installed: true }, { ios: true }]) {
        const app = setup(options);
        assert.ok(app.content.includes('maximum-scale=1, user-scalable=no'));
        assert.ok(app.classes.has('pwa-touch-app'));
        assert.equal(app.gesture('gesturestart'), true);
        assert.equal(app.gesture('gesturechange'), true);
        assert.equal(app.gesture('touchmove'), true);
        assert.equal(app.gesture('touchmove', 1), false);
    }
});
test('desktop installed apps preserve zoom and leaving standalone restores the viewport', () => {
    assert.equal(setup({ installed: true, touch: false }).classes.size, 0);
    const app = setup({ installed: true });
    const mode = app.modes['(display-mode: standalone)'];
    mode.matches = false; mode.change();
    assert.equal(app.content, app.original);
    assert.equal(app.classes.size, 0);
    assert.equal(app.gesture('touchmove'), false);
});
