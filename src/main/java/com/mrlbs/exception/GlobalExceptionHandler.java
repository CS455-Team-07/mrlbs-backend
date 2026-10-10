package com.mrlbs.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Returns 404 for requests to unmapped routes instead of letting the
     * broad Exception handler turn them into a misleading 500.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ProblemDetail handleNoHandlerFound(NoHandlerFoundException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception: {}", exception.getMessage(), exception);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                exception.getMessage() != null ? exception.getMessage() : "An unexpected error occurred.");
    }
}
