"""ScheduleTool API client and database-external audit checkpoints."""
import argparse
from collections import deque
from datetime import datetime, timedelta, timezone, date
import json
import os
from pathlib import Path
import ssl
import sys
from urllib import error, parse, request
from uuid import UUID

_REPOSITORY = Path(__file__).resolve().parents[3]
DEFAULT_HOME = _REPOSITORY if (_REPOSITORY / 'backend/pom.xml').is_file() else Path.cwd()
FIELDS = {'title', 'description', 'start', 'end', 'allDay', 'timeZone', 'recurrence', 'repeatUntil', 'color', 'version', 'todoId', 'todoVersion'}
TODO_FIELDS = {'title', 'description', 'expectedEffect', 'implementationIdea', 'status', 'pinned', 'version'}
SOURCES = ('CODEX', 'CHATGPT', 'WEB', 'OTHER', 'UNKNOWN')


class Failure(Exception):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def credentials(home):
    values = {}
    path = home / '.local/login.txt'
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            key, sep, value = line.partition('：')
            if sep:
                values[key.strip()] = value.strip()
    username = os.environ.get('SCHEDULETOOL_USERNAME', values.get('用户名', 'admin'))
    password = os.environ.get('SCHEDULETOOL_PASSWORD', values.get('密码', ''))
    if not password:
        raise Failure('缺少本机登录信息；检查 .local/login.txt 或 SCHEDULETOOL_PASSWORD。')
    return username, password


class Client:
    def __init__(self, base, username, password, source='UNKNOWN', ca_file=None):
        if source not in SOURCES:
            raise Failure('来源须为 CODEX、CHATGPT、WEB、OTHER 或 UNKNOWN。')
        self.source = source
        parsed = parse.urlsplit(base)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise Failure('服务地址格式不正确。')
        if parsed.scheme == 'http' and parsed.hostname not in ('127.0.0.1', 'localhost', '::1'):
            raise Failure('只有本机地址可使用 HTTP；其他目标须配置 HTTPS。')
        self.base, self.username, self.password, self.token = base.rstrip('/'), username, password, ''
        handlers = [NoRedirect]
        if ca_file:
            handlers.append(request.HTTPSHandler(context=ssl.create_default_context(cafile=str(ca_file))))
        self.opener = request.build_opener(*handlers)
        self.request_id, self.audit_sequence = None, None

    def sanitize(self, text):
        for secret in (self.password, self.token):
            if secret:
                text = text.replace(secret, '[REDACTED]')
        return text

    def call(self, path, method='GET', body=None):
        self.request_id, self.audit_sequence = None, None
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        route = path.partition('?')[0]
        if method in ('POST', 'PUT', 'DELETE') and any(route == base or route.startswith(base + '/') for base in ('/api/v1/events', '/api/v1/todos')):
            headers['X-Schedule-Source'] = self.source
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=15) as response:
                self.request_id = response.headers.get('X-Request-ID')
                self.audit_sequence = response.headers.get('X-Audit-Sequence')
                raw = response.read(16 * 1024 * 1024 + 1)
        except error.HTTPError as exc:
            self.request_id = exc.headers.get('X-Request-ID')
            try:
                detail = json.loads(exc.read(8192)).get('detail', '请求被拒绝')
            except (ValueError, AttributeError):
                detail = '请求被拒绝'
            raise Failure(self.sanitize(f'HTTP {exc.code}: {detail}；requestId={self.request_id}'), exc.code) from None
        if len(raw) > 16 * 1024 * 1024:
            raise Failure('响应过大，请缩小查询范围。')
        return json.loads(raw) if raw else None

    def __enter__(self):
        session = self.call('/api/v1/auth/login', 'POST', {'username': self.username, 'password': self.password})
        self.token = session['accessToken']
        return self

    def __exit__(self, *_):
        try:
            self.call('/api/v1/auth/logout', 'POST')
        except (Failure, OSError, ValueError):
            print('提示：当前 API 会话撤销未确认。', file=sys.stderr)


def mutate(client, args, body):
    path, query = '/api/v1/events', {}
    if args.command != 'create':
        try:
            UUID(args.id)
        except ValueError:
            raise Failure('请使用 seriesId，不能使用 seriesId@日期。') from None
        path += '/' + args.id
        client.call(path)
        query['scope'] = args.scope
        if args.scope == 'OCCURRENCE':
            if not args.occurrence_date:
                raise Failure('单次操作必须提供 --occurrence-date。')
            query['occurrenceDate'] = date.fromisoformat(args.occurrence_date).isoformat()
    if args.command == 'delete':
        if not args.confirm_delete:
            raise Failure('删除须明确指定 --confirm-delete、范围和所查询到的版本号。')
        if args.version < 0:
            raise Failure('版本号不能小于 0。')
        query['version'] = args.version
    if query:
        path += '?' + parse.urlencode(query)
    try:
        result = client.call(path, {'create': 'POST', 'update': 'PUT', 'delete': 'DELETE'}[args.command], body)
    except Failure as exc:
        raise Failure(f'{exc}；先核对日程和审计，不自动重试写操作。', exc.status) from None
    except (OSError, ValueError):
        raise Failure('操作结果未确认；先查询日程和服务端审计，不要直接重试。') from None
    return {'requestId': client.request_id, 'auditSequence': client.audit_sequence,
            'declaredSource': client.source, 'result': result}


