package org.lite.project.cashiersaas.exception;

import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.exception.runtime.BusinessProfileAlreadyCompletedException;
import org.lite.project.cashiersaas.exception.runtime.DataIntegrityViolationRuntimeException;
import org.lite.project.cashiersaas.exception.runtime.EmailAlreadyRegisteredException;
import org.lite.project.cashiersaas.exception.runtime.InvalidCredentialsException;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.exception.runtime.AuthenticationRequiredException;
import org.lite.project.cashiersaas.exception.runtime.AccessForbiddenException;
import org.lite.project.cashiersaas.shared.AuthenticationConstants;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

    @ExceptionHandler(AuthenticationRequiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleAuthenticationRequired(AuthenticationRequiredException exception,
                                                           HttpServletResponse response) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, AuthenticationConstants.BEARER_SCHEME);
        return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), exception.getMessage());
    }

    @ExceptionHandler(AccessForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Object> handleAccessForbidden(AccessForbiddenException exception) {
        return ApiResponse.error(HttpStatus.FORBIDDEN.value(), exception.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleSecurityAuthentication(AuthenticationException exception,
                                                           HttpServletResponse response) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, AuthenticationConstants.BEARER_SCHEME);
        return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), ErrorMessage.AUTHENTICATION_INVALID.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Object> handleSecurityAccessDenied(AccessDeniedException exception) {
        return ApiResponse.error(HttpStatus.FORBIDDEN.value(), ErrorMessage.ACCESS_FORBIDDEN.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleInvalidCredentials(InvalidCredentialsException exception,
                                                       HttpServletResponse response) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, AuthenticationConstants.BEARER_SCHEME);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "Body permintaan wajib diisi dengan JSON yang valid dan tipe data yang sesuai.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleMissingHeader(MissingRequestHeaderException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "Header '" + exception.getHeaderName() + "' wajib diisi.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleMissingParameter(MissingServletRequestParameterException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "Parameter '" + exception.getParameterName() + "' wajib diisi.");
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleRequestBinding(ServletRequestBindingException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Header, cookie, atau parameter permintaan tidak valid.");
    }

    @ExceptionHandler(TypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleTypeMismatch(TypeMismatchException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Tipe data parameter permintaan tidak sesuai.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleConstraintValidation(ConstraintViolationException exception) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "Parameter permintaan tidak memenuhi aturan validasi.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Object> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception,
                                                      HttpServletResponse response) {
        String[] supportedMethods = exception.getSupportedMethods();
        if (supportedMethods != null) {
            response.setHeader(HttpHeaders.ALLOW, String.join(", ", supportedMethods));
        }
        return ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Metode HTTP '" + exception.getMethod() + "' tidak didukung untuk endpoint ini.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<Object> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ApiResponse.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "Content-Type permintaan tidak didukung.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    public ApiResponse<Object> handleUnacceptableMediaType(HttpMediaTypeNotAcceptableException exception) {
        return ApiResponse.error(HttpStatus.NOT_ACCEPTABLE.value(), "Format respons pada header Accept tidak didukung.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Object> handleMissingEndpoint(NoResourceFoundException exception) {
        return ApiResponse.error(HttpStatus.NOT_FOUND.value(), "Endpoint tidak ditemukan.");
    }

}
