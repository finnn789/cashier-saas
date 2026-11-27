package org.lite.project.cashiersaas.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Midtrans credentials, supplied only through environment variables (MIDTRANS_*). The server key is never logged or returned. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cashier.billing.midtrans")
public class MidtransProperties {
    private String serverKey;
    private String merchantId;
    private boolean production;
    private String finishUrl;
}
