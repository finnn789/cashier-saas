"""TASK-116: pure derived calculations of app.analysis (ABC, menu matrix, ranking, growth, money)."""

import unittest
from decimal import Decimal

from app.analysis.money import format_number, format_rupiah
from app.analysis.product import abc_classes, growth_pct, menu_matrix, rank


def _p(name: str, quantity: int, gross: int, cogs: int = 0, no_cost: int = 0) -> dict:
    return {
        "name": name,
        "quantity": quantity,
        "gross_sales": str(gross),
        "cost_of_goods_sold": str(cogs),
        "items_without_cost": no_cost,
    }


class AbcTests(unittest.TestCase):
    def test_classes_use_cumulative_share_before_the_item(self) -> None:
        result = abc_classes([_p("D", 1, 50), _p("A", 1, 500), _p("C", 1, 150), _p("B", 1, 300)])

        self.assertEqual([r["name"] for r in result], ["A", "B", "C", "D"])
        self.assertEqual([r["abc"] for r in result], ["A", "A", "B", "C"])
        self.assertEqual(
            [r["share_pct"] for r in result],
            [Decimal("50.0"), Decimal("30.0"), Decimal("15.0"), Decimal("5.0")],
        )
        self.assertEqual(
            [r["cumulative_pct"] for r in result],
            [Decimal("50.0"), Decimal("80.0"), Decimal("95.0"), Decimal("100.0")],
        )

    def test_single_product_is_a_and_zero_total_sales_is_empty(self) -> None:
        self.assertEqual([r["abc"] for r in abc_classes([_p("Solo", 1, 100)])], ["A"])
        self.assertEqual(abc_classes([_p("X", 1, 0), _p("Y", 2, 0)]), [])


class MenuMatrixTests(unittest.TestCase):
    STAR = _p("Star", 40, 400000, 100000)  # margin 7500
    PLOWHORSE = _p("Plowhorse", 40, 200000, 120000)  # margin 2000
    PUZZLE = _p("Puzzle", 10, 150000, 50000)  # margin 10000
    DOG = _p("Dog", 10, 30000, 20000)  # margin 1000; average margin 4900, popular at mix >= 17.5%

    @staticmethod
    def _names(matrix: dict) -> dict:
        return {key: [i["name"] for i in items] for key, items in matrix.items()}

    def test_each_product_lands_in_its_quadrant_with_mix_and_unit_margin(self) -> None:
        matrix = menu_matrix([self.STAR, self.PLOWHORSE, self.PUZZLE, self.DOG])

        self.assertEqual(
            self._names(matrix),
            {
                "STAR": ["Star"],
                "PLOWHORSE": ["Plowhorse"],
                "PUZZLE": ["Puzzle"],
                "DOG": ["Dog"],
                "COST_UNKNOWN": [],
            },
        )
        self.assertEqual(matrix["STAR"][0]["mix_pct"], Decimal("40"))
        self.assertEqual(matrix["STAR"][0]["unit_margin"], Decimal("7500"))

    def test_cost_unknown_product_is_isolated_and_does_not_move_thresholds(self) -> None:
        unknown = _p("Unknown", 1000, 1, 0, no_cost=1)

        matrix = menu_matrix([self.STAR, self.PLOWHORSE, self.PUZZLE, self.DOG, unknown])

        self.assertEqual(
            self._names(matrix),
            {
                "STAR": ["Star"],
                "PLOWHORSE": ["Plowhorse"],
                "PUZZLE": ["Puzzle"],
                "DOG": ["Dog"],
                "COST_UNKNOWN": ["Unknown"],
            },
        )
        self.assertNotIn("mix_pct", matrix["COST_UNKNOWN"][0])
        self.assertNotIn("unit_margin", matrix["COST_UNKNOWN"][0])

    def test_empty_input_has_all_five_keys_and_zero_quantity_is_ignored(self) -> None:
        keys = {"STAR", "PLOWHORSE", "PUZZLE", "DOG", "COST_UNKNOWN"}
        self.assertEqual(set(menu_matrix([])), keys)

        matrix = menu_matrix([_p("Ghost", 0, 0, 0), self.STAR])

        self.assertEqual(set(matrix), keys)
        self.assertEqual(sum(len(v) for v in matrix.values()), 1)


class RankTests(unittest.TestCase):
    def test_top_by_quantity_worst_by_sales_and_name_breaks_ties(self) -> None:
        items = [_p("B", 5, 100), _p("A", 5, 300), _p("C", 9, 200), _p("D", 1, 50)]

        self.assertEqual([r["name"] for r in rank(items, by="quantity", n=2)], ["C", "A"])
        self.assertEqual(
            [r["name"] for r in rank(items, by="gross_sales", n=2, worst=True)], ["D", "B"]
        )


class GrowthTests(unittest.TestCase):
    def test_growth_percent_with_none_for_zero_previous_and_string_inputs(self) -> None:
        self.assertEqual(growth_pct(150, 100), Decimal("50.0"))
        self.assertEqual(growth_pct(50, 100), Decimal("-50.0"))
        self.assertEqual(growth_pct("125", "100"), Decimal("25.0"))
        self.assertIsNone(growth_pct(10, 0))
        self.assertIsNone(growth_pct(0, 0))


class MoneyTests(unittest.TestCase):
    def test_rupiah_and_number_formats(self) -> None:
        self.assertEqual(format_rupiah("12500"), "Rp12.500")
        self.assertEqual(format_rupiah(-500), "-Rp500")
        self.assertEqual(format_rupiah("abc"), "Rp0")
        self.assertEqual(format_number("12.5"), "12,5")


if __name__ == "__main__":
    unittest.main()
