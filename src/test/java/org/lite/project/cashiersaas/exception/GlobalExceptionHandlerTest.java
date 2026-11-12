package org.lite.project.cashiersaas.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("unit")
class GlobalExceptionHandlerTest {

    private static final String CONFLICT_MESSAGE = "Data baru saja diubah oleh proses lain. Silakan coba lagi.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ThrowingController()).setControllerAdvice(handler).build();
    }

    @Test
    void optimisticLockFailureIsReturnedAsConflictResponse() {
        ApiResponse<Object> response = handler.handleOptimisticLockConflict(
                new ObjectOptimisticLockingFailureException(Object.class, "id"));

        assertEquals(409, response.getCode());
        assertFalse(response.isSuccess());
        assertEquals(CONFLICT_MESSAGE, response.getMessage());
    }

    @Test
    void optimisticLockHandlerDeclaresConflictStatus() throws Exception {
        ResponseStatus responseStatus = GlobalExceptionHandler.class
                .getMethod("handleOptimisticLockConflict",
                        org.springframework.dao.OptimisticLockingFailureException.class)
                .getAnnotation(ResponseStatus.class);

        assertEquals(HttpStatus.CONFLICT, responseStatus.value());
    }

    @Test
    void optimisticLockFailureIsMappedToHttp409() throws Exception {
        mockMvc.perform(get("/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE));
    }

    @Test
    void unexpectedExceptionIsStillHttp500() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/lock")
        String lock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("x");
        }
    }
}
