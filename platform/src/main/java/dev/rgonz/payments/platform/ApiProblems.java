package dev.rgonz.payments.platform;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
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

  // The generated API interfaces are @Validated, so a query or path parameter outside its schema's
  // bounds fails in their method validation, not in Spring MVC's own, which would answer 400.
  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail badParameter(ConstraintViolationException exception) {
    var detail =
        exception.getConstraintViolations().stream()
            .map(ApiProblems::describe)
            .sorted()
            .collect(Collectors.joining("; "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  // "limit must be less than or equal to 100": the parameter's name, the last node of the path
  // "listClearingFiles.limit".
  private static String describe(ConstraintViolation<?> violation) {
    String parameter = null;
    for (var node : violation.getPropertyPath()) {
      parameter = node.getName();
    }
    return parameter + " " + violation.getMessage();
  }
}
