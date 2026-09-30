#!/usr/bin/env python3
"""Register and publish the product demo through the real Center API; never resets tables."""
import copy
import importlib.util
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('platform_smoke', ROOT / 'scripts/platform-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
Client, required = smoke.Client, smoke.required
SAMPLES = ROOT / 'docs/samples/product-approval'
TYPE = 'PRODUCT_MULTI_APPROVAL'


def load(name):
    return json.loads((SAMPLES / name).read_text())


def evaluate(runtime, request):
    return runtime.request('POST', '/rule/evaluate?ruleType=' + TYPE, request)


def verify(runtime):
    request = load('request.json')
    result = evaluate(runtime, request)
    decision = result['decision']
    assert set(decision) == {'spu', 'sku', 'regionPrice'}, result
    assert decision['spu']['instanceId'] == 'T+2', result
    for collection in ('sku', 'regionPrice'):
        rows = decision[collection]
        assert [row['instanceId'] for row in rows] == ['T+2', 'T', 'T'], rows
        assert [row['marginRate'] for row in rows] == [0.4, 0.2, 0.3], rows
        assert [row['skuCode'] for row in rows] == ['SKU-001', 'SKU-002', 'SKU-003'], rows
    assert [row['priceDecision'] for row in decision['regionPrice']] == ['STANDARD', 'REVIEW', 'STANDARD']
    assert result['fired'] == 7, result
    # Every item is evaluated independently, including exact decimal threshold boundaries.
    for sale, cost, expected in [('0.13', '0.10', 'T'), ('0.130000001', '0.10', 'T+2'),
                                 ('0.129999999', '0.10', 'T'), ('100', '0', 'INVALID_COST'),
                                 ('100', '-1', 'INVALID_COST'), ('90', '100', 'T')]:
        sample = copy.deepcopy(request)
        for row in [sample['spu']] + sample['sku'] + sample['regionPrice']:
            row['salePrice'], row['costPrice'] = sale, cost
        output = evaluate(runtime, sample)['decision']
        for row in [output['spu']] + output['sku'] + output['regionPrice']:
            assert row['instanceId'] == expected, (sale, cost, row)
            if expected == 'INVALID_COST':
                assert 'marginRate' not in row, row
    empty = copy.deepcopy(request)
    empty['sku'], empty['regionPrice'] = [], []
    output = evaluate(runtime, empty)
    assert output['decision']['sku'] == [] and output['decision']['regionPrice'] == []
    assert output['fired'] == 1
    print('PASS SPU, per-SKU/per-region outputs, decimal boundary, zero/negative cost, empty arrays')
    return result


def main():
    if os.environ.get('DROOLS_PRODUCT_INIT') != 'true':
        raise SystemExit('Set DROOLS_PRODUCT_INIT=true to authorize registering PRODUCT_SKU / ' + TYPE)
    author = Client(required('RULE_CENTER_URL'), required('RULE_CENTER_AUTHOR_USERNAME'),
                    required('RULE_CENTER_AUTHOR_PASSWORD'), True)
    runtime = Client(required('RULE_CENTER_URL'), required('RULE_CENTER_RUNTIME_USERNAME'),
                     required('RULE_CENTER_RUNTIME_PASSWORD'))
    existing = any(t['ruleType'] == TYPE for t in author.request('GET', '/rule/type/list'))
    resume = os.environ.get('DROOLS_PRODUCT_RESUME') == 'true'
    if existing and not resume:
        raise SystemExit(TYPE + ' already exists; refusing to overwrite. Use the operations page to edit it.')
    if resume:
        if any(r['ruleType'] == TYPE for r in author.request('GET', '/rule/list')):
            raise SystemExit('Cannot resume initialization once an approval rule exists.')
        if existing and author.request('GET', '/rule/releases/' + TYPE + '/draft')['revision'] != 0:
            raise SystemExit('Cannot resume initialization of a published rule type.')
    registration = load('registration.json')
    code = registration['document']['docCode']
    if any(d['docCode'] == code for d in author.request('GET', '/rule/doc/tree')) and not resume:
        raise SystemExit(code + ' already exists; refusing to overwrite.')
    author.request('POST', '/rule/doc/save', registration['document'])
    for obj in registration['objects']:
        author.request('POST', '/rule/doc/object/save', obj)
    for field in registration['fields']:
        author.request('POST', '/rule/doc/field/save', field)
    author.request('POST', '/rule/type/save', load('configuration.json'))
    # Persist an explicit resource configuration draft; rules are supplied by the form template.
    author.request('PUT', '/rule/releases/' + TYPE + '/draft', {
        'expectedDraftVersion': author.request('GET', '/rule/releases/' + TYPE + '/draft')['draftVersion'],
        'configuration': {'eventProcessingMode': 'stream'}})
    params = {'marginThreshold': '0.3'}
    rule = author.request('POST', '/rule/create', {
        'ruleName': 'PRODUCT_MULTI_APPROVAL_DEFAULT', 'ruleType': TYPE,
        'ruleParams': json.dumps(params), 'remark': '商品三步审批示例：1/2/3个结果字段'})
    published = author.request('POST', '/rule/publish/' + str(rule['id']), params,
                               {'If-Match': str(rule['version'])})
    result = verify(runtime)
    # Verify parameter-driven publication, then restore the requested 0.3 threshold.
    changed = author.request('POST', '/rule/publish/' + str(rule['id']), {'marginThreshold': '0.5'},
                             {'If-Match': str(published['version'])})
    output = evaluate(runtime, load('request.json'))
    assert all(row['instanceId'] == 'T' for row in [output['decision']['spu']] + output['decision']['sku'] + output['decision']['regionPrice'])
    restored = author.request('POST', '/rule/publish/' + str(rule['id']), params,
                              {'If-Match': str(changed['version'])})
    author.request('POST', '/rule/publish/' + str(rule['id']), {'marginThreshold': '-0.1'},
                   {'If-Match': str(restored['version'])}, expected=400)
    result = verify(runtime)
    assert result['revision'] == 3, result
    (SAMPLES / 'response.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('PASS parameter update/republication and invalid threshold rejection; final threshold=0.3, revision=3')


if __name__ == '__main__':
    main()
