/*_############################################################################
  _## 
  _##  SNMP4J 2 - TcpTransportMapping.java  
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


package org.snmp4j.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.SNMP4JSettings;
import org.snmp4j.TransportStateReference;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.TcpAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;



/**
 * The <code>TcpTransportMapping</code> is the abstract base class for
 * TCP transport mappings.
 *
 * @author Frank Fock
 * @version 1.7
 */
public abstract class TcpTransportMapping
    extends AbstractTransportMapping<TcpAddress>
    implements ConnectionOrientedTransportMapping<TcpAddress>
{

  private static final Logger logger =
      LoggerFactory.getLogger(TcpTransportMapping.class);

  protected TcpAddress tcpAddress;
  private transient Vector<TransportStateListener> transportStateListeners;

  public TcpTransportMapping(TcpAddress tcpAddress) {
    this.tcpAddress = tcpAddress;
  }

  public Class<? extends Address> getSupportedAddressClass() {
    return TcpAddress.class;
  }

  /**
   * Returns the transport address that is used by this transport mapping for
   * sending and receiving messages.
   * @return
   *    the <code>Address</code> used by this transport mapping. The returned
   *    instance must not be modified!
   */
  public TcpAddress getAddress() {
    return tcpAddress;
  }

  public TcpAddress getListenAddress() {
    return tcpAddress;
  }

  public synchronized void addTransportStateListener(TransportStateListener l) {
    if (transportStateListeners == null) {
      transportStateListeners = new Vector<>(2);
    }
    transportStateListeners.add(l);
  }

  public synchronized void removeTransportStateListener(TransportStateListener
      l) {
    if (transportStateListeners != null) {
      transportStateListeners.remove(l);
    }
  }

  protected void fireConnectionStateChanged(TransportStateEvent change) {
    if (logger.isDebugEnabled()) {
      logger.debug("Firing transport state event: {}", change);
    }
    final List<TransportStateListener> listenersFinalRef = transportStateListeners;
    if (listenersFinalRef != null) {
      try {
        List<TransportStateListener> listeners;
        synchronized (listenersFinalRef) {
          listeners = new ArrayList<>(listenersFinalRef);
        }
        for (TransportStateListener listener : listeners) {
          listener.connectionStateChanged(change);
        }
      }
      catch (RuntimeException ex) {
        logger.error("Exception in fireConnectionStateChanged: {}", ex.getMessage(), ex);
        if (SNMP4JSettings.isForwardRuntimeExceptions()) {
          throw ex;
        }
      }
    }
  }
}
