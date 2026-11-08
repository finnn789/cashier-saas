package org.lite.project.cashiersaas.unit.shared;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.shared.PhoneNumberNormalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the normalizer against the contract fixture shared with the Python implementation. */
@Tag("unit")
class PhoneNumberNormalizerTest {
    /** Resolved against the Maven working directory (the project root). */
    private static final Path CASES = Path.of("docs", "contracts", "phone-normalization-cases.json");

    private record Case(String input, String expected) {
    }

    private static List<Case> loadCases() throws Exception {
        assertTrue(Files.isRegularFile(CASES), "Contract fixture not found at " + CASES.toAbsolutePath());
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(CASES));
        assertTrue(root.isArray(), "Fixture must be a JSON array");
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode expected = node.get("expected");
            cases.add(new Case(node.get("input").asString(), expected.isNull() ? null : expected.asString()));
        }
        return cases;
    }

    @Test
    void everyContractCaseProducesTheExpectedResult() throws Exception {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 16, "Fixture unexpectedly small: " + cases.size());
        for (Case testCase : cases) {
            Optional<String> actual = PhoneNumberNormalizer.tryNormalize(testCase.input());
            if (testCase.expected() == null) {
                assertTrue(actual.isEmpty(), "Expected invalid for input [" + testCase.input() + "]");
            } else {
                assertEquals(Optional.of(testCase.expected()), actual, "Input [" + testCase.input() + "]");
            }
        }
    }

    @Test
    void normalizeReturnsDigitsForValidAndThrowsBadRequestExceptionForInvalidContractCases() throws Exception {
        for (Case testCase : loadCases()) {
            if (testCase.expected() == null) {
                InvalidOperationException exception = assertThrows(InvalidOperationException.class,
                        () -> PhoneNumberNormalizer.normalize(testCase.input()),
                        "Input [" + testCase.input() + "]");
                assertEquals("Nomor telepon tidak valid.", exception.getMessage());
            } else {
                assertEquals(testCase.expected(), PhoneNumberNormalizer.normalize(testCase.input()),
                        "Input [" + testCase.input() + "]");
            }
        }
    }

    @Test
    void normalizingAnAlreadyNormalizedNumberIsStable() throws Exception {
        for (Case testCase : loadCases()) {
            if (testCase.expected() != null) {
                assertEquals(testCase.expected(), PhoneNumberNormalizer.normalize(testCase.expected()),
                        "Input [" + testCase.input() + "]");
            }
        }
    }

    @Test
    void nullInputIsInvalid() {
        assertTrue(PhoneNumberNormalizer.tryNormalize(null).isEmpty());
        assertThrows(InvalidOperationException.class, () -> PhoneNumberNormalizer.normalize(null));
    }

    @Test
    void nonAsciiDigitsAndOtherSeparatorsAreRejected() {
        // Arabic-Indic digits must not be accepted as digits.
        assertTrue(PhoneNumberNormalizer.tryNormalize(
                "٠٨١٢٣٤٥٦٧٨٩").isEmpty());
        assertTrue(PhoneNumberNormalizer.tryNormalize("0812\t34567890").isEmpty());
        assertTrue(PhoneNumberNormalizer.tryNormalize("0812/34567890").isEmpty());
    }

    @Test
    void lengthBoundariesAreNineToFifteenDigits() {
        assertEquals(Optional.of("123456789"), PhoneNumberNormalizer.tryNormalize("123456789"));
        assertTrue(PhoneNumberNormalizer.tryNormalize("12345678").isEmpty());
        assertEquals(Optional.of("123456789012345"), PhoneNumberNormalizer.tryNormalize("123456789012345"));
        assertTrue(PhoneNumberNormalizer.tryNormalize("1234567890123456").isEmpty());
        assertEquals(Optional.of("123456789012345"), PhoneNumberNormalizer.tryNormalize("+123456789012345"));
        assertTrue(PhoneNumberNormalizer.tryNormalize("+1234567890123456").isEmpty());
    }
}
