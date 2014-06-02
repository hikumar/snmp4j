/*_############################################################################
  _## 
  _##  SNMP4J 2 - DefaultTcpTransportMapping.java  
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
import org.snmp4j.asn1.BER;
import org.snmp4j.asn1.BER.MutableByte;
import org.snmp4j.asn1.BERInputStream;
import org.snmp4j.concurrent.ControlableRunnable;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.TcpAddress;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

/**
 * The <code>DefaultTcpTransportMapping</code> implements a TCP transport
 * mapping with the Java 1.4 new IO API.
 * <p>
 * It uses a single thread for processing incoming and outgoing messages.
 * The thread is started when the <code>listen</code> method is called, or
 * when an outgoing request is sent using the <code>sendMessage</code> method.
 *
 * @author Frank Fock
 * @version 1.11
 */
public class DefaultTcpTransportMapping extends TcpTransportMapping {
  private static final Logger logger =
      LoggerFactory.getLogger(DefaultTcpTransportMapping.class);

  private static final int MIN_SNMP_HEADER_LENGTH = 6;
  private static final long DEFAULT_CONNECTION_MS_TIMEOUT = 60000;

  private Thread listenerThread;
  private SocketListener socketListener;
  private final Selector selector;

  private final Map<SocketAddress, SelectionKey> keys;

  // 1 minute default timeout
  private long connectionTimeout = DEFAULT_CONNECTION_MS_TIMEOUT;
  private final boolean serverEnabled;

  private MessageLengthDecoder messageLengthDecoder =
      new SnmpMesssageLengthDecoder();

  /**
   * Creates a default TCP transport mapping with the server for incoming
   * messages disabled.
   * @throws IOException
   *    on failure of binding a local port.
   */
  public DefaultTcpTransportMapping() throws IOException {
    this(new TcpAddress(InetAddress.getLocalHost(), 0), false);
  }

  /**
   * Creates a default TCP transport mapping that binds to the given address
   * (interface) on the local host.
   *
   * @param serverAddress
   *    the TcpAddress instance that describes the server address to listen
   *    on incoming connection requests.
   * @throws IOException
   *    if the given address cannot be bound.
   */
  public DefaultTcpTransportMapping(TcpAddress serverAddress, boolean enableServer) throws IOException {
    super(serverAddress);

    serverEnabled = enableServer;
    keys = new Hashtable<>();

    // Selector for incoming requests
    selector = Selector.open();
  }

  /**
   * Listen for incoming and outgoing requests. If the <code>serverEnabled</code>
   * member is <code>false</code> the server for incoming requests is not
   * started. This starts the internal server thread that processes messages.
   * @throws SocketException
   *    when the transport is already listening for incoming/outgoing messages.
   * @throws IOException
   */
  @Override
  public synchronized void listen() throws IOException {
    if (listenerThread == null) {
      socketListener = new SocketListener(getListenAddress().toSocketAddress());
      listenerThread = SNMP4JSettings.getThreadFactory().newThread(socketListener);
      listenerThread.start();
    }

    logger.info("Now listening with {}", socketListener);
  }

  /**
   * Closes all open sockets and stops the internal server thread that
   * processes messages.
   */
  @Override
  public void close() {
  }

  /**
   * Closes a connection to the supplied remote address, if it is open. This
   * method is particularly useful when not using a timeout for remote
   * connections.
   *
   * @param remoteAddress
   *    the address of the peer socket.
   * @return
   *    <code>true</code> if the connection has been closed and
   *    <code>false</code> if there was nothing to close.
   * @throws IOException
   *    if the remote address cannot be closed due to an IO exception.
   * @since 1.7.1
   */
  @Override
  public synchronized boolean close(TcpAddress remoteAddress) {
    close(remoteAddress.toSocketAddress());
    return true;
  }

  private void close(InetSocketAddress socketAddress) {
    SelectionKey key = keys.get(socketAddress);
    close(key);
  }

  private void close(SelectionKey key) {
    SocketAddress remoteAddress = getEntry(key).remoteAddress;
    logger.debug("Closing socket to {}", remoteAddress);

    try {
      keys.remove(remoteAddress);

      key.cancel();
      key.channel().close();
    } catch (IOException e) {
      logger.error("Encountered an I/O error when attempting to close socket to {}", remoteAddress, e);
    }
  }

