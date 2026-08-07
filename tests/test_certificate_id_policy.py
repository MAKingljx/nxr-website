import unittest
from unittest.mock import patch

from nxr_admin.admin_core import generate_cert_id, is_canonical_cert_id


class CertificateIdPolicyTests(unittest.TestCase):
    def test_accepts_ten_digit_format_without_leading_zero(self):
        self.assertTrue(is_canonical_cert_id("5703018202"))
        self.assertFalse(is_canonical_cert_id("0123456789"))

    def test_rejects_legacy_and_wrong_length_formats_for_new_entries(self):
        self.assertFalse(is_canonical_cert_id("VRA003"))
        self.assertFalse(is_canonical_cert_id("NXR2026032401"))
        self.assertFalse(is_canonical_cert_id("123456789"))
        self.assertFalse(is_canonical_cert_id("12345678901"))
        self.assertFalse(is_canonical_cert_id("１２３４５６７８９０"))

    def test_generator_builds_nonzero_first_digit(self):
        with (
            patch("nxr_admin.admin_core.random.choice", return_value="7"),
            patch("nxr_admin.admin_core.random.choices", return_value=list("000000000")),
            patch("nxr_admin.admin_core.certificate_id_exists", return_value=False),
        ):
            self.assertEqual(generate_cert_id(), "7000000000")


if __name__ == "__main__":
    unittest.main()
