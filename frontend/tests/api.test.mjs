import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
let moduleId = 0
const reply = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const session = { csrfToken: 'fresh-csrf', autoLogin: true }

async function setup(t, fetch) {
  const previous = globalThis.window
  globalThis.window = new EventTarget()
  t.after(() => { if (previous === undefined) delete globalThis.window; else globalThis.window = previous })
  const expired = []
  window.addEventListener('session-expired', event => expired.push(event))
  t.mock.method(globalThis, 'fetch', fetch)
  const client = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}#${++moduleId}`)
  return { ...client, expired }
}

test('默认模式的 401 直接要求登录', async t => {
  const calls = []
  const client = await setup(t, async url => { calls.push(url); return reply(401, { detail: '请先登录' }) })
  client.setCsrf('old-csrf')
  await assert.rejects(client.api('/api/v1/todos'), error => error.status === 401)
  assert.deepEqual(calls, ['/schedule/api/v1/todos'])
  assert.equal(client.expired.length, 1)
})

test('免登录恢复后使用新 CSRF 重试原始 POST', async t => {
  const calls = []
  const client = await setup(t, async (url, options) => {
    calls.push({ url, ...options })
    if (url.endsWith('/auth/me')) return reply(200, session)
    return options.headers['X-CSRF-TOKEN'] === 'old-csrf' ? reply(401, {}) : reply(201, { id: 'created' })
  })
  client.setCsrf('old-csrf', true)
  assert.deepEqual(await client.api('/api/v1/todos', 'POST', { title: '安排事项' }), { id: 'created' })
  assert.equal(calls.length, 3)
  assert.equal(calls[1].url, '/schedule/api/v1/auth/me')
  assert.equal(calls[2].method, 'POST')
  assert.equal(calls[2].body, calls[0].body)
  assert.equal(calls[2].credentials, 'same-origin')
  assert.equal(calls[2].headers['X-CSRF-TOKEN'], 'fresh-csrf')
  assert.equal(client.expired.length, 0)
})

test('并发过期请求共享一次会话恢复', async t => {
  let release
  const ready = new Promise(resolve => { release = resolve })
  let renewals = 0
  const calls = []
  const client = await setup(t, async (url, options) => {
    calls.push(url)
    if (url.endsWith('/auth/me')) { renewals++; await ready; return reply(200, session) }
    return options.headers['X-CSRF-TOKEN'] === 'old-csrf' ? reply(401, {}) : reply(200, [])
  })
  client.setCsrf('old-csrf', true)
  const results = Promise.all([client.api('/api/v1/todos'), client.api('/api/v1/events')])
  await new Promise(setImmediate)
  assert.equal(renewals, 1)
  release()
  assert.deepEqual(await results, [[], []])
  assert.equal(calls.length, 5)
  assert.equal(renewals, 1)
})

test('恢复后仍然 401 只重试一次', async t => {
  const calls = []
  const client = await setup(t, async url => {
    calls.push(url)
    return url.endsWith('/auth/me') ? reply(200, session) : reply(401, {})
  })
  client.setCsrf('old-csrf', true)
  await assert.rejects(client.api('/api/v1/todos'), error => error.status === 401)
  assert.deepEqual(calls, ['/schedule/api/v1/todos', '/schedule/api/v1/auth/me', '/schedule/api/v1/todos'])
  assert.equal(client.expired.length, 1)
})

for (const status of [403, 409]) test(`${status} 不恢复会话或重放写请求`, async t => {
  let calls = 0
  const client = await setup(t, async () => { calls++; return reply(status, {}) })
  client.setCsrf('old-csrf', true)
  await assert.rejects(client.api('/api/v1/todos', 'POST', { title: '安排事项' }), error => error.status === status)
  assert.equal(calls, 1)
  assert.equal(client.expired.length, 0)
})