def todo_operation(client, args, body):
    command = args.command
    path = '/api/v1/todos'
    if command == 'todo-list':
        return client.call(path + '?' + parse.urlencode({k: v for k, v in {
            'q': args.q, 'status': args.status, 'includeCompleted': str(args.include_completed).lower(),
            'unscheduled': str(args.unscheduled).lower()}.items() if v is not None}))
    if command not in ('todo-list', 'todo-create'):
        UUID(args.id)
        path = ('/api/v1/events/' if command == 'link' else path + '/') + args.id
        client.call(path)
    if command == 'todo-get':
        return client.call(path)
    if command == 'todo-delete':
        if not args.confirm_delete:
            raise Failure('删除待办须先确认关联系列及例外的影响，并指定 --confirm-delete 和 --mode。')
        path += '?' + parse.urlencode({'version': args.version, 'mode': args.mode})
    if command == 'link':
        path += '/todo'
        body = {'todoId': args.todo_id, 'todoVersion': args.todo_version, 'version': args.version}
        if args.todo_id:
            UUID(args.todo_id)
            client.call('/api/v1/todos/' + args.todo_id)
    try:
        result = client.call(path, {'todo-create': 'POST', 'todo-update': 'PUT', 'todo-delete': 'DELETE', 'link': 'PUT'}[command], body)
    except Failure as exc:
        raise Failure(f'{exc}；先核对待办、日程和审计，不自动重试。', exc.status) from None
    except (OSError, ValueError):
        raise Failure('操作结果未确认；先核对待办、日程和审计，不自动重试。') from None
    return {'result': result, 'requestId': client.request_id, 'auditSequence': client.audit_sequence, 'declaredSource': client.source}


def verify(client, home, save=False):
    path = home / '.local/audit-checkpoint.json'
    query = ''
    if path.exists():
        anchor = json.loads(path.read_text(encoding='utf-8'))
        if anchor['url'] != client.base:
            raise Failure('校验点属于其他服务地址，请先核对目标。')
        query = '?' + parse.urlencode({'expectedSequence': anchor['sequence'], 'expectedHash': anchor['hash']})
    result = client.call('/api/v1/audit-logs/verify' + query)
    result['externalCheckpointUsed'] = bool(query)
    if not result['valid']:
        raise Failure('审计校验失败：' + result['detail'] + '；不要覆盖旧校验点或继续写入。')
    if save:
        anchor = {'url': client.base, 'sequence': result['lastSequence'], 'hash': result['lastHash'],
                  'savedAt': datetime.now(timezone.utc).isoformat()}
        folder = home / '.local/audit-checkpoints'
        folder.mkdir(parents=True, exist_ok=True)
        history = folder / f"{anchor['sequence']}-{anchor['hash']}.json"
        encoded = json.dumps(anchor, ensure_ascii=False, indent=2) + '\n'
        if not history.exists():
            with history.open('x', encoding='utf-8') as stream:
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
        pending = path.with_suffix('.tmp')
        with pending.open('w', encoding='utf-8') as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, path)
        result['checkpoint'] = str(history)
    return result


