"""normalize_phone must agree with the shared Java/Python contract fixtures."""
import json
import unittest
from pathlib import Path

from app.core.phone import normalize_phone

CASES_FILE = (
    Path(__file__).resolve().parents[3] / "docs" / "contracts" / "phone-normalization-cases.json"
)


class PhoneNormalizationTests(unittest.TestCase):
    def test_every_shared_contract_case(self) -> None:
        cases = json.loads(CASES_FILE.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(cases), 16)
        for case in cases:
            with self.subTest(input=case["input"]):
                self.assertEqual(normalize_phone(case["input"]), case["expected"])

    def test_plus_only_allowed_as_first_character(self) -> None:
        self.assertIsNone(normalize_phone("0812+34567890"))
        self.assertIsNone(normalize_phone("+"))
        self.assertIsNone(normalize_phone("+ 62 812 +3456"))

    def test_international_number_kept_as_is(self) -> None:
        self.assertEqual(normalize_phone("+081234567890"), "081234567890")

    def test_length_boundaries(self) -> None:
        self.assertEqual(normalize_phone("123456789"), "123456789")
        self.assertIsNone(normalize_phone("12345678"))
        self.assertEqual(normalize_phone("123456789012345"), "123456789012345")
        self.assertIsNone(normalize_phone("1234567890123456"))

    def test_only_ascii_digits_and_listed_separators_are_accepted(self) -> None:
        self.assertIsNone(normalize_phone("０８１２３４５６７８９０"))  # fullwidth digits
        self.assertIsNone(normalize_phone("0812/3456/7890"))
        self.assertIsNone(normalize_phone("0812\t3456\t7890"))
        self.assertEqual(normalize_phone("\t0812 3456 7890\n"), "6281234567890")

    def test_non_string_is_invalid(self) -> None:
        self.assertIsNone(normalize_phone(None))  # type: ignore[arg-type]
        self.assertIsNone(normalize_phone(81234567890))  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
