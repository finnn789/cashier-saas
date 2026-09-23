package org.lite.project.cashiersaas.exception;

import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.exception.runtime.BusinessProfileAlreadyCompletedException;
import org.lite.project.cashiersaas.exception.runtime.DataIntegrityViolationRuntimeException;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.shared.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleInvalidCredentials(InvalidCredentialsException exception) {
        log.warn("Authentication failed due to invalid credentials.");
        return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), exception.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException exception) {
        log.warn("Registration rejected because the email is already registered.");
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationRuntimeException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleDataIntegrityViolation(DataIntegrityViolationRuntimeException exception) {
        log.error("Data persistence failed because a database integrity rule was violated.");
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleDatabaseConflict(DataIntegrityViolationException exception) {
        log.warn("Operation rejected by a database integrity rule.");
        return ApiResponse.error(HttpStatus.CONFLICT.value(), "Data sudah berubah atau terjadi duplikasi.");
    }

    @ExceptionHandler(BusinessProfileAlreadyCompletedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleBusinessProfileAlreadyCompleted(BusinessProfileAlreadyCompletedException exception) {
        log.warn("Business profile completion rejected because the profile is already complete.");
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiResponse.FieldErrorDetail> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldErrorResponse)
                .toList();

        log.warn("Request validation failed: fieldErrorCount={}", errors.size());
        return ApiResponse.validationError(HttpStatus.BAD_REQUEST.value(), "Request tidak valid.", errors);
    }

    @ExceptionHandler(InvalidOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleInvalidOperation(InvalidOperationException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), exception.getMessage());
    }

    @ExceptionHandler(OperationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleOperationConflict(OperationConflictException exception) {
        return ApiResponse.error(HttpStatus.CONFLICT.value(), exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Object> handleResourceNotFound(ResourceNotFoundException exception) {
        return ApiResponse.error(HttpStatus.NOT_FOUND.value(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Object> handleUnexpected(Exception exception) {
        log.error("Unhandled application exception", exception);
        return ApiResponse.internalServerError();
    }

    private ApiResponse.FieldErrorDetail toFieldErrorResponse(FieldError fieldError) {
        return new ApiResponse.FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage());
    }

}
