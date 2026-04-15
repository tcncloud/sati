package com.tcn.exile;

import static org.junit.jupiter.api.Assertions.*;

import com.tcn.exile.handler.Plugin;
import com.tcn.exile.handler.ResourceLimit;
import com.tcn.exile.service.ConfigService;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * Exercises the C4 capacity-provider selection logic via the package-private {@link
 * ExileClient#chooseCapacityProvider} helper, avoiding the heavy ChannelFactory path that would
 * require real mTLS certs at test setup.
 */
class ExileClientBuilderTest {

  @Test
  void defaultBuilderProducesAdaptiveCapacity() {
    var choice = choose(builder());
    assertNotNull(choice.adaptive(), "default builder should construct an AdaptiveCapacity");
    assertSame(choice.adaptive(), choice.provider(), "provider should be the adaptive instance");
  }

  @Test
  void defaultBoundsAreMin1Initial10Max100() {
    var choice = choose(builder());
    var a = choice.adaptive();
    assertEquals(1, a.minLimit());
    assertEquals(100, a.maxLimit(), "C4 raised the default max from 5 to 100");
    assertEquals(10, a.limit(), "initial limit should match builder.initialConcurrency");
  }

  @Test
  void explicitCapacityProviderWins() {
    IntSupplier custom = () -> 42;
    var choice = choose(builder().capacityProvider(custom));
    assertNull(choice.adaptive(), "adaptive should be null when explicit provider is set");
    assertSame(custom, choice.provider(), "provider should be the explicit one");
    assertEquals(42, choice.provider().getAsInt());
  }

  @Test
  void adaptiveFalseFallsBackToAvailableCapacity() {
    var choice = choose(builder().adaptive(false));
    assertNull(choice.adaptive(), "adaptive(false) should leave adaptive null");
    // The provider should call plugin.availableCapacity(). NoopPlugin returns MAX_VALUE by default.
    assertEquals(Integer.MAX_VALUE, choice.provider().getAsInt());
  }

  @Test
  void customBoundsPropagateToController() {
    var choice = choose(builder().minConcurrency(5).initialConcurrency(20).maxConcurrency(200));
    var a = choice.adaptive();
    assertNotNull(a);
    assertEquals(5, a.minLimit());
    assertEquals(200, a.maxLimit());
    assertEquals(20, a.limit());
  }

  @Test
  void pluginResourceLimitsFeedIntoAdaptive() {
    var plugin =
        new NoopPlugin() {
          @Override
          public List<ResourceLimit> resourceLimits() {
            return List.of(ResourceLimit.capOnly("db_pool", 4));
          }
        };
    var choice = ExileClient.chooseCapacityProvider(builder(), plugin);
    assertEquals(
        4,
        choice.adaptive().effectiveCeiling(),
        "effectiveCeiling should reflect the plugin's hardMax");
  }

  @Test
  void builderRejectsInvalidBounds() {
    assertThrows(IllegalArgumentException.class, () -> ExileClient.builder().maxConcurrency(0));
    assertThrows(IllegalArgumentException.class, () -> ExileClient.builder().minConcurrency(0));
    assertThrows(IllegalArgumentException.class, () -> ExileClient.builder().initialConcurrency(0));
  }

  // --- helpers ---

  private static ExileClient.Builder builder() {
    return ExileClient.builder().clientName("test").clientVersion("0.0.0");
  }

  private static ExileClient.CapacityChoice choose(ExileClient.Builder b) {
    return ExileClient.chooseCapacityProvider(b, new NoopPlugin());
  }

  private static class NoopPlugin implements Plugin {
    @Override
    public boolean onConfig(ConfigService.ClientConfiguration cfg) {
      return true;
    }
  }
}
