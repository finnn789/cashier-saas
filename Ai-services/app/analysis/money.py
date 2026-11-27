"""Decimal parsing and Indonesian number/Rupiah formatting shared by all bot replies."""

from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any


def to_decimal(value: Any) -> Decimal:
    """Decimal of a str/int/Decimal; None, garbage and non-finite values become 0."""
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return Decimal(0)
    return result if result.is_finite() else Decimal(0)


def format_rupiah(value: Any) -> str:
    """Whole rupiah with dot thousands separators: 12500 -> Rp12.500, -500 -> -Rp500."""
    amount = int(to_decimal(value).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    return f"{'-' if amount < 0 else ''}Rp{abs(amount):,}".replace(",", ".")


def format_number(value: Any) -> str:
    """Plain number with a comma decimal and no trailing zeros: 12.50 -> 12,5."""
    return format(to_decimal(value).normalize(), "f").replace(".", ",")
