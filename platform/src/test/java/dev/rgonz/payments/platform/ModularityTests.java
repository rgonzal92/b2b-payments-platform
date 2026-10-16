package dev.rgonz.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(PlatformApplication.class);

  @Test
  void moduleBoundariesHold() {
    modules.verify();
  }

  // PANs stay in as few places as possible (ADR-0004): only cards, which stores a PAN with each new
  // card, and suppliers, which shows a Supplier its card once through the Remittance Advice link,
  // may ask the vault for anything. Spring Modulith's allowedDependencies limits what a module
  // uses, not who uses it, so this checks the vault's users directly.
  @Test
  void onlyCardsAndSuppliersUseTheVault() {
    assertThat(
            modules.stream()
                .filter(
                    module -> module.getDirectDependencies(modules).containsModuleNamed("vault"))
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString))
        .containsExactlyInAnyOrder("cards", "suppliers");
  }
}
