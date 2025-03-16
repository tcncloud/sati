package com.tcn.exile.memlogger;

public interface LogShipper {
  void shipLogs(String payload);

  void stop();
}
