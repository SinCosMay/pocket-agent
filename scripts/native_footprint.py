#!/usr/bin/env python3
"""Measure the same native inputs before and after AGP symbol stripping."""
import json
from pathlib import Path
root=Path('app/build/intermediates');report={}
for stage in ['merged_native_libs','stripped_native_libs']:
 files={}
 for path in (root/stage/'release').rglob('libjavet*.so'):
  files[path.parent.name+'/'+path.name]=path.stat().st_size
 report[stage]=files
report['scope']='File-size comparison of the same i18n native library before/after standard NDK stripping; not a RAM or worker-spawn benchmark.'
print(json.dumps(report,indent=2))
