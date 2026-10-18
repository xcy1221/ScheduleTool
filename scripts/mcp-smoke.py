"""Local MCP acceptance: before, restart the application, then after. Secrets stay in .local/mcp."""
import argparse
import base64
from datetime import datetime
import hashlib
from http.cookiejar import CookieJar
import json
from pathlib import Path
import re
import secrets
import sys
from urllib import request, error, parse

HOME = Path(__file__).resolve().parents[1]
PRIVATE = HOME / '.local/mcp'
STATE = PRIVATE / 'smoke-state.json'
BASE = 'http://127.0.0.1:18081/schedule'
CALLBACK = 'http://127.0.0.1:18082/callback'
VERIFIER = secrets.token_urlsafe(48)


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


browser = request.build_opener(NoRedirect(), request.HTTPCookieProcessor(CookieJar()))


def check(condition, description):
    if not condition:
        raise RuntimeError(description)


def call(path, body=None, *, token=None, form=False, client_auth=False):
    headers = {'Accept': 'application/json, text/event-stream' if path == '/mcp' else 'text/html, application/json'}
    headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if client_auth:
        secret = (PRIVATE / 'client-secret.txt').read_text(encoding='utf-8').strip()
        headers['Authorization'] = 'Basic ' + base64.b64encode(('scheduletool-chatgpt:' + secret).encode()).decode()
    data = None if body is None else (parse.urlencode(body, doseq=True).encode() if form else json.dumps(body).encode())
    try:
        response = browser.open(request.Request(BASE + path, data=data, headers=headers), timeout=20)
    except error.HTTPError as exc:
        response = exc
    with response:
        content = response.read(16 * 1024 * 1024).decode()
        return response.code, response.headers, content


def login():
    values = {}
    for line in (HOME / '.local/login.txt').read_text(encoding='utf-8-sig').splitlines():
        key, sep, value = line.partition('：')
        if sep:
            values[key.strip()] = value.strip()
    status, _, html = call('/mcp-login')
    csrf = re.search(r'name="_csrf" value="([^"]+)"', html)
    check(status == 200 and csrf, '读取本地 OAuth 登录页失败')
    status, _, _ = call('/mcp-login', {'username': values['用户名'], 'password': values['密码'], '_csrf': csrf[1]}, form=True)
    check(status == 302, 'OAuth 登录失败；停止重试，请检查本机私密配置')
    challenge = base64.urlsafe_b64encode(hashlib.sha256(VERIFIER.encode()).digest()).rstrip(b'=').decode()
    state = secrets.token_urlsafe(24)
    query = parse.urlencode({'client_id': 'scheduletool-chatgpt', 'redirect_uri': CALLBACK, 'response_type': 'code',
                             'scope': 'schedule:read schedule:write', 'state': state, 'code_challenge': challenge,
                             'code_challenge_method': 'S256', 'resource': BASE + '/mcp'})
    status, headers, html = call('/oauth2/authorize?' + query)
    location = headers.get('Location', '')
    if status == 302 and not location.startswith(CALLBACK + '?'):
        target = parse.urlsplit(location)
        check(location.startswith(BASE + '/mcp-consent?'), 'OAuth 返回了未预期的授权页面')
        status, headers, html = call('/mcp-consent?' + target.query)
    if status == 200:
        consent = re.search(r'name="state" value="([^"]+)"', html)
        check(consent is not None, '未读取到 OAuth 授权状态')
        status, headers, _ = call('/oauth2/authorize', {'client_id': 'scheduletool-chatgpt', 'state': consent[1],
                                  'scope': ['schedule:read', 'schedule:write']}, form=True)
        location = headers.get('Location', '')
    check(status == 302 and location.startswith(CALLBACK + '?'), '授权码回调失败')
    parameters = parse.parse_qs(parse.urlsplit(location).query)
    check(parameters.get('state') == [state] and 'code' in parameters, 'OAuth state 或授权码校验失败')
    status, _, raw = call('/oauth2/token', {'grant_type': 'authorization_code', 'code': parameters['code'][0],
                         'redirect_uri': CALLBACK, 'code_verifier': VERIFIER, 'resource': BASE + '/mcp'}, form=True, client_auth=True)
    check(status == 200, 'OAuth 换取令牌失败')
    return json.loads(raw)


