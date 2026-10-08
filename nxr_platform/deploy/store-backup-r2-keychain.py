#!/usr/bin/env python3
"""Enter this project's new backup-only R2 credentials locally without exposing them in chat or process arguments."""
import getpass
import re
import subprocess
import sys

ACCOUNT='PROJ-NXR-LIVE'
SERVICES={'access':'nxr-mysql-r2-backup-access-key','secret':'nxr-mysql-r2-backup-secret-key'}

def existing(service):
    return subprocess.run(['security','find-generic-password','-s',service,'-a',ACCOUNT],capture_output=True).returncode==0

def store(service,value):
    if not re.fullmatch(r'[A-Za-z0-9+/=_-]{16,128}',value):raise ValueError('Invalid S3 credential format; no item was saved')
    if existing(service):raise ValueError('This backup credential already exists; it will not be replaced automatically')
    # security's interactive input keeps secrets out of argv. The accepted
    # credential alphabet has no whitespace, quoting or command separators.
    command=f'add-generic-password -s {service} -a {ACCOUNT} -w {value}\nquit\n'
    subprocess.run(['security','-i'],input=command,text=True,capture_output=True)
    result=subprocess.run(['security','find-generic-password','-s',service,'-a',ACCOUNT,'-w'],capture_output=True,text=True)
    if result.returncode or result.stdout.strip()!=value:raise ValueError('Keychain storage could not be verified')

def main():
    if sys.platform!='darwin':raise ValueError('Use this credential-entry helper on the operator Mac')
    if any(existing(s) for s in SERVICES.values()):raise ValueError('Existing backup credential references were found; no automatic replacement')
    print('Use the S3 credentials from the R2 Object Read & Write token limited ONLY to nxr-mysql-backups-prod.')
    access=getpass.getpass('Access Key ID (hidden): ').strip()
    secret=getpass.getpass('Secret Access Key (hidden): ').strip()
    if not re.fullmatch(r'[A-Za-z0-9+/=_-]{16,128}',access) or not re.fullmatch(r'[A-Za-z0-9+/=_-]{32,128}',secret):raise ValueError('Invalid S3 credential format; no item was saved')
    store(SERVICES['access'],access)
    store(SERVICES['secret'],secret)
    print('Both backup-only credential references are verified in local Keychain. No secret was written to a file or displayed.')

if __name__=='__main__':
    try:main()
    except (ValueError,subprocess.SubprocessError):
        print('Credential entry did not complete. Existing credentials were not replaced; do not send credentials to chat.',file=sys.stderr)
        raise SystemExit(1)