def tail(path, count):
    if not path.exists():
        return []
    with path.open(encoding='utf-8-sig', errors='replace') as stream:
        return [line.rstrip('\n') for line in deque(stream, maxlen=count)]


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--home', type=Path, default=Path(os.environ.get('SCHEDULETOOL_HOME', DEFAULT_HOME)))
    p.add_argument('--url', default=os.environ.get('SCHEDULETOOL_URL'))
    p.add_argument('--ca-file', type=Path, default=os.environ.get('SCHEDULETOOL_CA_FILE'), help='HTTPS 私有 CA 的公开证书文件')
    p.add_argument('--source', choices=SOURCES, default='UNKNOWN', help='调用方自报来源；不代表服务端已保存')
    sub = p.add_subparsers(dest='command', required=True)
    sub.add_parser('status')
    listing = sub.add_parser('list')
    listing.add_argument('--from', dest='from_time', required=True)
    listing.add_argument('--to', dest='to_time', required=True)
    sub.add_parser('get').add_argument('id')
    sub.add_parser('create').add_argument('--input', type=Path, required=True)
    for name in ('update', 'delete'):
        cmd = sub.add_parser(name)
        cmd.add_argument('id')
        cmd.add_argument('--scope', choices=['SERIES', 'OCCURRENCE'], required=True)
        cmd.add_argument('--occurrence-date')
        if name == 'update':
            cmd.add_argument('--input', type=Path, required=True)
        else:
            cmd.add_argument('--version', type=int, required=True)
            cmd.add_argument('--confirm-delete', action='store_true')
    sub.add_parser('logs').add_argument('--tail', type=int, default=30)
    history = sub.add_parser('history')
    history.add_argument('--after', type=int, default=0)
    history.add_argument('--limit', type=int, default=50)
    history.add_argument('--series-id')
    history.add_argument('--request-id')
    sub.add_parser('verify')
    sub.add_parser('checkpoint')
    listing = sub.add_parser('todo-list')
    listing.add_argument('--q')
    listing.add_argument('--status', choices=['PENDING', 'IN_PROGRESS', 'COMPLETED', 'SHELVED'])
    listing.add_argument('--include-completed', action='store_true')
    listing.add_argument('--unscheduled', action='store_true')
    sub.add_parser('todo-get').add_argument('id')
    sub.add_parser('todo-create').add_argument('--input', type=Path, required=True)
    update = sub.add_parser('todo-update'); update.add_argument('id'); update.add_argument('--input', type=Path, required=True)
    delete = sub.add_parser('todo-delete'); delete.add_argument('id'); delete.add_argument('--version', type=int, required=True)
    delete.add_argument('--mode', choices=['KEEP_EVENTS', 'DELETE_EVENTS'], required=True); delete.add_argument('--confirm-delete', action='store_true')
    link = sub.add_parser('link'); link.add_argument('id'); link.add_argument('--todo-id', required=True, help='空字符串解除关联')
    link.add_argument('--version', type=int, required=True); link.add_argument('--todo-version', type=int)
    return p


def run(args):
    if not args.home.is_dir():
        raise Failure('项目目录不存在；请配置 SCHEDULETOOL_HOME。')
    if args.command == 'logs':
        if not 1 <= args.tail <= 1000:
            raise Failure('--tail 范围为 1 至 1000。')
        return {name: tail(args.home / '.local' / name, args.tail) for name in ('logs/runtime.log', 'app-error.log')}
    body = None
    if args.command in ('create', 'update', 'todo-create', 'todo-update'):
        body = json.loads(args.input.read_text(encoding='utf-8-sig'))
        if not isinstance(body, dict) or set(body) - (TODO_FIELDS if args.command.startswith('todo-') else FIELDS):
            raise Failure('正文须为日程 JSON 对象，且只包含 API 支持的字段。')
        if args.command in ('create', 'todo-create'):
            body.setdefault('version', 0)
        if type(body.get('version')) is not int or body['version'] < 0:
            raise Failure('正文必须提供非负整数 version；修改须使用已查询到的版本号。')
    query = None
    if args.command == 'list':
        start, end = datetime.fromisoformat(args.from_time), datetime.fromisoformat(args.to_time)
        if start.tzinfo or end.tzinfo or not timedelta(0) < end - start <= timedelta(days=366):
            raise Failure('查询使用上海本地时间，不带偏移；范围须大于 0 且不超过 366 天。')
        query = parse.urlencode({'from': start.isoformat(timespec='seconds'), 'to': end.isoformat(timespec='seconds')})
    settings_file = args.home / '.local/skill-config.json'
    settings = json.loads(settings_file.read_text(encoding='utf-8-sig')) if settings_file.exists() else {}
    url = args.url or settings.get('url', 'http://127.0.0.1:18080/schedule')
    ca_file = args.ca_file or settings.get('caFile')
    with Client(url, *credentials(args.home), source=args.source, ca_file=ca_file) as client:
        if args.command.startswith('todo-') or args.command == 'link':
            if args.command not in ('todo-list', 'todo-get'):
                verify(client, args.home)
            return todo_operation(client, args, body)
        if args.command == 'status':
            me = client.call('/api/v1/auth/me')
            return {key: me[key] for key in ('username', 'expiresAt')}
        if args.command == 'list':
            return client.call('/api/v1/events?' + query)
        if args.command == 'get':
            return client.call('/api/v1/events/' + parse.quote(args.id, safe=''))
        if args.command == 'history':
            if args.after < 0 or not 1 <= args.limit <= 200:
                raise Failure('--after 须非负，--limit 须为 1 至 200。')
            query = {'after': args.after, 'limit': args.limit}
            if args.series_id:
                query['seriesId'] = args.series_id
            if args.request_id:
                query['requestId'] = args.request_id
            return client.call('/api/v1/audit-logs?' + parse.urlencode(query))
        if args.command in ('verify', 'checkpoint'):
            return verify(client, args.home, args.command == 'checkpoint')
        # Check the existing external checkpoint before writing, when present.
        verify(client, args.home)
        return mutate(client, args, body)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    try:
        result = run(parser().parse_args())
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (Failure, OSError, ValueError, KeyError) as exc:
        print(f'错误：{exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
