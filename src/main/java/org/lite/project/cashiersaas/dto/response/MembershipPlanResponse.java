package org.lite.project.cashiersaas.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MembershipPlanResponse {

    private String code;
    private String name;

    public static MembershipPlanResponse of(String code, String name) {
        MembershipPlanResponse response = new MembershipPlanResponse();
        response.setCode(code);
        response.setName(name);
        return response;
    }
}
