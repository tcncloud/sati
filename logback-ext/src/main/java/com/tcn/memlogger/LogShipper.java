package com.tcn.memlogger;

public interface LogShipper {
  void shipLogs(String payload);

  void stop();
}
