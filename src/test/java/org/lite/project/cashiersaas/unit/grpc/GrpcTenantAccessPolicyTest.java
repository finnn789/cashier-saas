package org.lite.project.cashiersaas.unit.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class GrpcTenantAccessPolicyTest {

    private static final String ALLOWED = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String ALLOWED_MIXED_CASE = "AbCdEf01-2345-6789-ABCD-ef0123456789";

    private static GrpcSecurityProperties properties(List<String> allowed) {
        GrpcSecurityProperties properties = new GrpcSecurityProperties();
        properties.setServiceKey("unit-test-internal-service-key-0001");
        properties.setAllowedTenantIds(new ArrayList<>(allowed));
        return properties;
    }

    private static GrpcTenantAccessPolicy policy(String... allowed) {
        return new GrpcTenantAccessPolicy(properties(Arrays.asList(allowed)));
    }

    private static StatusRuntimeException rejection(GrpcTenantAccessPolicy policy, String raw) {
        return assertThrows(StatusRuntimeException.class, () -> policy.requireAllowedTenant(raw));
    }

    @Test
    void acceptsAllowlistedTenant() {
        assertEquals(UUID.fromString(ALLOWED), policy(ALLOWED, OTHER).requireAllowedTenant(ALLOWED));
    }

    @Test
    void acceptsAllowlistedTenantWithSurroundingWhitespace() {
        assertEquals(UUID.fromString(ALLOWED), policy(ALLOWED).requireAllowedTenant("  " + ALLOWED + "\t\n"));
    }

    @Test
    void acceptsUpperCaseTenantUuidAgainstLowerCaseAllowlist() {
        assertEquals(UUID.fromString(ALLOWED), policy(ALLOWED).requireAllowedTenant(ALLOWED.toUpperCase()));
    }

    @Test
    void acceptsLowerCaseRequestAgainstMixedCaseAllowlistEntryWithWhitespace() {
        GrpcTenantAccessPolicy policy = policy("  " + ALLOWED_MIXED_CASE + " ");

        assertEquals(UUID.fromString(ALLOWED_MIXED_CASE),
                policy.requireAllowedTenant(ALLOWED_MIXED_CASE.toLowerCase()));
    }

    @Test
    void nonAllowlistedTenantIsPermissionDeniedWithoutLeakingAllowlistOrRequestedId() {
        StatusRuntimeException ex = rejection(policy(ALLOWED), OTHER);

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        String description = String.valueOf(ex.getStatus().getDescription());
        assertFalse(description.contains(ALLOWED));
        assertFalse(description.contains(OTHER));
        assertFalse(description.contains("1111"));
    }

    @Test
    void emptyAllowlistDeniesEveryTenant() {
        GrpcTenantAccessPolicy policy = policy();

        assertEquals(Status.Code.PERMISSION_DENIED, rejection(policy, ALLOWED).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED, rejection(policy, OTHER).getStatus().getCode());
    }

    @Test
    void nullAllowlistDeniesEveryTenant() {
        GrpcSecurityProperties properties = properties(List.of());
        properties.setAllowedTenantIds(null);

        assertEquals(Status.Code.PERMISSION_DENIED,
                rejection(new GrpcTenantAccessPolicy(properties), ALLOWED).getStatus().getCode());
    }

    @Test
    void blankEntriesInAllowlistAreIgnored() {
        GrpcTenantAccessPolicy policy = policy(" ", "", null, ALLOWED);

        assertEquals(UUID.fromString(ALLOWED), policy.requireAllowedTenant(ALLOWED));
        assertEquals(Status.Code.PERMISSION_DENIED, rejection(policy, OTHER).getStatus().getCode());
    }

    @Test
    void nullTenantIsInvalidArgument() {
        StatusRuntimeException ex = rejection(policy(ALLOWED), null);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void blankTenantIsInvalidArgument(String raw) {
        StatusRuntimeException ex = rejection(policy(ALLOWED), raw);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1111", "11111111-1111-1111-1111-11111111111Z",
            "11111111-1111-1111-1111-111111111111-extra", "'; DROP TABLE x;--"})
    void malformedTenantIsInvalidArgument(String raw) {
        StatusRuntimeException ex = rejection(policy(ALLOWED), raw);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    }

    @Test
    void invalidUuidInConfigurationFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> policy(ALLOWED, "not-a-uuid"));

        assertFalse(String.valueOf(ex.getMessage()).contains("not-a-uuid"));
    }

    @ParameterizedTest
    @CsvSource({
            "-5, 10",
            "0, 10",
            "1, 1",
            "10, 10",
            "50, 50",
            "51, 50",
            "100000, 50",
            "2147483647, 50"
    })
    void clampLimitAppliesDefaultAndMaximum(int requested, int expected) {
        assertEquals(expected, policy(ALLOWED).clampLimit(requested));
    }

    @Test
    void clampLimitNeverExceedsMaximumEvenWhenDefaultIsHigher() {
        GrpcSecurityProperties properties = properties(List.of(ALLOWED));
        properties.setMaxCatalogLimit(5);
        properties.setDefaultCatalogLimit(20);
        GrpcTenantAccessPolicy policy = new GrpcTenantAccessPolicy(properties);

        assertEquals(5, policy.clampLimit(0));
        assertEquals(5, policy.clampLimit(-1));
        assertEquals(3, policy.clampLimit(3));
        assertEquals(5, policy.clampLimit(6));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "latte|latte",
            "50% off|50\\% off",
            "a_b|a\\_b",
            "%|\\%",
            "_|\\_",
            "%%__|\\%\\%\\_\\_",
            "mixed %_ text|mixed \\%\\_ text",
            "kopi susu|kopi susu"
    })
    void escapeLikePatternEscapesWildcards(String input, String expected) {
        assertEquals(expected, GrpcTenantAccessPolicy.escapeLikePattern(input));
    }

    @Test
    void escapeLikePatternEscapesBackslashBeforeWildcards() {
        assertEquals("\\\\", GrpcTenantAccessPolicy.escapeLikePattern("\\"));
        assertEquals("a\\\\\\%b", GrpcTenantAccessPolicy.escapeLikePattern("a\\%b"));
        assertEquals("\\\\\\\\", GrpcTenantAccessPolicy.escapeLikePattern("\\\\"));
    }

    @Test
    void escapeLikePatternHandlesNullAndEmpty() {
        assertEquals("", GrpcTenantAccessPolicy.escapeLikePattern(null));
        assertEquals("", GrpcTenantAccessPolicy.escapeLikePattern(""));
    }

    @Test
    void escapeLikePatternLeavesPlainTextAndUnicodeUntouched() {
        assertEquals("Es Teh Manis 123", GrpcTenantAccessPolicy.escapeLikePattern("Es Teh Manis 123"));
        assertEquals("kopi ☕", GrpcTenantAccessPolicy.escapeLikePattern("kopi ☕"));
    }

    @Test
    void shortTenantRefTruncatesToEightCharacters() {
        assertEquals("11111111", GrpcTenantAccessPolicy.shortTenantRef(ALLOWED));
        assertEquals("11111111", GrpcTenantAccessPolicy.shortTenantRef("  " + ALLOWED + "  "));
        assertEquals("abc", GrpcTenantAccessPolicy.shortTenantRef("abc"));
        assertEquals("12345678", GrpcTenantAccessPolicy.shortTenantRef("12345678"));
    }

    @Test
    void shortTenantRefHandlesNullAndBlank() {
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef(null));
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef(""));
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef("   "));
    }
}
