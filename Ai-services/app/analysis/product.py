"""Product analysis over the per-product aggregates Java returns (ABC, menu matrix, ranking, growth)."""

from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from app.analysis.money import to_decimal

_ONE_DP = Decimal("0.1")
_HUNDRED = Decimal(100)


def _by_sales(products: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(products, key=lambda p: (-to_decimal(p.get("gross_sales")), p.get("name", "")))


def abc_classes(products: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Pareto classes by gross sales: A while the share before the item is < 80%, B < 95%, else C."""
    rows = _by_sales(products)
    total = sum((to_decimal(p.get("gross_sales")) for p in rows), Decimal(0))
    if total <= 0:
        return []
    result, cumulative = [], Decimal(0)
    for p in rows:
        before = cumulative / total * _HUNDRED
        cumulative += to_decimal(p.get("gross_sales"))
        result.append(
            {
                **p,
                "share_pct": (to_decimal(p.get("gross_sales")) / total * _HUNDRED).quantize(_ONE_DP, ROUND_HALF_UP),
                "cumulative_pct": (cumulative / total * _HUNDRED).quantize(_ONE_DP, ROUND_HALF_UP),
                "abc": "A" if before < 80 else "B" if before < 95 else "C",
            }
        )
    return result


def menu_matrix(products: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Kasavana-Smith quadrants from actual sales; products with unknown cost are set aside."""
    matrix: dict[str, list[dict[str, Any]]] = {
        key: [] for key in ("STAR", "PLOWHORSE", "PUZZLE", "DOG", "COST_UNKNOWN")
    }
    rated = []
    for p in products:
        if to_decimal(p.get("items_without_cost")) > 0:
            matrix["COST_UNKNOWN"].append(dict(p))
        elif to_decimal(p.get("quantity")) > 0:
            rated.append(p)
    if not rated:
        return matrix
    total_quantity = sum((to_decimal(p["quantity"]) for p in rated), Decimal(0))
    margin_of = lambda p: to_decimal(p.get("gross_sales")) - to_decimal(p.get("cost_of_goods_sold"))
    average_margin = sum((margin_of(p) for p in rated), Decimal(0)) / total_quantity
    for p in rated:
        quantity = to_decimal(p["quantity"])
        unit_margin = margin_of(p) / quantity
        popular = quantity * len(rated) * 10 >= total_quantity * 7  # mix >= 70% of an even share
        high = unit_margin >= average_margin
        key = ("STAR" if high else "PLOWHORSE") if popular else ("PUZZLE" if high else "DOG")
        matrix[key].append(
            {
                **p,
                "mix_pct": (quantity / total_quantity * _HUNDRED).quantize(_ONE_DP, ROUND_HALF_UP),
                "unit_margin": unit_margin.quantize(Decimal(1), ROUND_HALF_UP),
            }
        )
    return matrix


def rank(products: list[dict[str, Any]], by: str = "quantity", n: int = 5, worst: bool = False) -> list[dict[str, Any]]:
    """Top (or worst) n products by `quantity` or `gross_sales`, ties by name."""
    sign = 1 if worst else -1
    return sorted(products, key=lambda p: (sign * to_decimal(p.get(by)), p.get("name", "")))[:n]


def growth_pct(current: Any, previous: Any) -> Decimal | None:
    """Percent change to 1 decimal; None when the previous value is 0 (shown as new)."""
    before = to_decimal(previous)
    if before == 0:
        return None
    return ((to_decimal(current) - before) / before * _HUNDRED).quantize(_ONE_DP, ROUND_HALF_UP)
