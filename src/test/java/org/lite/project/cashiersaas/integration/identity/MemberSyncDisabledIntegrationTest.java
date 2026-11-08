package org.lite.project.cashiersaas.integration.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lite.project.cashiersaas.component.MemberSyncRequestedEvent;
import org.lite.project.cashiersaas.integration.support.AbstractPostgresIntegrationTest;
import org.lite.project.cashiersaas.integration.support.ApiTestSupport;
import org.lite.project.cashiersaas.integration.support.MerchantFixture;
import org.lite.project.cashiersaas.service.MemberSyncClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * With a blank {@code app.ai-service.base-url} (the default) onboarding works and the member sync does nothing:
 * the client reports itself disabled, owns no HTTP client, and is never asked to push a member.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberSyncDisabledIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Forces the blank default so an AI_SERVICE_BASE_URL variable on the machine cannot leak into this test. */
    @DynamicPropertySource
    static void syncDisabled(DynamicPropertyRegistry registry) {
        registry.add("app.ai-service.base-url", () -> "");
        registry.add("app.ai-service.api-key", () -> "");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private DataSource dataSource;
    @MockitoSpyBean
    private MemberSyncClient memberSyncClient;

    private ApiTestSupport api;

    @BeforeAll
    void setUpAll() {
        api = new ApiTestSupport(webAppContextSetup(webApplicationContext).apply(springSecurity()).build(), dataSource);
        api.ensureFnbBusinessType();
    }

    @AfterAll
    void cleanUp() {
        api.truncateTenantData();
    }

    @Test
    void onboardingSucceedsAndNoMemberIsEverPushedWhileTheSyncIsDisabled() throws Exception {
        assertFalse(memberSyncClient.isEnabled());

        MerchantFixture merchant = api.onboardOwner("disabled");

        assertEquals("ACTIVE", api.jdbc().queryForObject("SELECT status FROM tenants WHERE id = ?", String.class,
                merchant.tenantId()));
        verify(memberSyncClient, never()).sync(any(MemberSyncRequestedEvent.class));
    }
}
