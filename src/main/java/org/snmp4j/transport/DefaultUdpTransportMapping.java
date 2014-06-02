/*_############################################################################
  _## 
  _##  SNMP4J 2 - DefaultUdpTransportMapping.java  
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
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.concurrent.ControlableRunnable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.nio.ByteBuffer;

/**
 * The <code>DefaultUdpTransportMapping</code> implements a UDP transport
 * mapping based on Java standard IO and using an internal thread for
 * listening on the inbound socket.
 *
 * @author Frank Fock
 * @version 1.9
 */
public class DefaultUdpTransportMapping extends UdpTransportMapping implements Closeable {
  private static final Logger logger =
      LoggerFactory.getLogger(DefaultUdpTransportMapping.class);

  private final DatagramSocket socket;
  private int receiveBufferSize = 0; // not set by default

  protected Thread listenerThread;
  protected SocketListener listener;

  /**
   * Creates a UDP transport with optional reusing the address if is currently
   * in timeout state (TIME_WAIT) after the connection is closed.
   *
   * @param udpAddress
   *    the local address for sending and receiving of UDP messages.
   * @param reuseAddress
   *    if <code>true</code> addresses are reused which provides faster socket
   *    binding if an application is restarted for instance.
   * @throws java.net.SocketException
   *    if socket binding fails.
   * @since 1.7.3
   */
  public DefaultUdpTransportMapping(UdpAddress udpAddress,
                                    boolean reuseAddress) throws SocketException {
    super(udpAddress);
    socket = new DatagramSocket(null);
    socket.setReuseAddress(reuseAddress);
    final SocketAddress addr =
        new InetSocketAddress(udpAddress.getInetAddress(),udpAddress.getPort());
    socket.bind(addr);
  }

  /**
   * Creates a UDP transport with an arbitrary local port on all local
   * interfaces.
   *
   * @throws java.net.SocketException
   *    if socket binding fails.
   * @throws java.net.UnknownHostException
   *    if the local address to bind to could not be resolved
   */
  public DefaultUdpTransportMapping() throws UnknownHostException, SocketException {
    this(new UdpAddress(InetAddress.getLocalHost(), 0), false);
  }

  /**
   * Creates a UDP transport on the specified address. The address will not be
   * reused if it is currently in timeout state (TIME_WAIT).
   *
   * @param udpAddress
   *    the local address for sending and receiving of UDP messages.
   * @throws java.net.SocketException
   *    if socket binding fails.
   */
  public DefaultUdpTransportMapping(UdpAddress udpAddress) throws SocketException {
    this(udpAddress, false);
  }

  @Override
  public synchronized UdpAddress getListenAddress() {
    return new UdpAddress(socket.getLocalAddress(), socket.getLocalPort());
  }

  @Override
  public void sendMessage(UdpAddress targetAddress, byte[] message,
                          TransportStateReference tmStateReference)
      throws IOException {
    InetSocketAddress targetSocketAddress =
        new InetSocketAddress(targetAddress.getInetAddress(),
                              targetAddress.getPort());

    if (logger.isDebugEnabled()) {
      logger.debug("Sending message to {} with length {}: {}", targetAddress, message.length, new OctetString(message).toHexString());
    }

    socket.send(new DatagramPacket(message, message.length, targetSocketAddress));
  }

  /**
   * Closes the socket and stops the listener thread.
   */
  @Override
  public synchronized void close() {
    logger.info("Close requested for {}", this);

    if (listenerThread != null) {
      listener.askToStop();
      listenerThread.interrupt();
      listenerThread = null;
      listener = null;
    }

    if (socket != null && !socket.isClosed()) {
      socket.close();
    }
  }

  /**
   * Starts the listener thread that accepts incoming messages. The
   * {@link #close()} method should be called to stop the listen thread
   * gracefully and free associated ressources.
   *
   * @throws java.net.SocketException
   *    When this method is called when the socket is closed
   */
  @Override
  public synchronized void listen() throws SocketException {
    if (socket.isClosed())
      throw new SocketException("Socket is closed");

    if (listenerThread == null) {
      listener = new SocketListener();
      listenerThread = SNMP4JSettings.getThreadFactory().newThread(listener);
      listenerThread.start();
    }

    logger.info("Now listening with {}", listener);
  }

  @Override
  public synchronized boolean isListening() {
    return (listenerThread != null);
  }

  public synchronized void setMaxInboundMessageSize(int newSize) {
    if (newSize <= 0)
      throw new IllegalArgumentException("The max inbound message size must be > 0");

    if (listenerThread.isAlive())
      throw new UnsupportedOperationException("Unable to change message size after the listen thread has been started");

    maxInboundMessageSize = newSize;
  }

  /**
   * Gets the requested receive buffer size for the underlying UDP socket.
   * This size might not reflect the actual size of the receive buffer, which
   * is implementation specific.
   * @return
   *    <=0 if the default buffer size of the OS is used, or a value >0 if the
   *    user specified a buffer size.
   */
  public synchronized int getReceiveBufferSize() {
    return receiveBufferSize;
  }

  /**
   * Sets the receive buffer size, which should be > the maximum inbound message
   * size. This method has to be called before {@link #listen()} to be
   * effective.
   * @param newSize
   *    an integer value >0 and > {@link #getMaxInboundMessageSize()}.
   */
  public synchronized void setReceiveBufferSize(int newSize) {
    if (newSize <= 0)
      throw new IllegalArgumentException("Receive buffer size must be > 0");

    if (listenerThread.isAlive())
      throw new UnsupportedOperationException("Unable to change buffer size after the listen thread has been started");

    receiveBufferSize = newSize;
  }

  private void dispatchMessage(InetSocketAddress address, byte[] message, int messageLength) {
    logger.debug("Read {} bytes from {}", messageLength, address);

    ByteBuffer bis = ByteBuffer.wrap(message);

    TransportStateReference stateReference =
        new TransportStateReference(this, udpAddress, null,
            SecurityLevel.undefined, SecurityLevel.undefined,
            false, socket);

    fireProcessMessage(new UdpAddress(address), bis, stateReference);
  }

  class SocketListener extends ControlableRunnable {
    @Override
    public void run() {
      try {
        if (receiveBufferSize > 0) {
          socket.setReceiveBufferSize(Math.max(receiveBufferSize,
              maxInboundMessageSize));
        }

        int bufferSize = socket.getReceiveBufferSize();
        logger.debug("UDP receive buffer size for {} is set to {} bytes", this, bufferSize);

        while (!shouldStop()) {
          byte[] receivedData = new byte[bufferSize];
          DatagramPacket packet = new DatagramPacket(receivedData, receivedData.length,
              udpAddress.getInetAddress(),
              udpAddress.getPort());

          socket.receive(packet);

          InetSocketAddress address = new InetSocketAddress(packet.getAddress(), packet.getPort());
          dispatchMessage(address, packet.getData(), packet.getLength());
        }
      } catch (InterruptedIOException iiox) {
        logger.info("{} was interrupted during IO and {} bytes are being discarded", this, iiox.bytesTransferred);
      } catch (SocketException ex) {
        logger.error("Could not set DatagramSockets receive buffer size", ex);
      } catch (IOException iox) {
        logger.error(iox.getMessage(), iox);
        throw new Error("An I/O error occurred when reading from socket", iox);
      } finally {
        // If the loop above returns there was either an error or it was
        // requested of us to stop. Either way, all resources should be freed.
        close();
      }

      logger.info("Thread {} is stopping", this);
    }

    @Override
    public String getName() {
      return DefaultUdpTransportMapping.class.getSimpleName() + "Listener_" + getListenAddress();
    }
  }
}
