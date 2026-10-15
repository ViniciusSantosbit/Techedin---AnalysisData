import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveApiBase } from '../js/api-config.mjs';

test('mantém host local para preservar cookies entre frontend e API', () => {
    assert.equal(resolveApiBase({ href: 'http://127.0.0.1:5500/frontend/' }), 'http://127.0.0.1:8080');
    assert.equal(resolveApiBase({ href: 'http://localhost:8081/' }), 'http://localhost:8080');
    assert.equal(resolveApiBase({ href: 'http://[::1]:5500/' }), 'http://[::1]:8080');
});
test('respeita configuração explícita e usa origem do site em produção', () => {
    assert.equal(resolveApiBase({ href: 'https://techedin.example/' }), 'https://techedin.example');
    assert.equal(resolveApiBase({ href: 'http://127.0.0.1:5500/' }, 'https://api.example/'), 'https://api.example');
});
