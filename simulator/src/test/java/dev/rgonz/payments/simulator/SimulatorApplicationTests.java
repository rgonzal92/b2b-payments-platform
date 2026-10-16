package dev.rgonz.payments.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "simulator.seed.enabled=false")
class SimulatorApplicationTests {

  @Test
  void contextLoads() {}
}
