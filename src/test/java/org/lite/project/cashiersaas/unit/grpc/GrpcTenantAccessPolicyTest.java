package org.lite.project.cashiersaas.unit.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;
import org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy;
import org.lite.project.cashiersaas.repository.MembershipOutletAccessRepository;
import org.lite.project.cashiersaas.repository.TenantRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class GrpcTenantAccessPolicyTest {

    /** Active tenant with F&B enabled: the repository reports it as allowed. */
    private static final String ACTIVE = "11111111-1111-1111-1111-111111111111";
    /** Exists in the database but is inactive or not F&B: the repository reports false, like an unknown id. */
    private static final String INACTIVE = "22222222-2222-2222-2222-222222222222";
    /** Not in the database at all. */
    private static final String UNKNOWN = "33333333-3333-3333-3333-333333333333";

    private TenantRepository tenantRepository;
    private MembershipOutletAccessRepository outletAccessRepository;
    private GrpcTenantAccessPolicy policy;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        outletAccessRepository = mock(MembershipOutletAccessRepository.class);
        when(tenantRepository.existsActiveFnbTenant(UUID.fromString(ACTIVE))).thenReturn(true);
        when(tenantRepository.existsActiveFnbTenant(UUID.fromString(INACTIVE))).thenReturn(false);
        when(tenantRepository.existsActiveFnbTenant(UUID.fromString(UNKNOWN))).thenReturn(false);
        policy = policy(properties());
    }

    private GrpcSecurityProperties properties() {
        GrpcSecurityProperties properties = new GrpcSecurityProperties();
        properties.setServiceKey("unit-test-internal-service-key-0001");
        return properties;
    }

    private GrpcTenantAccessPolicy policy(GrpcSecurityProperties properties) {
        return new GrpcTenantAccessPolicy(tenantRepository, outletAccessRepository, properties);
    }

    private static StatusRuntimeException rejection(GrpcTenantAccessPolicy policy, String raw) {
        return assertThrows(StatusRuntimeException.class, () -> policy.requireAllowedTenant(raw));
    }

    private static final UUID USER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Test
    void acceptsActiveTenantFoundInTheDatabase() {
        assertEquals(UUID.fromString(ACTIVE), policy.requireAllowedTenant(ACTIVE));
        verify(tenantRepository).existsActiveFnbTenant(UUID.fromString(ACTIVE));
    }

    @Test
    void acceptsActiveTenantWithSurroundingWhitespace() {
        assertEquals(UUID.fromString(ACTIVE), policy.requireAllowedTenant("  " + ACTIVE + "\t\n"));
    }

    @Test
    void acceptsUpperCaseUuidOfAnActiveTenant() {
        assertEquals(UUID.fromString(ACTIVE), policy.requireAllowedTenant(ACTIVE.toUpperCase()));
        verify(tenantRepository).existsActiveFnbTenant(UUID.fromString(ACTIVE));
    }

    @Test
    void inactiveTenantIsPermissionDeniedWithoutLeakingTheRequestedId() {
        StatusRuntimeException ex = rejection(policy, INACTIVE);

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        String description = String.valueOf(ex.getStatus().getDescription());
        assertFalse(description.contains(INACTIVE));
        assertFalse(description.contains(ACTIVE));
        assertFalse(description.contains("2222"));
    }

    @Test
    void unknownTenantIsPermissionDeniedWithoutLeakingTheRequestedId() {
        StatusRuntimeException ex = rejection(policy, UNKNOWN);

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains(UNKNOWN));
    }

    @Test
    void unknownAndInactiveTenantsAreIndistinguishable() {
        StatusRuntimeException unknown = rejection(policy, UNKNOWN);
        StatusRuntimeException inactive = rejection(policy, INACTIVE);

        assertEquals(unknown.getStatus().getCode(), inactive.getStatus().getCode());
        assertEquals(unknown.getStatus().getDescription(), inactive.getStatus().getDescription());
        assertEquals("Tenant is not permitted for this service", unknown.getStatus().getDescription());
    }

    @Test
    void repositoryFailureIsNeverTreatedAsPermission() {
        UUID failing = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(tenantRepository.existsActiveFnbTenant(failing)).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> policy.requireAllowedTenant(failing.toString()));
    }

    @Test
    void nullTenantIsInvalidArgumentWithoutTouchingTheDatabase() {
        StatusRuntimeException ex = rejection(policy, null);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        verifyNoInteractions(tenantRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void blankTenantIsInvalidArgumentWithoutTouchingTheDatabase(String raw) {
        StatusRuntimeException ex = rejection(policy, raw);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        verifyNoInteractions(tenantRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1111", "11111111-1111-1111-1111-11111111111Z",
            "11111111-1111-1111-1111-111111111111-extra", "'; DROP TABLE x;--"})
    void malformedTenantIsInvalidArgumentWithoutTouchingTheDatabase(String raw) {
        StatusRuntimeException ex = rejection(policy, raw);

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        verifyNoInteractions(tenantRepository);
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
        assertEquals(expected, policy.clampLimit(requested));
    }

    @Test
    void clampLimitNeverExceedsMaximumEvenWhenDefaultIsHigher() {
        GrpcSecurityProperties properties = properties();
        properties.setMaxCatalogLimit(5);
        properties.setDefaultCatalogLimit(20);
        GrpcTenantAccessPolicy limited = policy(properties);

        assertEquals(5, limited.clampLimit(0));
        assertEquals(5, limited.clampLimit(-1));
        assertEquals(3, limited.clampLimit(3));
        assertEquals(5, limited.clampLimit(6));
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
        assertEquals("11111111", GrpcTenantAccessPolicy.shortTenantRef(ACTIVE));
        assertEquals("11111111", GrpcTenantAccessPolicy.shortTenantRef("  " + ACTIVE + "  "));
        assertEquals("abc", GrpcTenantAccessPolicy.shortTenantRef("abc"));
        assertEquals("12345678", GrpcTenantAccessPolicy.shortTenantRef("12345678"));
    }

    @Test
    void shortTenantRefHandlesNullAndBlank() {
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef(null));
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef(""));
        assertEquals("none", GrpcTenantAccessPolicy.shortTenantRef("   "));
    }
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "	"})
    void blankUserIsInvalidArgumentWithoutTouchingTheDatabase(String raw) {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> policy.requireMemberOutlets(UUID.fromString(ACTIVE), raw));

        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        verifyNoInteractions(outletAccessRepository);
    }

    @Test
    void nullAndMalformedUserAreInvalidArgument() {
        UUID tenant = UUID.fromString(ACTIVE);
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> policy.requireMemberOutlets(tenant, null)).getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> policy.requireMemberOutlets(tenant, "not-a-uuid")).getStatus().getCode());
        verifyNoInteractions(outletAccessRepository);
    }

    @Test
    void userWithoutActiveOutletAccessIsPermissionDeniedWithTheTenantMessage() {
        UUID tenant = UUID.fromString(ACTIVE);
        when(outletAccessRepository.findActiveOutletIds(tenant, USER)).thenReturn(List.of());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> policy.requireMemberOutlets(tenant, USER.toString()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertEquals("Tenant is not permitted for this service", ex.getStatus().getDescription());
        assertFalse(String.valueOf(ex.getStatus().getDescription()).contains(USER.toString()));
    }

    @Test
    void memberOutletsAreReturnedForAnActiveMember() {
        UUID tenant = UUID.fromString(ACTIVE);
        when(outletAccessRepository.findActiveOutletIds(tenant, USER)).thenReturn(List.of(OUTLET));

        assertEquals(List.of(OUTLET), policy.requireMemberOutlets(tenant, "  " + USER.toString().toUpperCase() + " "));
    }
}
