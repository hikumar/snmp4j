package org.snmp4j.smi;

import junit.framework.Assert;
import junit.framework.TestCase;
import org.junit.Test;

import java.net.InetSocketAddress;

public class TcpAddressTest extends TestCase {
  @Test
  public void testSocketAddressInstantiation() {
    InetSocketAddress inetSocketAddress = new InetSocketAddress(0);
    TcpAddress tcpAddress = new TcpAddress(inetSocketAddress);

    Assert.assertEquals(inetSocketAddress.getAddress(), tcpAddress.getInetAddress());
    Assert.assertEquals(inetSocketAddress.getPort(), tcpAddress.getPort());
  }
}