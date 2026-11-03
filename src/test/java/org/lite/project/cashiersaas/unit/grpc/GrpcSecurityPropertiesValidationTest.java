package org.lite.project.cashiersaas.unit.grpc;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.configuration.GrpcSecurityProperties;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GrpcSecurityPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static GrpcSecurityProperties valid() {
        GrpcSecurityProperties properties = new GrpcSecurityProperties();
        properties.setServiceKey("0123456789abcdef");
        return properties;
    }

    private static Set<String> violatedProperties(GrpcSecurityProperties properties) {
        Set<ConstraintViolation<GrpcSecurityProperties>> violations = validator.validate(properties);
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void validConfigurationPassesWithDefaults() {
        GrpcSecurityProperties properties = valid();

        assertTrue(validator.validate(properties).isEmpty());
        assertEquals(50, properties.getMaxCatalogLimit());
        assertEquals(10, properties.getDefaultCatalogLimit());
        assertEquals(50, properties.getRequestsPerSecond());
        assertTrue(properties.getAllowedTenantIds().isEmpty());
    }

    @Test
    void serviceKeyOfExactlySixteenCharactersIsAccepted() {
        GrpcSecurityProperties properties = valid();
        properties.setServiceKey("a".repeat(16));

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void nullServiceKeyIsRejected() {
        GrpcSecurityProperties properties = valid();
        properties.setServiceKey(null);

        assertEquals(Set.of("serviceKey"), violatedProperties(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "                    "})
    void blankServiceKeyIsRejected(String key) {
        GrpcSecurityProperties properties = valid();
        properties.setServiceKey(key);

        assertEquals(Set.of("serviceKey"), violatedProperties(properties));
    }

    @Test
    void serviceKeyShorterThanSixteenCharactersIsRejected() {
        GrpcSecurityProperties properties = valid();
        properties.setServiceKey("a".repeat(15));

        assertEquals(Set.of("serviceKey"), violatedProperties(properties));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void requestsPerSecondBelowOneIsRejected(int value) {
        GrpcSecurityProperties properties = valid();
        properties.setRequestsPerSecond(value);

        assertEquals(Set.of("requestsPerSecond"), violatedProperties(properties));
    }

    @Test
    void catalogLimitsBelowOneAreRejected() {
        GrpcSecurityProperties properties = valid();
        properties.setMaxCatalogLimit(0);
        properties.setDefaultCatalogLimit(-5);

        assertEquals(Set.of("maxCatalogLimit", "defaultCatalogLimit"), violatedProperties(properties));
    }
}
