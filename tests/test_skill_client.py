"""Run with: python -X utf8 -m unittest discover -s tests"""
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
import unittest

source = Path(__file__).resolve().parents[1] / 'skills/scheduletool/scripts/scheduletool.py'
spec = importlib.util.spec_from_file_location('scheduletool', source)
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class Handler(BaseHTTPRequestHandler):
    calls = []
    received_sources = []
    fail_write = False

    def log_message(self, *_):
        pass

    def do_POST(self):
        self.calls.append(self.path)
        self.received_sources.append((self.command, self.path, self.headers.get('X-Schedule-Source')))
        if self.path.endswith('/login'):
            self.reply(200, {'accessToken': 'test-secret-token'})
        elif self.path.endswith('/me'):
            self.reply(200, {'username': 'admin', 'expiresAt': '2026-10-18T00:00:00Z'})
        elif self.path.endswith('/logout'):
            self.reply(204, None)
        elif self.fail_write:
            self.reply(504, {'detail': 'test-secret-token test-password'})
        else:
            self.reply(201, {'id': 'test-id'}, {'X-Audit-Sequence': '4', 'X-Request-ID': 'test-request'})

    do_GET = do_PUT = do_DELETE = do_POST

    def reply(self, status, body, headers=None):
        data = json.dumps(body).encode() if body is not None else b''
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class SkillClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        Handler.calls.clear()
        Handler.received_sources.clear()
        Handler.fail_write = False

    def connect(self, source='UNKNOWN'):
        return client.Client(f'http://127.0.0.1:{self.server.server_port}', 'admin', 'test-password', source=source)

    def test_declared_source_is_sent_on_all_event_writes_only(self):
        for source in ('CODEX', 'CHATGPT'):
            Handler.received_sources.clear()
            with self.connect(source) as api:
                client.mutate(api, SimpleNamespace(command='create'), {'title': 'test'})
                api.call('/api/v1/events/test-id?scope=SERIES', 'PUT', {})
                api.call('/api/v1/events/test-id?scope=SERIES&version=1', 'DELETE')
                api.call('/api/v1/events/test-id')
            writes = [x for x in Handler.received_sources if x[0] in ('POST', 'PUT', 'DELETE') and x[1].startswith('/api/v1/events')]
            self.assertEqual([source]*3, [x[2] for x in writes])
            self.assertTrue(all(x[2] is None for x in Handler.received_sources if x not in writes))

    def test_default_source_is_unknown_and_invalid_source_is_rejected(self):
        with self.connect() as api:
            result = client.mutate(api, SimpleNamespace(command='create'), {})
            self.assertEqual('UNKNOWN', result['declaredSource'])
        with self.assertRaises(client.Failure):
            self.connect('CODEX\r\nInjected: value')
        self.assertEqual('CHATGPT', client.parser().parse_args(['--source', 'CHATGPT', 'status']).source)

    def test_todo_writes_and_link_send_source_and_return_receipts(self):
        with self.connect('CODEX') as api:
            args = client.parser().parse_args(['todo-create', '--input', 'unused.json'])
            result = client.todo_operation(api, args, {'title': '想法'})
            self.assertEqual('4', result['auditSequence'])
            self.assertEqual('test-request', result['requestId'])
            api.call('/api/v1/todos/test-id', 'PUT', {})
            api.call('/api/v1/events/test-id/todo', 'PUT', {})
            api.call('/api/v1/todos/test-id?version=0&mode=KEEP_EVENTS', 'DELETE')
        writes = [x for x in Handler.received_sources if x[2]]
        self.assertEqual(['CODEX']*4, [x[2] for x in writes])

    def test_todo_conflict_is_not_retried_and_delete_requires_choice(self):
        Handler.fail_write = True
        with self.connect('CODEX') as api:
            with self.assertRaises(client.Failure):
                client.todo_operation(api, client.parser().parse_args(['todo-create', '--input', 'unused']), {'title': 'test'})
        self.assertEqual(1, Handler.calls.count('/api/v1/todos'))
        with self.assertRaises(SystemExit):
            client.parser().parse_args(['todo-delete', '00000000-0000-0000-0000-000000000000', '--version', '0', '--confirm-delete'])

    def test_configured_target_is_used_when_url_not_provided(self):
        with TemporaryDirectory() as folder:
            home = Path(folder)
            (home / '.local').mkdir()
            (home / '.local/login.txt').write_text('用户名：admin\n密码：test-password', encoding='utf-8')
            (home / '.local/skill-config.json').write_text(json.dumps({'url': f'http://127.0.0.1:{self.server.server_port}'}))
            args = client.parser().parse_args(['--home', str(home), 'status'])
            result = client.run(args)
            self.assertEqual('admin', result['username'])
            self.assertEqual('/api/v1/auth/logout', Handler.calls[-1])

    def test_write_returns_server_evidence_and_revokes_session(self):
        with self.connect() as api:
            result = client.mutate(api, SimpleNamespace(command='create'), {'title': 'test'})
            self.assertEqual('4', result['auditSequence'])
            self.assertEqual('test-request', result['requestId'])
        self.assertEqual(['/api/v1/auth/login', '/api/v1/events', '/api/v1/auth/logout'], Handler.calls)

    def test_uncertain_write_is_not_retried_and_secrets_are_redacted(self):
        Handler.fail_write = True
        with self.connect() as api:
            with self.assertRaises(client.Failure) as caught:
                client.mutate(api, SimpleNamespace(command='create'), {})
        self.assertEqual(1, Handler.calls.count('/api/v1/events'))
        self.assertNotIn('test-password', str(caught.exception))
        self.assertNotIn('test-secret-token', str(caught.exception))

    def test_refuses_insecure_remote_address_and_invalid_series_id(self):
        with self.assertRaises(client.Failure):
            client.Client('http://example.com', 'admin', 'secret')
        with self.connect() as api:
            with self.assertRaises(client.Failure):
                client.mutate(api, SimpleNamespace(command='delete', id='series@2026-10-17'), None)
        self.assertNotIn('/api/v1/events', Handler.calls)

    def test_failed_verification_preserves_external_checkpoint(self):
        class API:
            base = 'http://127.0.0.1/schedule'
            valid = True

            def call(self, path):
                self.path = path
                return {'valid': self.valid, 'lastSequence': 2, 'lastHash': 'a'*64, 'detail': 'mismatch'}

        with TemporaryDirectory() as folder:
            home, api = Path(folder), API()
            client.verify(api, home, save=True)
            anchor = home / '.local/audit-checkpoint.json'
            original = anchor.read_bytes()
            api.valid = False
            with self.assertRaises(client.Failure):
                client.verify(api, home, save=True)
            self.assertIn('expectedSequence=2', api.path)
            self.assertEqual(original, anchor.read_bytes())


if __name__ == '__main__':
    unittest.main()
