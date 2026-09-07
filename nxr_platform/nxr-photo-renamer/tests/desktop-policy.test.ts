import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { assetPath, isAppUrl, CSP } = createRequire(import.meta.url)('../desktop/policy.cjs');

test('desktop serves only its local origin and safe asset paths', () => {
  assert.equal(assetPath('nxr://app/'), 'index.html');
  assert.equal(assetPath('nxr://app/assets/worker.js'), 'assets/worker.js');
  for (const value of ['https://app/index.html', 'nxr://other/index.html', 'nxr://user@app/index.html',
    'nxr://app:80/index.html', 'nxr://app/assets/%2e%2e%2fpackage.json', 'nxr://app/%5csecret.js',
    'nxr://app/.PhoenixBrain', 'nxr://app/%00.js', 'nxr://app/bad%GG.js', 'nxr://app/photo.jpg']) {
    assert.equal(assetPath(value), null, value);
  }
  assert.equal(isAppUrl('file:///tmp/index.html'), false);
  assert.match(CSP, /connect-src 'none'/);
  assert.doesNotMatch(CSP, /'unsafe-eval'/); // wasm permission does not enable JavaScript eval.
});
