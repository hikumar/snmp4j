package org.snmp4j.smi;

import junit.framework.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;

public class UdpAddressTest {
  @Test
  public void testSocketAddressInstantiation() {
    InetSocketAddress inetSocketAddress = new InetSocketAddress(0);
    UdpAddress address = new UdpAddress(inetSocketAddress);

    Assert.assertEquals(inetSocketAddress.getAddress(), address.getInetAddress());
    Assert.assertEquals(inetSocketAddress.getPort(), address.getPort());
  }
}