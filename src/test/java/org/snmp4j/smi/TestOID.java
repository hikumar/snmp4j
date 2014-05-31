/*_############################################################################
  _## 
  _##  SNMP4J 2 - TestOID.java  
  _## 
  _##  Copyright (C) 2003-2013  Frank Fock and Jochen Katz (SNMP4J.org)
  _##  
  _##  Licensed under the Apache License, Version 2.0 (the "License");
  _##  you may not use this file except in compliance with the License.
  _##  You may obtain a copy of the License at
  _##  
  _##      http://www.apache.org/licenses/LICENSE-2.0
  _##  
  _##  Unless required by applicable law or agreed to in writing, software
  _##  distributed under the License is distributed on an "AS IS" BASIS,
  _##  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  _##  See the License for the specific language governing permissions and
  _##  limitations under the License.
  _##  
  _##########################################################################*/

package org.snmp4j.smi;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.mp.SnmpConstants;

/**
 * @author Frank Fock
 * @version 1.0
 */

public class TestOID {

  private final static Logger logger = LoggerFactory.getLogger(TestOID.class);

  private OID oID = null;

  @Before
  public void setUp() throws Exception {
    /**@todo Konstruktoren verifizieren*/
    oID = new OID(SnmpConstants.usmStatsUnknownEngineIDs);
  }

  @After
  public void tearDown() throws Exception {
    oID = null;
  }

  @Test
  public void testCompareTo() {
    OID o = SnmpConstants.usmStatsNotInTimeWindows;
    int expectedReturn = 1;
    int actualReturn = oID.compareTo(o);
    Assert.assertEquals(expectedReturn, actualReturn);
    o = SnmpConstants.usmStatsUnknownEngineIDs;
    expectedReturn = 0;
    actualReturn = oID.compareTo(o);
    Assert.assertEquals(expectedReturn, actualReturn);
    o = SnmpConstants.usmStatsWrongDigests;
    expectedReturn = -1;
    actualReturn = oID.compareTo(o);
    Assert.assertEquals(expectedReturn, actualReturn);

    OID a = new OID(new int[]{ 1,2,3,6,0x80000000});
    OID b = new OID(new int[]{ 1,2,3,6,0x80000001});
    expectedReturn = 1;
    actualReturn = b.compareTo(a);
    Assert.assertEquals(expectedReturn, actualReturn);

    expectedReturn = -1;
    actualReturn = a.compareTo(b);
    Assert.assertEquals(expectedReturn, actualReturn);
  }

  @Test
  public void testLeftMostCompare() {
    OID other = SnmpConstants.snmpInASNParseErrs;
    int n = Math.min(other.size(), oID.size());
    int expectedReturn = 1;
    int actualReturn = oID.leftMostCompare(n, other);
    Assert.assertEquals(expectedReturn, actualReturn);
  }

  @Test
  public void testRightMostCompare() {
    int n = 2;
    OID other = SnmpConstants.usmStatsUnsupportedSecLevels;
    int expectedReturn = 1;
    int actualReturn = oID.rightMostCompare(n, other);
    Assert.assertEquals(expectedReturn, actualReturn);
  }

  @Test
  public void testPredecessor() {
    OID oid = new OID("1.3.6.4.1.5");
    printOIDs(oid);
    Assert.assertEquals(oid.predecessor().successor(), oid);
    oid = new OID("1.3.6.4.1.5.0");
    printOIDs(oid);
    Assert.assertEquals(oid.predecessor().successor(), oid);
    oid = new OID("1.3.6.4.1.5.2147483647");
    printOIDs(oid);
    Assert.assertEquals(oid.predecessor().successor(), oid);
  }

  private static void printOIDs(OID oid) {
    if (logger.isDebugEnabled()) {
      logger.debug("OID={}, predecessor={},successor={}", oid, oid.predecessor(), oid.successor());
    }
  }

  @Test
  public void testStartsWith() {
    OID other = new OID(SnmpConstants.usmStatsDecryptionErrors.getValue());
    other.removeLast();
    other.removeLast();
    boolean expectedReturn = true;
    boolean actualReturn = oID.startsWith(other);
    Assert.assertEquals("Return value", expectedReturn, actualReturn);

    other = new OID(SnmpConstants.usmStatsUnknownEngineIDs.getValue());
    expectedReturn = true;
    actualReturn = oID.startsWith(other);
    Assert.assertEquals("Return value", expectedReturn, actualReturn);

    other = new OID(SnmpConstants.usmStatsUnknownEngineIDs.getValue());
    other.append("33.44");
    expectedReturn = false;
    actualReturn = oID.startsWith(other);
    Assert.assertEquals("Return value", expectedReturn, actualReturn);
  }

  @Test
  public void testStringParse() {
    OID a = new OID("1.3.6.2.1.5.'hallo'.1");
    OID b = new OID("1.3.6.2.1.5.104.97.108.108.111.1");
    Assert.assertEquals(a, b);
    a = new OID("1.3.6.2.1.5.'hal.lo'.1");
    b = new OID("1.3.6.2.1.5.104.97.108.46.108.111.1");
    Assert.assertEquals(a, b);
    a = new OID("1.3.6.2.1.5.'hal.'.'''.'lo'.1");
    b = new OID("1.3.6.2.1.5.104.97.108.46.39.108.111.1");
    Assert.assertEquals(a, b);
  }

}
