#!/usr/bin/env python3
"""Wait for today's existing sync before a scheduled backup; never starts or changes it."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import re
import subprocess
import sys
import time
from zoneinfo import ZoneInfo

UNIT = 'nxr-python-java-sync.service'

def parse_timestamp(value: str) -> datetime | None:
    match = re.search(r'(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.\d+)? (UTC|GMT|CST|[+-]\d{4})$', value)
    if not match:
        return None
    zone = match[3]
    if zone in ('UTC', 'GMT'):
        tz = timezone.utc
    elif zone == 'CST':
        tz = ZoneInfo('Asia/Shanghai')
    else:
        return datetime.strptime(match[1]+' '+match[2]+' '+zone, '%Y-%m-%d %H:%M:%S %z')
    return datetime.strptime(match[1]+' '+match[2], '%Y-%m-%d %H:%M:%S').replace(tzinfo=tz)

def state_for(unit: str = UNIT) -> dict[str,str]:
    result = subprocess.run(['systemctl','show',unit,'--property=LoadState,ActiveState,SubState,Result,ExecMainStatus,ExecMainStartTimestamp,ExecMainExitTimestamp'], capture_output=True, text=True, timeout=10)
    if result.returncode:
        raise RuntimeError('Unable to read the existing sync status')
    return dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line)

def readiness(state: dict[str,str], now: datetime) -> str:
    if state.get('LoadState') != 'loaded':
        raise RuntimeError('The expected sync service is unavailable; backup was not started')
    midnight = now.astimezone(ZoneInfo('Asia/Shanghai')).replace(hour=0,minute=0,second=0,microsecond=0)
    start = parse_timestamp(state.get('ExecMainStartTimestamp',''))
    if start is None or start < midnight:
        return 'waiting_for_today'
    if state.get('ActiveState') in ('activating','active','deactivating'):
        return 'waiting_for_completion'
    end = parse_timestamp(state.get('ExecMainExitTimestamp',''))
    if state.get('Result') != 'success' or state.get('ExecMainStatus') != '0' or end is None or end < start:
        raise RuntimeError('Today\'s sync did not complete successfully; existing backups were kept')
    return 'ready'

def main(argv=None) -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deadline-seconds',type=int,default=7200)
    args=parser.parse_args(argv)
    if not 1 <= args.deadline_seconds <= 14400:
        parser.error('Deadline must be between 1 and 14400 seconds')
    deadline=time.monotonic()+args.deadline_seconds
    try:
        while True:
            result=readiness(state_for(),datetime.now(timezone.utc))
            if result=='ready':
                print('Today\'s sync completed; backup may begin',flush=True)
                return 0
            if time.monotonic()>=deadline:
                raise RuntimeError('Timed out waiting for today\'s sync; existing backups were kept')
            time.sleep(min(5,max(0,deadline-time.monotonic())))
    except (RuntimeError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr)
        return 1

if __name__=='__main__':
    raise SystemExit(main())
