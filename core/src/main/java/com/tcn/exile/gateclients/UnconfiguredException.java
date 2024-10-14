package com.tcn.exile.gateclients;

public class UnconfiguredException extends Exception{
  public UnconfiguredException() {
    super();
  }

  public UnconfiguredException(String message) {
    super(message);
  }
  public UnconfiguredException(String message, Throwable cause) {
    super(message, cause);
  }
  public UnconfiguredException(Throwable cause) {
    super(cause);
  }

}
