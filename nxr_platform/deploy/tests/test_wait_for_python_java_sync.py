import importlib.util
from pathlib import Path
import unittest
from datetime import datetime,timezone
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('syncwait',Path(__file__).parents[1]/'wait_for_python_java_sync.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class SyncWaitTests(unittest.TestCase):
    def fixture(self,**kw):
        return {'LoadState':'loaded','ActiveState':'inactive','SubState':'dead','Result':'success','ExecMainStatus':'0','ExecMainStartTimestamp':'Wed 2026-10-07 16:00:00 UTC','ExecMainExitTimestamp':'Wed 2026-10-07 16:04:10 UTC',**kw}
    def now(self):return datetime(2026,10,8,1,0,tzinfo=timezone.utc)
    def test_midnight_is_beijing_time(self):self.assertEqual(module.readiness(self.fixture(),self.now()),'ready')
    def test_yesterday_cannot_satisfy_today(self):self.assertEqual(module.readiness(self.fixture(ExecMainStartTimestamp='Tue 2026-10-06 16:00:00 UTC'),self.now()),'waiting_for_today')
    def test_activation_and_active_work_are_not_finished(self):
        for status in ('activating','active','deactivating'):
            with self.subTest(status=status):self.assertEqual(module.readiness(self.fixture(ActiveState=status),self.now()),'waiting_for_completion')
    def test_failed_sync_stops_backup(self):
        for fields in ({'Result':'timeout'},{'ExecMainStatus':'1'},{'ExecMainExitTimestamp':'Wed 2026-10-07 15:00:00 UTC'}):
            with self.subTest(fields=fields),self.assertRaises(RuntimeError):module.readiness(self.fixture(**fields),self.now())
    def test_missing_service_cannot_run_backup(self):
        with self.assertRaises(RuntimeError):module.readiness(self.fixture(LoadState='not-found'),self.now())
    def test_missing_start_waits_without_starting_sync(self):self.assertEqual(module.readiness(self.fixture(ExecMainStartTimestamp=''),self.now()),'waiting_for_today')
    def test_unknown_end_fails_closed(self):
        with self.assertRaises(RuntimeError):module.readiness(self.fixture(ExecMainExitTimestamp=''),self.now())
    def test_native_utc_and_offset_dates_parse(self):
        self.assertEqual(module.parse_timestamp('Thu 2026-10-08 00:00:00 +0800'),module.parse_timestamp('Wed 2026-10-07 16:00:00 UTC'))
    def test_status_reader_only_uses_show(self):
        result=type('R',(),{'returncode':0,'stdout':'LoadState=loaded\nActiveState=inactive\n'})()
        with patch.object(module.subprocess,'run',return_value=result) as run:
            module.state_for();self.assertEqual(run.call_args.args[0][1],'show')
    def test_immediate_ready_does_not_sleep(self):
        with patch.object(module,'state_for',return_value=self.fixture()),patch.object(module,'readiness',return_value='ready'),patch.object(module.time,'sleep') as sleep:
            self.assertEqual(module.main(['--deadline-seconds','1']),0);sleep.assert_not_called()
    def test_failure_is_nonzero_without_backup_command(self):
        with patch.object(module,'state_for',return_value=self.fixture(Result='failed')):
            self.assertEqual(module.main(['--deadline-seconds','1']),1)

if __name__=='__main__':unittest.main()
