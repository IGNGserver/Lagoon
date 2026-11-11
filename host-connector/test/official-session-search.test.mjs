import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.env.LAGOON_OFFICIAL_DSH_PACKAGE_ROOT;
test('fixed official cold search normalizes literal input, filters visible current messages and bounds Unicode snippets', {
  skip: root === undefined ? 'Set LAGOON_OFFICIAL_DSH_PACKAGE_ROOT for official cold-search runtime' : false,
  timeout: 10_000,
}, async () => {
  const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  assert.equal(pkg.version, '0.2.1-rc.5');
  const { ApiSessionList } = await import(pathToFileURL(join(root, 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/types/list.js')));
  const ids = Array.from({ length: 21 }, (_, i) => `visible-${i}`);
  const queries = [];
  const hit = (id, snippet = '😀'.repeat(260)) => ({ header: { id, cwd: '/repo', createdAt: 1 }, bestMatch: { sessionId: id, surface: 'current', type: 'assistant/message', snippet } });
  const provider = {
    async listSessions(signal) { signal.throwIfAborted(); return ids.map(id => ({ header: { id, cwd: '/repo' } })); },
    async searchSessions(request, exec) {
      exec.signal.throwIfAborted(); queries.push(request);
      assert.equal(request.query, 'literal [a.*] + text');
      assert.deepEqual(request.eventFilters, [{ kind: 'type', values: ['user/message', 'assistant/message'] }, { kind: 'surface', values: ['current'] }]);
      return request.cursor === undefined
        ? { items: [hit('invisible'), ...ids.slice(0, 19).map(id => hit(id))], nextCursor: 'tail' }
        : { items: ids.slice(19).map(id => hit(id)) };
    },
  };
  const list = new ApiSessionList({ sessionProjections: { register() {} }, inject() {}, get(name) { assert.equal(name, 'sessionQuery'); return provider; } }, 1);
  const result = await list.search('  literal [a.*] + text  ', new AbortController().signal);
  assert.equal(result.items.length, 20); assert.equal(result.hasMore, true);
  assert.deepEqual(result.items.map(item => item.sessionId), ids.slice(0, 20));
  assert.ok(result.items.every(item => [...item.snippet].length === 240 && item.snippet.length === 480));
  assert.equal(queries.length, 2);
  for (const invalid of ['', ' '.repeat(3), 'x'.repeat(501), 'bad\0query']) await assert.rejects(list.search(invalid, new AbortController().signal));
  const aborted = new AbortController(); aborted.abort(); await assert.rejects(list.search('text', aborted.signal));
  assert.equal(queries.length, 2);
});
