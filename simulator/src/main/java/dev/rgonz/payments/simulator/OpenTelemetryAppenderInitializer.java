package dev.rgonz.payments.simulator;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

// Spring Boot exports logs over OTLP but leaves connecting the Logback appender to the app.
@Component
class OpenTelemetryAppenderInitializer implements InitializingBean {

  private final OpenTelemetry openTelemetry;

  OpenTelemetryAppenderInitializer(OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  @Override
  public void afterPropertiesSet() {
    OpenTelemetryAppender.install(openTelemetry);
  }
}
