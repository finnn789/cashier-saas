"""Phone normalization shared with the Java backend (docs/contracts/member-sync.md, section 3).

The result is a digits-only string of 9..15 characters, or None when invalid.
Callers must never log the raw input or the output.
"""

DIGITS = frozenset("0123456789")
SEPARATORS = frozenset(" -().")
MIN_LENGTH = 9
MAX_LENGTH = 15


def _trim(raw: str) -> str:
    # Mirrors Java String.trim(): strips every character up to and including U+0020.
    start = 0
    end = len(raw)
    while start < end and ord(raw[start]) <= 0x20:
        start += 1
    while end > start and ord(raw[end - 1]) <= 0x20:
        end -= 1
    return raw[start:end]


def normalize_phone(raw: str) -> str | None:
    if not isinstance(raw, str):
        return None
    text = _trim(raw)
    if not text:
        return None
    international = text[0] == "+"
    body = text[1:] if international else text
    digits: list[str] = []
    for character in body:
        if character in DIGITS:
            digits.append(character)
        elif character not in SEPARATORS:
            # Includes a second '+' or a '+' that is not the first character.
            return None
    number = "".join(digits)
    if not number:
        return None
    if not international:
        if number.startswith("0"):
            number = "62" + number[1:]
        elif number.startswith("62"):
            pass
        elif number.startswith("8"):
            number = "62" + number
    if not MIN_LENGTH <= len(number) <= MAX_LENGTH:
        return None
    return number