  private void close(SelectionKey key, IOException cause) {
    logger.error("Closing {} due to a {} being thrown during its operation", key, cause, cause);

    close(key);

    TransportStateEvent e =
        new TransportStateEvent(DefaultTcpTransportMapping.this,
            new TcpAddress(getEntry(key).remoteAddress),
            TransportStateEvent.TransportStates.DISCONNECTED_REMOTELY,
            cause);

    fireConnectionStateChanged(e);
  }

  /**
   * Sends a SNMP message to the supplied address.
   * @param address
   *    an <code>TcpAddress</code>. A <code>ClassCastException</code> is thrown
   *    if <code>address</code> is not a <code>TcpAddress</code> instance.
   * @param message byte[]
   *    the message to sent.
   * @param tmStateReference
   *    the (optional) transport model state reference as defined by
   *    RFC 5590 section 6.1.
   * @throws IOException
   */
  @Override
  public void sendMessage(TcpAddress address, byte[] message,
                          TransportStateReference tmStateReference)
      throws IOException
  {
    InetSocketAddress socketAddress = new InetSocketAddress(address.getInetAddress(),
                                                            address.getPort());

    ByteBuffer bufferMessage = ByteBuffer.wrap(message);

    sendMessage(socketAddress, bufferMessage);
  }

  public void sendMessage(InetSocketAddress remoteAddress, ByteBuffer message) throws IOException {
    logger.debug("Adding message of length {} to send queue for {}", message.remaining(), remoteAddress);
    getEntry(remoteAddress).addMessage(message);
    selector.wakeup();
  }

  /**
   * Gets the connection timeout. This timeout specifies the time a connection
   * may be idle before it is closed.
   * @return long
   *    the idle timeout in milliseconds.
   */
  public long getConnectionTimeout() {
    return connectionTimeout;
  }

  /**
   * Sets the connection timeout. This timeout specifies the time a connection
   * may be idle before it is closed.
   * @param connectionTimeout
   *    the idle timeout in milliseconds. A zero or negative value will disable
   *    any timeout and connections opened by this transport mapping will stay
   *    opened until they are explicitly closed.
   */
  @Override
  public void setConnectionTimeout(long connectionTimeout) {
    this.connectionTimeout = connectionTimeout;
  }

  /**
   * Checks whether a server for incoming requests is enabled.
   * @return boolean
   */
  public boolean isServerEnabled() {
    return serverEnabled;
  }

  @Override
  public MessageLengthDecoder getMessageLengthDecoder() {
    return messageLengthDecoder;
  }

  /**
   * Sets the message length decoder. Default message length decoder is the
   * {@link SnmpMesssageLengthDecoder}. The message length decoder must be
   * able to decode the total length of a message for this transport mapping
   * protocol(s).
   * @param messageLengthDecoder
   *    a <code>MessageLengthDecoder</code> instance.
   */
  @Override
  public void setMessageLengthDecoder(MessageLengthDecoder messageLengthDecoder) {
    this.messageLengthDecoder = Objects.requireNonNull(messageLengthDecoder);
  }

  /**
   * Sets the maximum buffer size for incoming requests. When SNMP packets are
   * received that are longer than this maximum size, the messages will be
   * silently dropped and the connection will be closed.
   * @param maxInboundMessageSize
   *    the length of the inbound buffer in bytes.
   */
  public void setMaxInboundMessageSize(int maxInboundMessageSize) {
    this.maxInboundMessageSize = maxInboundMessageSize;
  }

  @Override
  public boolean isListening() {
    return (listenerThread != null);
  }

  public static class SnmpMesssageLengthDecoder implements MessageLengthDecoder {
    @Override
    public int getMinHeaderLength() {
      return MIN_SNMP_HEADER_LENGTH;
    }
    @Override
    public MessageLength getMessageLength(ByteBuffer buf) throws IOException {
      MutableByte type = new MutableByte();
      BERInputStream is = new BERInputStream(buf);
      int ml = BER.decodeHeader(is, type);
      int hl = (int)is.getPosition();
      return new MessageLength(hl, ml);
    }
  }

