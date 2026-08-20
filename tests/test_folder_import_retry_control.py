import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
UPLOAD_MANAGER = PROJECT_ROOT / 'nxr_admin' / 'templates' / 'upload_manager.html'


class FolderImportRetryControlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = UPLOAD_MANAGER.read_text(encoding='utf-8')

    def test_transient_failures_have_three_bounded_retries(self):
        self.assertIn('const MAX_FOLDER_IMPORT_RETRIES = 3;', self.source)
        self.assertIn('function shouldRetryFolderImport(', self.source)
        self.assertIn(
            '[0, 408, 409, 423, 425, 429, 500, 502, 503, 504].includes(status)',
            self.source,
        )
        self.assertIn("typeof response.retryable === 'boolean'", self.source)

    def test_retry_uses_exponential_backoff_for_the_same_batch(self):
        retry_block = self.source.split(
            'function scheduleFolderImportRetry',
            1,
        )[1].split('function uploadFolderImportBatch', 1)[0]
        self.assertIn('configuredBaseDelay * (2 ** retryAttempt)', retry_block)
        self.assertIn('setTimeout(() =>', retry_block)
        self.assertIn('...context', retry_block)
        self.assertIn('retryAttempt: nextRetryAttempt', retry_block)

    def test_success_resets_retry_counter_before_next_batch(self):
        upload_block = self.source.split('function uploadFolderImportBatch', 1)[1]
        self.assertIn('retryAttempt = 0', upload_block)
        self.assertIn('retryAttempt: 0', upload_block)
        self.assertIn('requestSettled', upload_block)

    def test_final_failure_keeps_manual_retry_available(self):
        self.assertIn('submitButton.disabled = false;', self.source)
        self.assertIn(
            'Automatic retries were exhausted. Click Import Images to try again.',
            self.source,
        )


if __name__ == '__main__':
    unittest.main()
