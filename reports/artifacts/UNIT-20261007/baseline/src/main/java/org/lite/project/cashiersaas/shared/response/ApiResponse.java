package org.lite.project.cashiersaas.shared.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private int code;
    private String message;
    private T data;
    private List<FieldErrorDetail> validationErrors;
    private LocalDateTime timestamp;

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, 200, message, data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> created(String message, T data) {
        return new ApiResponse<>(true, 201, message, data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> accepted(String message, T data) {
        return new ApiResponse<>(true, 202, message, data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> partialContent(String message, T data) {
        return new ApiResponse<>(true, 206, message, data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(false, code, message, null, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> validationError(int code, String message,
                                                       List<FieldErrorDetail> validationErrors) {
        return new ApiResponse<>(false, code, message, null, validationErrors, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> internalServerError() {
        return error(500, "Terjadi kesalahan pada sistem.");
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldErrorDetail {

        private String field;
        private String message;
    }
}
