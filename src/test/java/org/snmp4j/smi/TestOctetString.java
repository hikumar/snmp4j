/*_############################################################################
  _## 
  _##  SNMP4J 2 - TestOctetString.java  
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

import java.util.Collection;
import java.util.StringTokenizer;

public class TestOctetString {
  private OctetString octetString = null;

  @Before
  public void setUp() throws Exception {
    octetString = new OctetString();
  }

  @After
  public void tearDown() throws Exception {
    octetString = null;
  }

  @Test
  public void testConstructors() {
    byte[] ba = {
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'};

    octetString = new OctetString(ba);

    Assert.assertEquals(octetString.toString(), "abcdefghi");

    octetString = new OctetString(ba, 2, 2);
    Assert.assertEquals(octetString.toString(), "cd");
  }

  @Test
  public void testSlip() {
    String s = "A short string with several delimiters  and a short word!";
    OctetString sp = new OctetString(s);
    Collection<OctetString> words = OctetString.split(sp, new OctetString("! "));
    StringTokenizer st = new StringTokenizer(s, "! ");
    for (OctetString os : words) {
      Assert.assertEquals(os.toString(), st.nextToken());
    }
    Assert.assertFalse(st.hasMoreTokens());
  }

  @Test
  public void testIsPrintable() {
    OctetString nonPrintable = OctetString.fromHexString("1C:32:41:1C:4E:38");
    Assert.assertFalse(nonPrintable.isPrintable());
  }
}
