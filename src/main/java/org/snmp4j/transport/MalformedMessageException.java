package org.snmp4j.transport;

import java.io.IOException;

public class MalformedMessageException extends IOException {
  public MalformedMessageException(String message) {
    super(message);
  }
}
