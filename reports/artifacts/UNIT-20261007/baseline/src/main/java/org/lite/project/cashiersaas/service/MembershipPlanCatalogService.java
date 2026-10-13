package org.lite.project.cashiersaas.service;

import org.lite.project.cashiersaas.dto.response.MembershipPlanResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MembershipPlanCatalogService {

    public List<MembershipPlanResponse> getAvailablePlans() {
        return List.of(
                MembershipPlanResponse.of("FREETIER", "Freetier"),
                MembershipPlanResponse.of("PREMIUM", "Premium"),
                MembershipPlanResponse.of("PRO", "Pro")
        );
    }
}
