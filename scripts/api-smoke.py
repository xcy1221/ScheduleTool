"""真实本地 API 验证；仅删除本脚本创建的测试记录。before / after 跨应用重启验证。"""
from pathlib import Path
import json
import os
import ssl
import sys
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parents[1]
LOCAL=Path(os.environ.get('SCHEDULETOOL_SMOKE_DIR',str(ROOT/'.local')))
LOCAL.mkdir(parents=True,exist_ok=True)
BASE=os.environ.get('SCHEDULETOOL_URL','http://127.0.0.1:18080/schedule').rstrip('/')
CA_FILE=os.environ.get('SCHEDULETOOL_CA_FILE')
TLS_CONTEXT=ssl.create_default_context(cafile=CA_FILE) if CA_FILE else None
state_file=LOCAL/'smoke-state.json'
state=json.loads(state_file.read_text(encoding='utf-8')) if state_file.exists() else {'ids':[]}

def request(path,method='GET',body=None,expected=200,authenticated=True,headers=None):
    h={'Content-Type':'application/json'}
    if authenticated: h['Authorization']='Bearer '+state['token']
    if headers: h.update(headers)
    req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),method=method,headers=h)
    try:
        with urllib.request.urlopen(req,timeout=15,context=TLS_CONTEXT) as response:
            status=response.status; text=response.read()
    except urllib.error.HTTPError as error:
        status=error.code; text=error.read()
    assert status==expected,(method,path,status,text.decode()[:500])
    return json.loads(text) if text else None

def payload(title,start='2026-10-17T09:00:00',**kw):
    return {**dict(title=title,description='仅用于本地自动验证',start=start,end=None,allDay=False,timeZone='Asia/Shanghai',recurrence='NONE',repeatUntil=None,color='#256f62',version=0),**kw}

def create(body):
    e=request('/api/v1/events','POST',body,201);state['ids'].append(e['id']);state_file.write_text(json.dumps(state));return e

def events(start='2026-10-01T00:00:00',end='2026-11-01T00:00:00'):
    return request('/api/v1/events?from='+start+'&to='+end)

if sys.argv[1:] == ['before']:
    assert not state.get('ids'),'先完成已有 smoke-state 的 after 清理，避免遗留测试数据'
    request('/api/v1/events?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00',expected=401,authenticated=False)
    request('/api/openapi',expected=401,authenticated=False)
    password=(ROOT/'.local/login.txt').read_text(encoding='utf-8').splitlines()[-1].split('：',1)[1]
    session=request('/api/v1/auth/login','POST',{'username':'admin','password':password},authenticated=False)
    state['token']=session['accessToken'];state_file.write_text(json.dumps(state))
    cookie={'Cookie':'SCHEDULE_SESSION='+state['token']}
    request('/api/v1/events','POST',payload('_测试_CSRF拦截'),403,False,cookie)
    e=create(payload('_测试_时间点'))
    assert request('/api/v1/events/'+e['id'])['start']=='2026-10-17T09:00:00'
    updated=request('/api/v1/events/'+e['id'],'PUT',payload('_测试_修改',start='2026-10-18T10:30:00'))
    assert updated['version']==1
    request('/api/v1/events/'+e['id']+'?version=0','DELETE',expected=409)
    request('/api/v1/events/'+e['id']+'?version=1','DELETE',expected=204);state['ids'].remove(e['id'])
    for title,extra in [('_测试_跨午夜',{'end':'2026-10-18T02:00:00'}),('_测试_全天',{'start':'2026-10-17T00:00:00','end':'2026-10-19T00:00:00','allDay':True})]:
        body=payload(title,start='2026-10-17T23:00:00');body.update(extra);create(body)
    daily=create(payload('_测试_每日',recurrence='DAILY',repeatUntil='2026-10-20'))
    moved=request('/api/v1/events/'+daily['id']+'?scope=OCCURRENCE&occurrenceDate=2026-10-18','PUT',payload('_测试_仅本次',start='2026-10-22T11:00:00',recurrence='DAILY',repeatUntil='2026-10-20'))
    request('/api/v1/events/'+daily['id']+'?scope=OCCURRENCE&occurrenceDate=2026-10-19&version='+str(moved['version']),'DELETE',expected=204)
    days=[x for x in events() if x['seriesId']==daily['id']]
    assert len(days)==3 and any(x['title']=='_测试_仅本次' and x['start']=='2026-10-22T11:00:00' for x in days)
    weekly=create(payload('_测试_每周',recurrence='WEEKLY',repeatUntil='2026-10-31'))
    assert len([x for x in events() if x['seriesId']==weekly['id']])==3
    current=request('/api/v1/events/'+daily['id'])
    request('/api/v1/events/'+daily['id'],'PUT',payload('_测试_系列重置',recurrence='DAILY',repeatUntil='2026-10-20',version=current['version']))
    assert len([x for x in events() if x['seriesId']==daily['id']])==4
    request('/api/v1/events','POST',payload('_测试_非法区间',end='2026-10-17T08:00:00'),400)
    assert '/api/v1/events' in request('/api/openapi')['paths']
    state['snapshot']=[x for x in events() if x['seriesId'] in state['ids']]
    state_file.write_text(json.dumps(state,ensure_ascii=False),encoding='utf-8')
    print('PASS: MySQL API 鉴权、CSRF、时间点 CRUD、冲突、跨天、全天、每日/每周、单次修改/取消、系列重置、输入校验、OpenAPI；等待重启。')
elif sys.argv[1:] == ['after']:
    assert state.get('snapshot'),'缺少重启前快照'
    request('/api/v1/auth/me')
    actual=[x for x in events() if x['seriesId'] in state['ids']]
    assert actual==state['snapshot'],'重启后日程不一致'
    for id in state['ids']:
        e=request('/api/v1/events/'+id)
        request('/api/v1/events/'+id+'?version='+str(e['version']),'DELETE',expected=204)
    request('/api/v1/auth/logout','POST',expected=204)
    request('/api/v1/auth/me',expected=401)
    state_file.unlink()
    print('PASS: 重启后日程及例外逐项一致、会话保留、系列删除、退出撤销 token；已清理本脚本测试记录。')
else:
    raise SystemExit('用法：python scripts/api-smoke.py before|after')
