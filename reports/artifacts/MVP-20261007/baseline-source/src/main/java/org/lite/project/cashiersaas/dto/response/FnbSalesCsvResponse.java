package org.lite.project.cashiersaas.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude; import lombok.Getter; import lombok.Setter;
@Getter @Setter @JsonInclude(JsonInclude.Include.NON_NULL)
public class FnbSalesCsvResponse { private String filename; private String contentType; private String content; }