  private void readMessage(SocketEntry entry, SelectionKey key, SocketChannel readChannel) throws IOException {
    ByteBuffer inputBuffer = entry.inputBuffer;
    long iterationBytesRead;

    SocketAddress remoteAddress = entry.remoteAddress;

    // Continue reading from the channel until there is nothing more to read.
    // It is a non blocking operation so it will just return with a value
    // equal to or bellow 0 if there is nothing to read
    while ((iterationBytesRead = readChannel.read(inputBuffer)) > 0) {
      logger.debug("Read {} bytes from {}", iterationBytesRead, remoteAddress);

      if (inputBuffer.position() == messageLengthDecoder.getMinHeaderLength()) {
        // If we have read a complete header decode the message length and
        // do stuff with it
        MessageLength messageLength = messageLengthDecoder.getMessageLength(inputBuffer);
        logger.debug("Message from {} will be {} bytes", remoteAddress, messageLength);

        if ((messageLength.getMessageLength() > getMaxInboundMessageSize()) ||
            (messageLength.getMessageLength() <= 0)) {
          throw new MalformedMessageException("Message from {} has invalid length (0 <= " + messageLength.getMessageLength() + " < " + getMaxInboundMessageSize() + ")");
        }

        // Update the buffers limit so we don't read past the message
        inputBuffer.limit(messageLength.getMessageLength());
      } else if (!inputBuffer.hasRemaining()) {
        // If we have read to the limit of the buffer we have read a full
        // message so now we need to dispatch the mssage and reset this
        // connections input buffer so we can read the next message.
        logger.debug("Received a full message of length {} from {}", inputBuffer.position(), remoteAddress);

        ByteBuffer messageBuffer = entry.copyMessage();
        entry.resetInputBuffer();

        dispatchMessage(entry, messageBuffer);
      }
    }

    if (iterationBytesRead < 0) {
      logger.debug("Reached end-of-stream with {}", remoteAddress);
      close(key);

      TransportStateEvent e =
          new TransportStateEvent(DefaultTcpTransportMapping.this,
              new TcpAddress(entry.remoteAddress),
              TransportStateEvent.TransportStates.DISCONNECTED_REMOTELY,
              null);

      fireConnectionStateChanged(e);
    }
  }

  public void writeMessages(final ArrayDeque<ByteBuffer> messages, SocketChannel channel) throws IOException {
    ByteBuffer nextBuffer = messages.peek();
    int writtenBytes;

    while ((writtenBytes = channel.write(nextBuffer)) > 0) {
      if (!nextBuffer.hasRemaining()) {
        messages.pop();
        nextBuffer = messages.peek();
      }

      logger.debug("Wrote {} bytes to {}", writtenBytes, channel);
    }
  }

  private SelectionKey getSelectionKey(InetSocketAddress remoteAddress) throws IOException {
    SelectionKey key = keys.get(remoteAddress);

    if (key == null) {
      // Open the channel, set it to non-blocking, initiate connect
      SocketChannel sc = SocketChannel.open();
      key = configureChannel(sc);
      sc.connect(remoteAddress);
    }

    return key;
  }

  private SocketEntry getEntry(SelectionKey key) {
    return (SocketEntry) key.attachment();
  }

  private SocketEntry getEntry(InetSocketAddress remoteAddress) throws IOException {
    return getEntry(getSelectionKey(remoteAddress));
  }

  private SelectionKey configureChannel(SocketChannel s) throws IOException {
    s.configureBlocking(false);
    SelectionKey key = s.register(selector,
        SelectionKey.OP_CONNECT |
            SelectionKey.OP_READ |
            SelectionKey.OP_WRITE);

    SocketEntry entry = new SocketEntry((InetSocketAddress) s.getRemoteAddress());
    key.attach(entry);

    return key;
  }

  private void dispatchMessage(SocketEntry entry,
                               ByteBuffer messageBuffer) {
    TcpAddress remoteAddress = new TcpAddress(entry.remoteAddress);

    TransportStateReference stateReference =
        new TransportStateReference(
            DefaultTcpTransportMapping.this,
            remoteAddress,
            null,
            SecurityLevel.undefined, SecurityLevel.undefined,
            false, entry);

    fireProcessMessage(remoteAddress, messageBuffer, stateReference);
  }

  class SocketListener extends ControlableRunnable {
    private final ServerSocketChannel ssc;

    public SocketListener(InetSocketAddress bindAddress) throws IOException {
      if (serverEnabled) {
        // Create a new server socket, set to non blocking mode and bind to the
        // supplied address
        ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.bind(bindAddress);

        // Register accepts on the server socket with the selector. This
        // step tells the selector that the socket wants to be put on the
        // ready list when accept operations occur, so allowing multiplexed
        // non-blocking I/O to take place.
        ssc.register(selector, SelectionKey.OP_ACCEPT);
      } else {
        // We're not supposed to listen for incoming requests so don't create
        // the ServerSocketChannel
        ssc = null;
      }
    }

