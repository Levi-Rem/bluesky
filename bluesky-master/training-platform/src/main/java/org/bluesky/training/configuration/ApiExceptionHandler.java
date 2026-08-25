package org.bluesky.training.configuration;

import org.bluesky.training.adapter.AdapterUnavailableException;
import org.bluesky.training.adapter.AdapterRejectedException;
import org.bluesky.training.display.DisplaySettingsPersistenceException;
import org.bluesky.training.display.DisplaySettingsValidationException;
import org.bluesky.training.mapdata.ReferenceDataException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(FieldValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse validation(FieldValidationException exception,
                                       HttpServletRequest request) {
        return ApiErrorResponse.fieldError(
                exception.getField(), exception.getMessage(), requestId(request));
    }

    @ExceptionHandler(DisplaySettingsValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse displaySettingsValidation(DisplaySettingsValidationException exception,
                                                       HttpServletRequest request) {
        return ApiErrorResponse.displaySettingsValidation(
                exception.getFieldErrors(), requestId(request));
    }

    @ExceptionHandler(DisplaySettingsPersistenceException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse displaySettingsPersistence(DisplaySettingsPersistenceException exception,
                                                        HttpServletRequest request) {
        return ApiErrorResponse.configurationError(exception.getMessage(), requestId(request));
    }

    @ExceptionHandler(AdapterUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiErrorResponse engineUnavailable(AdapterUnavailableException exception,
                                              HttpServletRequest request) {
        return ApiErrorResponse.engineUnavailable(exception.getMessage(), requestId(request));
    }

    @ExceptionHandler(AdapterRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiErrorResponse engineRejected(AdapterRejectedException exception,
                                           HttpServletRequest request) {
        return ApiErrorResponse.engineRejected(
                exception.getCode(), exception.getMessage(), requestId(request));
    }

    @ExceptionHandler(ReferenceDataException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse referenceData(ReferenceDataException exception,
                                          HttpServletRequest request) {
        return new ApiErrorResponse(exception.getCode(), exception.getMessage(),
                java.util.Collections.emptyList(), requestId(request));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse invalidState(RuntimeException exception,
                                        HttpServletRequest request) {
        return ApiErrorResponse.invalidState(exception.getMessage(), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "" : value.toString();
    }
}
