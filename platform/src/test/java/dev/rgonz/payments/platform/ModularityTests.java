package dev.rgonz.payments.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

  @Test
  void moduleBoundariesHold() {
    ApplicationModules.of(PlatformApplication.class).verify();
  }
}
