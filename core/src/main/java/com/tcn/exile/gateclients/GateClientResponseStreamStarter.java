package com.tcn.exile.gateclients;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;

public class GateClientResponseStreamStarter  implements ApplicationEventListener<StartupEvent> {
  @Inject
  private GateClientResponseStream streamClient;

  @Override
  public void onApplicationEvent(StartupEvent event) {

  }
}