    @Override
    public void run() {
      try {
        while (!shouldStop()) {
          if (selector.select() > 0) {
            // Someone is ready for I/O, get the ready keys
            Set<SelectionKey> readyKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = readyKeys.iterator();
            SelectionKey sk = null;

            // Walk through the ready keys collection and process date requests.
            while (it.hasNext()) {
              try {
                sk = it.next();
                it.remove();

                if (sk.isAcceptable()) {
                  handleAcceptable(sk);
                } else if (sk.isConnectable()) {
                  handleConnectable(sk);
                } else if (sk.isReadable()) {
                  handleReadable(sk);
                } else if (sk.isWritable()) {
                  handleWritable(sk);
                }
              } catch (ClosedByInterruptException e) {
                // If the thread gets interrupted we're about to shut down so
                // we should propagate this exception to a higher level.
                // Propagate the exception to a higher level
                throw e;
              } catch (ClosedChannelException e) {
                logger.error("Attempted an I/O operation on a closed channel in {}", this, e);
              } catch (CancelledKeyException ckex) {
                logger.debug("Selection key cancelled, skipping it");
              } catch (IOException e) {
                close(sk, e);
                throw e;
              }
            }
          }
        }
      } catch (ClosedByInterruptException e) {
        logger.info("{} was interrupted during I/O on a channel", this);
      } catch (IOException iox) {
        logger.error("Encountered an I/O problem in {}", this, iox);
      } finally {
        close();
      }

      logger.debug("Exiting listener thread {}", this);
    }

    private void handleAcceptable(SelectionKey sk) throws IOException {
      // Accept the incoming connection
      SocketChannel newChannel = ((ServerSocketChannel) sk.channel()).accept();

      SocketAddress remoteAddress = newChannel.getRemoteAddress();
      logger.debug("Accepting incoming connection from {}", remoteAddress);

      configureChannel(newChannel);

      SocketEntry socketEntry = getEntry(sk);

      TransportStateEvent e =
          new TransportStateEvent(DefaultTcpTransportMapping.this,
              new TcpAddress(socketEntry.remoteAddress),
              TransportStateEvent.TransportStates.CONNECTED,
              null);

      fireConnectionStateChanged(e);

      if (e.isCancelled()) {
        logger.warn("Incoming accepted connection to {} cancelled", remoteAddress);
        close(sk);
      }
    }

    private void handleConnectable(SelectionKey sk) {
      SocketEntry entry = getEntry(sk);
      logger.debug("Connection to {} established", entry.remoteAddress);

      TransportStateEvent e =
          new TransportStateEvent(DefaultTcpTransportMapping.this,
              new TcpAddress(entry.remoteAddress),
              TransportStateEvent.TransportStates.CONNECTED,
              null);

      fireConnectionStateChanged(e);

      if (e.isCancelled()) {
        logger.warn("Outgoing connection to {} cancelled", entry.remoteAddress);
        close(sk);
      }
    }

    private void handleReadable(SelectionKey sk) throws IOException {
      SocketChannel readChannel = (SocketChannel) sk.channel();
      SocketEntry socketEntry = getEntry(sk);
      readMessage(socketEntry, sk, readChannel);
    }

    private void handleWritable(SelectionKey sk) throws IOException {
      SocketChannel sc = (SocketChannel) sk.channel();
      SocketEntry socketEntry = getEntry(sk);
      writeMessages(socketEntry.messages, sc);
    }

    @Override
    public String getName() {
      return DefaultTcpTransportMapping.class.getSimpleName() + "Listener_" + getListenAddress();
    }
  }

  private class SocketEntry {
    public final ByteBuffer inputBuffer;
    public final InetSocketAddress remoteAddress;

    private final ArrayDeque<ByteBuffer> messages;

    SocketEntry(InetSocketAddress theRemoteAddress) {
      remoteAddress = Objects.requireNonNull(theRemoteAddress);
      inputBuffer = ByteBuffer.allocate(getMaxInboundMessageSize());
      messages = new ArrayDeque<>();
    }

    public void resetInputBuffer() {
      inputBuffer.clear();
      inputBuffer.limit(messageLengthDecoder.getMinHeaderLength());
    }

    public ByteBuffer copyMessage() {
      ByteBuffer messageBuffer = ByteBuffer.allocate(inputBuffer.position());
      inputBuffer.flip();
      messageBuffer.put(inputBuffer);
      return messageBuffer;
    }

    public void addMessage(ByteBuffer message) {
      messages.addLast(message);
    }
  }
}
