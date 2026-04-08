package com.tcn.exile.model;

public record Filter(String field, Operator operator, String value) {

  public enum Operator {
    UNSPECIFIED,
    EQUAL,
    NOT_EQUAL,
    CONTAINS,
    GREATER_THAN,
    LESS_THAN,
    IN,
    EXISTS
  }
}
