package org.lite.project.cashiersaas.shared;

import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;

import java.util.Optional;

/**
 * Normalizes phone numbers to digits only (no plus sign), 9 to 15 digits.
 * Must stay equivalent to the Python {@code normalize_phone}; both follow docs/contracts/member-sync.md section 3
 * and are checked against docs/contracts/phone-normalization-cases.json.
 */
public final class PhoneNumberNormalizer {
    public static final String INVALID_MESSAGE = "Nomor telepon tidak valid.";

    private static final int MIN_DIGITS = 9;
    private static final int MAX_DIGITS = 15;
    private static final String INDONESIA_CODE = "62";

    private PhoneNumberNormalizer() {
    }

    /** Returns the normalized digits or throws {@link InvalidOperationException} (HTTP 400) when invalid. */
    public static String normalize(String raw) {
        return tryNormalize(raw).orElseThrow(() -> new InvalidOperationException(INVALID_MESSAGE));
    }

    /** Returns the normalized digits, or empty when the input is not a valid phone number. */
    public static Optional<String> tryNormalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        boolean international = trimmed.charAt(0) == '+';
        StringBuilder digits = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            } else if (character == '+' && index == 0) {
                continue;
            } else if (!isSeparator(character)) {
                return Optional.empty();
            }
        }

        String normalized = international ? digits.toString() : withCountryCode(digits.toString());
        if (normalized.length() < MIN_DIGITS || normalized.length() > MAX_DIGITS) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private static String withCountryCode(String digits) {
        if (digits.startsWith("0")) {
            return INDONESIA_CODE + digits.substring(1);
        }
        if (digits.startsWith(INDONESIA_CODE)) {
            return digits;
        }
        if (digits.startsWith("8")) {
            return INDONESIA_CODE + digits;
        }
        return digits;
    }

    private static boolean isSeparator(char character) {
        return character == ' ' || character == '-' || character == '(' || character == ')' || character == '.';
    }
}
