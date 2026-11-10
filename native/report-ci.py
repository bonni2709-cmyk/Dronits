#!/usr/bin/env python3
"""Publish small, factual test/lint summaries in the run and Checks API."""
import os
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
lines = []
results = sorted((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if results:
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for result in results:
        suite = ET.parse(result).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    lines.append('JVM: ' + ', '.join(f'{key}={value}' for key, value in totals.items()))
else:
    lines.append('JVM: no completed test results in this run.')
lint = root / 'app/build/reports/lint-results-release.xml'
if lint.is_file():
    issues = ET.parse(lint).getroot().findall('issue')
    lines.append(f'Lint: {len(issues)} issues.')
    for issue in issues:
        lines.append(f"{issue.get('severity')}: {issue.get('id')}: {issue.get('message')}")
text = '\n'.join(lines)
for start in range(0, len(text), 2800):
    chunk = text[start:start + 2800].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::notice title=Android verification summary::' + chunk)
if summary := os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(summary, 'a') as output:
        output.write('## Android verification\n\n```text\n' + text + '\n```\n')
