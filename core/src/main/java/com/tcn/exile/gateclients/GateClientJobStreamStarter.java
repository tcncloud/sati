package com.tcn.exile.gateclients;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class GateClientJobStreamStarter implements ApplicationEventListener<StartupEvent> {
  @Inject
  private GateClientJobStream streamClient;

  @Override
  public void onApplicationEvent(StartupEvent event) {

  }
}
