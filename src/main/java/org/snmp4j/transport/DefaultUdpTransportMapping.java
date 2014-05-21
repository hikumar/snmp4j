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

  protected DatagramSocket socket = null;
  private int socketTimeout = 0;
  private int receiveBufferSize = 0; // not set by default

  protected Thread listenerThread;
  protected SocketListener listener;

  /**
   * Creates a UDP transport with an arbitrary local port on all local
   * interfaces.
   *
   * @throws IOException
   *    if socket binding fails.
   */
  public DefaultUdpTransportMapping() throws UnknownHostException, SocketException {
    super(new UdpAddress(InetAddress.getLocalHost(), 0));
    socket = new DatagramSocket(udpAddress.getPort());
  }

  /**
   * Creates a UDP transport with optional reusing the address if is currently
   * in timeout state (TIME_WAIT) after the connection is closed.
   *
   * @param udpAddress
   *    the local address for sending and receiving of UDP messages.
   * @param reuseAddress
   *    if <code>true</code> addresses are reused which provides faster socket
   *    binding if an application is restarted for instance.
   * @throws IOException
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
   * Creates a UDP transport on the specified address. The address will not be
   * reused if it is currently in timeout state (TIME_WAIT).
   *
   * @param udpAddress
   *    the local address for sending and receiving of UDP messages.
   * @throws IOException
   *    if socket binding fails.
   */
  public DefaultUdpTransportMapping(UdpAddress udpAddress) throws SocketException {
    super(udpAddress);
    socket = new DatagramSocket(udpAddress.getPort(),
                                udpAddress.getInetAddress());
  }

  public void sendMessage(UdpAddress targetAddress, byte[] message,
                          TransportStateReference tmStateReference)
      throws IOException {
    InetSocketAddress targetSocketAddress =
        new InetSocketAddress(targetAddress.getInetAddress(),
                              targetAddress.getPort());
    if (logger.isDebugEnabled()) {
      logger.debug("Sending message to {} with length {}: {}", targetAddress, message.length, new OctetString(message).toHexString());
    }
    DatagramSocket s = ensureSocket();
    s.send(new DatagramPacket(message, message.length, targetSocketAddress));
  }

  /**
   * Closes the socket and stops the listener thread.
   */
  public synchronized void close() {
    if (listenerThread != null) {
      listener.askToStop();
      listenerThread.interrupt();
      listenerThread = null;
      listener = null;
    }

    if (socket != null && !socket.isClosed()) {
      socket.close();
      socket = null;
    }
  }

  /**
   * Starts the listener thread that accepts incoming messages. The thread is
   * started in daemon mode and thus it will not block application terminated.
   * Nevertheless, the {@link #close()} method should be called to stop the
   * listen thread gracefully and free associated ressources.
   *
   * @throws IOException
   */
  public synchronized void listen() throws SocketException {
    if (listenerThread != null) {
      throw new SocketException("Port already listening");
    }
    ensureSocket();
    listener = new SocketListener();
    listenerThread = SNMP4JSettings.getThreadFactory().newThread(listener);
    listenerThread.start();
  }

  private synchronized DatagramSocket ensureSocket() throws SocketException {
    DatagramSocket s = socket;
    if (s == null) {
      s = new DatagramSocket(udpAddress.getPort());
      s.setSoTimeout(socketTimeout);
      this.socket = s;
    }
    return s;
  }

  public void setMaxInboundMessageSize(int maxInboundMessageSize) {
    this.maxInboundMessageSize = maxInboundMessageSize;
  }

  /**
   * Returns the socket timeout.
   * 0 returns implies that the option is disabled (i.e., timeout of infinity).
   * @return
   *    the socket timeout setting.
   */
  public int getSocketTimeout() {
    return socketTimeout;
  }

  /**
   * Gets the requested receive buffer size for the underlying UDP socket.
   * This size might not reflect the actual size of the receive buffer, which
   * is implementation specific.
   * @return
   *    <=0 if the default buffer size of the OS is used, or a value >0 if the
   *    user specified a buffer size.
   */
  public int getReceiveBufferSize() {
    return receiveBufferSize;
  }

  /**
   * Sets the receive buffer size, which should be > the maximum inbound message
   * size. This method has to be called before {@link #listen()} to be
   * effective.
   * @param receiveBufferSize
   *    an integer value >0 and > {@link #getMaxInboundMessageSize()}.
   */
  public void setReceiveBufferSize(int receiveBufferSize) {
    if (receiveBufferSize <= 0) {
      throw new IllegalArgumentException("Receive buffer size must be > 0");
    }
    this.receiveBufferSize = receiveBufferSize;
  }

  public boolean isListening() {
    return (listenerThread != null);
  }

  @Override
  public UdpAddress getListenAddress() {
    UdpAddress actualListenAddress = null;
    DatagramSocket socketCopy = socket;
    if (socketCopy != null) {
      actualListenAddress = new UdpAddress(socketCopy.getInetAddress(), socketCopy.getLocalPort());
    }
    return actualListenAddress;
  }

  /**
   * If receiving new datagrams fails with a {@link SocketException}, this method is called to renew the
   * socket - if possible.
   * @param socketException
   *    the exception that occurred.
   * @param failedSocket
   *    the socket that caused the exception. By default, he socket will be closed
   *    in order to be able to reopen it. Implementations may also try to reuse the socket, in dependence
   *    of the <code>socketException</code>.
   * @return
   *    the new socket or <code>null</code> if the listen thread should be terminated with the provided
   *    exception.
   * @throws SocketException
   *    a new socket exception if the socket could not be renewed.
   * @since 2.2.2
   */
  protected DatagramSocket renewSocketAfterException(SocketException socketException,
                                                     DatagramSocket failedSocket) throws SocketException {
    if ((failedSocket != null) && (!failedSocket.isClosed())) {
      failedSocket.close();
    }
    DatagramSocket s = new DatagramSocket(udpAddress.getPort(), udpAddress.getInetAddress());
    s.setSoTimeout(socketTimeout);
    return s;
  }

  class SocketListener extends ControlableRunnable {
    private byte[] buf;

    public SocketListener() {
      buf = new byte[getMaxInboundMessageSize()];
    }

    public void run() {
      DatagramSocket socketCopy = socket;
      if (socketCopy != null) {
        try {
          socketCopy.setSoTimeout(getSocketTimeout());
          if (receiveBufferSize > 0) {
            socketCopy.setReceiveBufferSize(Math.max(receiveBufferSize,
                                                      maxInboundMessageSize));
          }
          if (logger.isDebugEnabled()) {
            logger.debug("UDP receive buffer size for socket {} is set to: {}", getAddress(), socketCopy.getReceiveBufferSize());
          }
        } catch (SocketException ex) {
          logger.error(ex.getMessage(), ex);
        }
      }
      while (!shouldStop()) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length,
                                                   udpAddress.getInetAddress(),
                                                   udpAddress.getPort());
        try {
          socketCopy = socket;
          try {
            if (socketCopy == null) {
              askToStop();
              continue;
            }
            socketCopy.receive(packet);
          }
          catch (InterruptedIOException iiox) {
            if (iiox.bytesTransferred <= 0) {
              continue;
            }
          }
          if (logger.isDebugEnabled()) {
            logger.debug("Received message from {}/{} with length {}: {}", packet.getAddress(), packet.getPort(), packet.getLength(), new OctetString(packet.getData(), 0,
                packet.getLength()).toHexString());
          }
          ByteBuffer bis;
          // If messages are processed asynchronously (i.e. multi-threaded)
          // then we have to copy the buffer's content here!
          if (isAsyncMsgProcessingSupported()) {
            byte[] bytes = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, bytes, 0, bytes.length);
            bis = ByteBuffer.wrap(bytes);
          }
          else {
            bis = ByteBuffer.wrap(packet.getData());
          }
          TransportStateReference stateReference =
            new TransportStateReference(DefaultUdpTransportMapping.this, udpAddress, null,
                                        SecurityLevel.undefined, SecurityLevel.undefined,
                                        false, socketCopy);
          fireProcessMessage(new UdpAddress(packet.getAddress(),
                                            packet.getPort()), bis, stateReference);
        }
        catch (SocketTimeoutException stex) {
          // ignore
        }
        catch (PortUnreachableException purex) {
          synchronized (DefaultUdpTransportMapping.this) {
            listenerThread = null;
          }
          logger.error(purex.getMessage(), purex);
          throw new RuntimeException(purex);
        }
        catch (SocketException soex) {
          if (!shouldStop()) {
            logger.warn("Socket for transport mapping {} error: {}", this, soex.getMessage());
          }

          askToStop();
          throw new RuntimeException(soex);
        }
        catch (IOException iox) {
          logger.warn(iox.getMessage(), iox);
          throw new RuntimeException(iox);
        }
      }
      synchronized (DefaultUdpTransportMapping.this) {
        listenerThread = null;
        askToStop();
        DatagramSocket closingSocket = socket;
        if ((closingSocket != null) && (!closingSocket.isClosed())) {
          closingSocket.close();
        }
        socket = null;
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Worker task stopped:{}", getClass().getName());
      }
    }

    @Override
    public String getName() {
      return DefaultUdpTransportMapping.class.getSimpleName() + "Listener_" + getListenAddress();
    }
  }
}
