package com.tcn.exile.memlogger;

import java.util.List;

public interface LogShipper {
  void shipLogs(List<String> payload);

  void stop();
}