def rpc(token, method, params):
    status, _, raw = call('/mcp', {'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params}, token=token)
    check(status == 200, f'MCP HTTP 请求失败：{status}')
    result = json.loads(raw)
    check('error' not in result, 'MCP 协议返回错误')
    return result['result']


def tool(token, name, **args):
    result = rpc(token, 'tools/call', {'name': name, 'arguments': args})
    check(not result.get('isError'), f'{name} 未通过，请按 requestId 检查本地运行日志')
    return result['structuredContent']


def listing(token):
    return tool(token, 'schedule_list', **{'from': '2026-11-01T00:00:00', 'to': '2026-12-01T00:00:00'})['result']


def save(state):
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')


def run(phase):
    if phase == 'before':
        check(not STATE.exists(), '已有验收状态，保留证据；先完成 after，不重复创建')
        state = {'url': BASE, 'tokens': login(), 'ids': [], 'createdAt': datetime.now().isoformat()}
        save(state)
        token = state['tokens']['access_token']
        rpc(token, 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'ScheduleTool smoke', 'version': '1'}})
        check(len(rpc(token, 'tools/list', {})['tools']) == 8, '工具数量不正确')
        marker = secrets.token_hex(4)
        events = [
            {'title': 'MCP 验收每日 ' + marker, 'start': '2026-11-01T09:00:00', 'recurrence': 'DAILY', 'repeatUntil': '2026-11-03'},
            {'title': 'MCP 验收每周 ' + marker, 'start': '2026-11-01T10:00:00', 'recurrence': 'WEEKLY', 'repeatUntil': '2026-11-22'},
            {'title': 'MCP 验收全天 ' + marker, 'start': '2026-11-05T00:00:00', 'end': '2026-11-07T00:00:00', 'allDay': True},
            {'title': 'MCP 验收时间点 ' + marker, 'start': '2026-11-08T09:00:00'},
            {'title': 'MCP 验收跨天 ' + marker, 'start': '2026-11-10T23:00:00', 'end': '2026-11-11T02:00:00'},
        ]
        for event in events:
            result = tool(token, 'schedule_create', event=event, source='CHATGPT')
            state['ids'].append(result['result']['id'])
            save(state)
            check(result.get('auditSequence'), '写操作缺少审计回执')
        daily = state['ids'][0]
        tool(token, 'schedule_update', seriesId=daily, scope='OCCURRENCE', occurrenceDate='2026-11-02', source='CHATGPT',
             event={'title': events[0]['title'], 'start': '2026-11-02T11:00:00', 'version': 0})
        tool(token, 'schedule_delete', seriesId=daily, scope='OCCURRENCE', occurrenceDate='2026-11-03', version=1, source='CHATGPT')
        state['baseline'] = [e for e in listing(token) if e['seriesId'] in state['ids']]
        check(len(state['baseline']) == 9, '时间点、重复、全天或跨天展开结果不正确')
        save(state)
        print('OAuth 与 8 个 MCP 工具已验证；5 个验收系列已保存。请重启本地实例后运行 after。')
        return
    state = json.loads(STATE.read_text(encoding='utf-8'))
    check(state['url'] == BASE and 'baseline' in state, '验收状态不完整或目标地址不符，停止操作')
    # Refresh the persisted grant after restart, proving that authorization and signing keys survived.
    status, _, raw = call('/oauth2/token', {'grant_type': 'refresh_token', 'refresh_token': state['tokens']['refresh_token'],
                          'resource': BASE + '/mcp'}, form=True, client_auth=True)
    check(status == 200, '重启后 OAuth 授权未保留')
    state['tokens'] = json.loads(raw)
    save(state)
    token = state['tokens']['access_token']
    current = [e for e in listing(token) if e['seriesId'] in state['ids']]
    check(current == state['baseline'], '重启后日程或单次例外不一致，保留证据，不自动修复')
    for series_id in state['ids']:
        event = tool(token, 'schedule_get', seriesId=series_id)['result']
        tool(token, 'schedule_delete', seriesId=series_id, scope='SERIES', version=event['version'], source='CHATGPT')
    check(not [e for e in listing(token) if e['seriesId'] in state['ids']], '验收日程清理未确认')
    check(tool(token, 'schedule_verify')['result']['valid'], '审计链验证失败')
    for value in ('access_token', 'refresh_token'):
        status, _, _ = call('/oauth2/revoke', {'token': state['tokens'][value], 'token_type_hint': value}, form=True, client_auth=True)
        check(status == 200, '验收令牌撤销未确认')
    state.pop('tokens')
    state['complete'] = True
    save(state)
    print('重启持久化、OAuth 刷新、日程清理与审计链验证通过；验收令牌已撤销。')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    try:
        run(parser.parse_args().phase)
    except (OSError, ValueError, KeyError, RuntimeError) as exc:
        print('验收未完成：' + (str(exc) if isinstance(exc, RuntimeError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(1)
