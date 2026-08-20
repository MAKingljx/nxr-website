import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ENTRY_FORM = PROJECT_ROOT / "nxr_admin" / "templates" / "entry_form_updated.html"


class EntryFormRequestControlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = ENTRY_FORM.read_text(encoding="utf-8")

    def test_grade_and_pop_requests_share_bounded_debounce(self):
        self.assertIn("const REQUEST_DEBOUNCE_MS = 400;", self.source)
        self.assertIn("gradeDebounceTimer = setTimeout(runCalculation, REQUEST_DEBOUNCE_MS)", self.source)
        self.assertIn("popDebounceTimer = setTimeout(runCalculation, REQUEST_DEBOUNCE_MS)", self.source)

    def test_both_requests_abort_stale_work_and_ignore_stale_responses(self):
        self.assertEqual(self.source.count("new AbortController()"), 2)
        self.assertIn("signal: controller.signal", self.source)
        self.assertIn("requestSequence !== gradeRequestSequence", self.source)
        self.assertIn("requestSequence !== popRequestSequence", self.source)
        self.assertIn("activePopPayloadKey = null;", self.source)

    def test_payload_keys_deduplicate_grade_and_pop_requests(self):
        self.assertIn("payloadKey === completedGradePayloadKey", self.source)
        self.assertIn("payloadKey === scheduledPopPayloadKey", self.source)
        self.assertIn("payloadKey === activePopPayloadKey", self.source)
        self.assertIn("payloadKey === completedPopPayloadKey", self.source)

    def test_match_autofill_schedules_pop_once_without_synthetic_field_events(self):
        match_block = self.source.split("function matchCard(button)", 1)[1].split(
            "// Function to validate required fields", 1
        )[0]
        self.assertNotIn("dispatchEvent", match_block)
        self.assertEqual(match_block.count("window.schedulePOPCalculation();"), 1)
        self.assertIn("forEach(markFieldPopulated)", match_block)

    def test_pop_fields_have_only_one_request_listener_each(self):
        self.assertIn(
            "const eventName = element.tagName === 'SELECT' ? 'change' : 'input';",
            self.source,
        )
        self.assertIn("element.addEventListener(eventName, schedulePOPCalculation);", self.source)
        self.assertNotIn("gradeCalculated", self.source)

    def test_initial_load_uses_one_explicit_request_path(self):
        initial_block = self.source.split("// Initial validation and calculations", 1)[1].split(
            "// 删除图片功能", 1
        )[0]
        self.assertIn("calculateFinalGrade({immediate: true});", initial_block)
        self.assertIn("schedulePOPCalculation({immediate: true});", initial_block)
        self.assertNotIn("calculatePOP", initial_block)


if __name__ == "__main__":
    unittest.main()
