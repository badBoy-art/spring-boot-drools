#!/usr/bin/env python3
"""HTTP acceptance in an explicitly selected TEST environment. Creates random test metadata."""
import base64
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid


def required(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit('Missing environment variable: ' + name)
    return value


class Client:
    def __init__(self, url, username, password, csrf=False):
        self.url = url.rstrip('/')
        self.authorization = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.request('GET', '/rule/csrf') if csrf else None

    def request(self, method, path, body=None, headers=None, expected=200):
        merged = {'Authorization': self.authorization}
        if body is not None:
            merged['Content-Type'] = 'application/json'
        if getattr(self, 'csrf', None) and method not in ('GET', 'HEAD'):
            merged[self.csrf['headerName']] = self.csrf['token']
        merged.update(headers or {})
        req = urllib.request.Request(self.url + path, data=json.dumps(body).encode() if body is not None else None,
                                     headers=merged, method=method)
        try:
            response = self.opener.open(req, timeout=60)
        except urllib.error.HTTPError as exc:
            response = exc
        raw = response.read().decode()
        if response.code != expected:
            raise AssertionError('%s %s: expected %s, got %s: %s' % (method, path, expected, response.code, raw[:1500]))
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw


def main():
    if os.environ.get('DROOLS_SMOKE_TEST_ENV') != 'true':
        raise SystemExit('Set DROOLS_SMOKE_TEST_ENV=true to confirm this is a dedicated test environment')
    center = required('RULE_CENTER_URL')
    author = Client(center, required('RULE_CENTER_AUTHOR_USERNAME'), required('RULE_CENTER_AUTHOR_PASSWORD'), True)
    runtime = Client(center, required('RULE_CENTER_RUNTIME_USERNAME'), required('RULE_CENTER_RUNTIME_PASSWORD'))
    suffix = uuid.uuid4().hex[:12]
    document, rule_type = 'DOC_' + suffix, 'TYPE_' + suffix
    author.request('POST', '/rule/doc/save', {'docCode': document, 'docName': 'HTTP acceptance', 'status': 1})
    author.request('POST', '/rule/doc/object/save', {'docCode': document, 'objectKey': 'root', 'objectName': 'Root', 'valuePath': '', 'status': 1, 'isCollection': 0})
    author.request('POST', '/rule/doc/field/save', {'docCode': document, 'objectKey': 'root', 'fieldName': 'Amount', 'fieldKey': 'amount', 'fieldType': 'BigDecimal'})
    author.request('POST', '/rule/type/native', {'ruleType': rule_type, 'typeName': 'HTTP acceptance', 'docCode': document})
    author.request('POST', '/rule/type/outputs/tree', {'ruleType': rule_type, 'fields': [
        {'outputPath': 'accepted', 'nodeKind': 'LEAF', 'valueType': 'Boolean', 'source': 'EXT', 'sourceValue': 'accepted'}]})
    drl = '''package audit;
import com.example.drools.domain.DocFact;
rule "decision" when $d: DocFact() then $d.getExt().put("accepted", true); end
'''
    def draft_publish(source, draft_version, revision):
        author.request('PUT', '/rule/releases/' + rule_type + '/draft', {
            'expectedDraftVersion': draft_version, 'configuration': {
                'resources': [{'path': 'src/main/resources/audit.drl', 'content': source}]}})
        return author.request('POST', '/rule/releases/' + rule_type, {
            'expectedDraftVersion': draft_version + 1, 'expectedRevision': revision, 'remark': 'HTTP acceptance'})
    first = draft_publish(drl, 0, 0)
    result = runtime.request('POST', '/rule/evaluate?ruleType=' + rule_type, {'bizId': suffix, 'amount': 100})
    assert result['decision']['accepted'] is True and result['revision'] == 1, result
    author.request('POST', '/rule/releases/' + rule_type, {'expectedDraftVersion': 1, 'expectedRevision': 0}, expected=409)
    second = draft_publish(drl.replace('true', 'false'), 1, 1)
    result = runtime.request('POST', '/rule/evaluate?ruleType=' + rule_type, {'amount': 100})
    assert result['decision']['accepted'] is False and result['revision'] == 2, result
    author.request('POST', '/rule/releases/' + rule_type + '/rollback', {'releaseId': first['releaseId'], 'expectedRevision': 2})
    result = runtime.request('POST', '/rule/evaluate?ruleType=' + rule_type, {'amount': 100})
    assert result['decision']['accepted'] is True and result['revision'] == 3, result
    assert '规则' in author.request('GET', '/rule-console.html')
    print('PASS document registration, output schema, HTTP evaluation, revision conflict, rollback, console access')

    native_type = 'NATIVE_' + suffix
    author.request('POST', '/rule/type/native', {'ruleType': native_type, 'typeName': 'Native HTTP acceptance'})
    source = '''package audit;
declare Event
 value: int
end
rule observe when Event() from entry-point "Events" then end
query "EventsQuery"
 $event: Event() from entry-point "Events"
end
'''
    author.request('PUT', '/rule/releases/' + native_type + '/draft', {'expectedDraftVersion': 0, 'configuration': {
        'resources': [{'path': 'src/main/resources/events.drl', 'content': source}]}})
    author.request('POST', '/rule/releases/' + native_type, {'expectedRevision': 0, 'expectedDraftVersion': 1})
    result = runtime.request('POST', '/rule/native/evaluate?ruleType=' + native_type, {
        'facts': [{'package': 'audit', 'type': 'Event', 'values': {'value': 7}, 'entryPoint': 'Events'}],
        'queries': [{'name': 'EventsQuery'}]})
    assert result['fired'] == 1 and len(result['queries']['EventsQuery']) == 1, result
    print('PASS native declared facts, named entry point, query, rule type isolation')
    worker_url = os.environ.get('WORKER_URL')
    if worker_url:
        worker = Client(worker_url, required('DROOLS_WORKER_USERNAME'), required('DROOLS_WORKER_PASSWORD'))
        session = worker.request('POST', '/worker/sessions', {'ruleType': native_type, 'clockType': 'pseudo'})['sessionId']
        path = '/worker/sessions/' + session
        try:
            body = {'type': 'audit.Event', 'fact': {'value': 7}, 'entryPoint': 'Events'}
            first_insert = worker.request('POST', path + '/facts', body, {'Idempotency-Key': 'insert-1'})
            assert first_insert == worker.request('POST', path + '/facts', body, {'Idempotency-Key': 'insert-1'})
            assert len(worker.request('GET', path + '/facts')['facts']) == 1
            worker.request('POST', path + '/facts', dict(body, fact={'value': 8}), {'Idempotency-Key': 'insert-1'}, expected=409)
            handle = first_insert['factHandle']
            worker.request('PUT', path + '/facts', {'factHandle': handle, 'type': 'audit.Event', 'fact': {'value': 9}}, {'Idempotency-Key': 'update-1'})
            assert worker.request('POST', path + '/fire', {}, {'Idempotency-Key': 'fire-1'})['fired'] == 1
            assert len(worker.request('POST', path + '/queries', {'name': 'EventsQuery'})['rows']) == 1
            worker.request('POST', path + '/checkpoint')
            worker.request('DELETE', path + '/facts?factHandle=' + urllib.parse.quote(handle, safe=''))
            assert worker.request('GET', path + '/facts')['facts'] == []
            print('PASS Worker Feign/auth, idempotency, named entry point update/delete, query, durable checkpoint')
        finally:
            worker.request('DELETE', path)
    print('HTTP acceptance passed; registered random metadata: ' + rule_type + ', ' + native_type)


if __name__ == '__main__':
    main()
