package dev.rgonz.payments.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns errors Spring MVC doesn't already render into RFC 9457 problem details. */
@RestControllerAdvice
class ApiProblems {

  // Method security denials surface here before Spring Security's filter would answer with an empty
  // 403.
  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.FORBIDDEN, "Your role doesn't allow this operation.");
  }
}
