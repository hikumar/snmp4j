/*_############################################################################
  _## 
  _##  SNMP4J 2 - AbstractVariable.java  
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.SNMP4JSettings;
import org.snmp4j.asn1.BER;
import org.snmp4j.asn1.BERInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;

// For JavaDoc:

/**
 * The <code>Variable</code> abstract class is the base class for all SNMP
 * variables.
 * <p>
 * All derived classes need to be registered with their SMI BER type in the
 * <code>smisyntaxes.properties</code>so that the
 * {@link #createFromBER(BERInputStream inputStream)} method
 * is able to decode a variable from a BER encoded stream.
 * <p>
 * To register additional syntaxes, set the system property
 * {@link #SMISYNTAXES_PROPERTIES} before decoding a Variable for the first
 * time. The path of the property file must be accessible from the classpath
 * and it has to be specified relative to the <code>Variable</code> class.
 *
 * @author Jochen Katz & Frank Fock
 * @version 1.8
 * @since 1.8
 */
public abstract class AbstractVariable implements Variable, Serializable, Cloneable {

  private static final long serialVersionUID = 1395840752909725320L;

  public static final String SMISYNTAXES_PROPERTIES =
      "org.snmp4j.smisyntaxes";
  private static final String SMISYNTAXES_PROPERTIES_DEFAULT =
      "smisyntaxes.properties";

  private static final Object[][] SYNTAX_NAME_MAPPING = {
      { "Integer32", (int) BER.INTEGER32},
      { "BIT STRING", (int) BER.BITSTRING},
      { "OCTET STRING", (int) BER.OCTETSTRING},
      { "OBJECT IDENTIFIER", (int) BER.OID},
      { "TimeTicks", (int) BER.TIMETICKS},
      { "Counter", (int) BER.COUNTER},
      { "Counter64", (int) BER.COUNTER64},
      { "EndOfMibView", BER.ENDOFMIBVIEW},
      { "Gauge", (int) BER.GAUGE32},
      { "Unsigned32", (int) BER.GAUGE32},
      { "IpAddress", (int) BER.IPADDRESS},
      { "NoSuchInstance", BER.NOSUCHINSTANCE},
      { "NoSuchObject", BER.NOSUCHOBJECT},
      { "Null", (int) BER.NULL},
      { "Opaque", (int) BER.OPAQUE}
  };

  private static Hashtable<Integer, Class<? extends Variable>> registeredSyntaxes = null;

  private static final Logger logger =
      LoggerFactory.getLogger(AbstractVariable.class);

  /**
   * The abstract <code>Variable</code> class serves as the base class for all
   * specific SNMP syntax types.
   */
  public AbstractVariable() {
  }

  public int getBERPayloadLength() {
    return getBERLength();
  }

  /**
   * Creates a <code>Variable</code> from a BER encoded <code>InputStream</code>.
   * Subclasses of <code>Variable</code> are registered using the properties file
   * <code>smisyntaxes.properties</code> in this package. The properties are
   * read when this method is called first.
   *
   * @param inputStream
   *    an <code>BERInputStream</code> containing a BER encoded byte stream.
   * @return
   *    an instance of a subclass of <code>Variable</code>.
   * @throws IOException
   *    if the <code>inputStream</code> is not properly BER encoded.
   */
  public static Variable createFromBER(BERInputStream inputStream) throws
      IOException {
    if (!inputStream.markSupported()) {
      throw new IOException(
          "InputStream for decoding a Variable must support marks");
    }
    if (SNMP4JSettings.isExtensibilityEnabled() &&
        (registeredSyntaxes == null)) {
      registerSyntaxes();
    }
    inputStream.mark(2);
    int type = inputStream.read();
    Variable variable;
    if (SNMP4JSettings.isExtensibilityEnabled()) {
      Class<? extends Variable> c = registeredSyntaxes.get(new Integer(type));
      if (c == null) {
        throw new IOException("Encountered unsupported variable syntax: " +
                              type);
      }
      try {
        variable = c.newInstance();
      }
      catch (IllegalAccessException aex) {
        throw new IOException("Could not access variable syntax class for: " +
                              c.getName());
      }
      catch (InstantiationException iex) {
        throw new IOException(
            "Could not instantiate variable syntax class for: " +
            c.getName());
      }
    }
    else {
      variable = createVariable(type);
    }
    inputStream.reset();
    variable.decodeBER(inputStream);
    return variable;
  }

  private static Variable createVariable(int smiSyntax) {
    switch (smiSyntax) {
      case SMIConstants.SYNTAX_OBJECT_IDENTIFIER: {
        return new OID();
      }
      case SMIConstants.SYNTAX_INTEGER: {
        return new Integer32();
      }
      case SMIConstants.SYNTAX_OCTET_STRING: {
        return new OctetString();
      }
      case SMIConstants.SYNTAX_GAUGE32: {
        return new Gauge32();
      }
      case SMIConstants.SYNTAX_COUNTER32: {
        return new Counter32();
      }
      case SMIConstants.SYNTAX_COUNTER64: {
        return new Counter64();
      }
      case SMIConstants.SYNTAX_NULL: {
        return new Null();
      }
      case SMIConstants.SYNTAX_TIMETICKS: {
        return new TimeTicks();
      }
      case SMIConstants.EXCEPTION_END_OF_MIB_VIEW: {
        return new Null(SMIConstants.EXCEPTION_END_OF_MIB_VIEW);
      }
      case SMIConstants.EXCEPTION_NO_SUCH_INSTANCE: {
        return new Null(SMIConstants.EXCEPTION_NO_SUCH_INSTANCE);
      }
      case SMIConstants.EXCEPTION_NO_SUCH_OBJECT: {
        return new Null(SMIConstants.EXCEPTION_NO_SUCH_OBJECT);
      }
      case SMIConstants.SYNTAX_OPAQUE: {
        return new Opaque();
      }
      case SMIConstants.SYNTAX_IPADDRESS: {
        return new IpAddress();
      }
      default: {
        throw new IllegalArgumentException("Unsupported variable syntax: " +
                                           smiSyntax);
      }
    }
  }

  /**
   * Creates a <code>Variable</code> from the supplied SMI syntax identifier.
   * Subclasses of <code>Variable</code> are registered using the properties
   * file <code>smisyntaxes.properties</code> in this package. The properties
   * are read when this method is called for the first time.
   *
   * @param smiSyntax
   *    an SMI syntax identifier of the registered types, which is typically
   *    defined by {@link SMIConstants}.
   * @return
   *    a <code>Variable</code> variable instance of the supplied SMI syntax.
   */
  public static Variable createFromSyntax(int smiSyntax) {
    if (!SNMP4JSettings.isExtensibilityEnabled()) {
      return createVariable(smiSyntax);
    }
    if (registeredSyntaxes == null) {
      registerSyntaxes();
    }
    Class<? extends Variable> c = registeredSyntaxes.get(new Integer(smiSyntax));
    if (c == null) {
      throw new IllegalArgumentException("Unsupported variable syntax: " +
                                         smiSyntax);
    }
    try {
      return c.newInstance();
    }
    catch (IllegalAccessException aex) {
      throw new RuntimeException("Could not access variable syntax class for: " +
                                 c.getName());
    }
    catch (InstantiationException iex) {
      throw new RuntimeException(
          "Could not instantiate variable syntax class for: " +
          c.getName());
    }
  }

  /**
   * Clones this variable. Cloning can be used by the SNMP4J API to better
   * support concurrency by creating a immutable clone for internal processing.
   *
   * @return
   *    a new instance of this <code>Variable</code> with the same value.
   */
  public abstract Object clone();

  /**
   * Register SNMP syntax classes from a properties file. The registered
   * syntaxes are used by the {@link #createFromBER} method to type-safe
   * instantiate sub-classes from <code>Variable</code> from an BER encoded
   * <code>InputStream</code>.
   */
  @SuppressWarnings("unchecked")
  private synchronized static void registerSyntaxes() {
    String syntaxes = System.getProperty(SMISYNTAXES_PROPERTIES,
                                         SMISYNTAXES_PROPERTIES_DEFAULT);
    InputStream is = Variable.class.getResourceAsStream(syntaxes);
    if (is == null) {
      throw new InternalError("Could not read '" + syntaxes +
                              "' from classpath!");
    }
    Properties props = new Properties();
    try {
      props.load(is);
      Hashtable<Integer, Class<? extends Variable>> regSyntaxes = new Hashtable<>(props.size());
      for (Enumeration en = props.propertyNames(); en.hasMoreElements(); ) {
        String id = en.nextElement().toString();
        String className = props.getProperty(id);
        try {
          Class<? extends Variable> c = (Class<? extends Variable>) Class.forName(className);
          regSyntaxes.put(new Integer(id), c);
        }
        catch (ClassNotFoundException cnfe) {
          logger.error("Unable to find class with name {}", className, cnfe);
        }
        catch (ClassCastException ccex) {
          logger.error("Could not cast {} to Class<? extends Variable>", className, ccex);
        }
      }
      // atomic syntax registration
      registeredSyntaxes = regSyntaxes;
    }
    catch (IOException iox) {
      String txt = "Could not read '" + syntaxes + "': " +
          iox.getMessage();
      logger.error(txt);
      throw new InternalError(txt);
    }
    finally {
      try {
        is.close();
      }
      catch (IOException ex) {
        logger.warn(ex.getMessage(), ex);
      }
    }
  }

  /**
   * Checks whether this variable represents an exception like
   * noSuchObject, noSuchInstance, and endOfMibView.
   * @return
   *    <code>true</code> if the syntax of this variable is an instance of
   *    <code>Null</code> and its syntax equals one of the following:
   *    <UL>
   *    <LI>{@link SMIConstants#EXCEPTION_NO_SUCH_OBJECT}</LI>
   *    <LI>{@link SMIConstants#EXCEPTION_NO_SUCH_INSTANCE}</LI>
   *    <LI>{@link SMIConstants#EXCEPTION_END_OF_MIB_VIEW}</LI>
   *    </UL>
   */
  public boolean isException() {
    return Null.isExceptionSyntax(getSyntax());
  }

  /**
   * Gets a textual description of the supplied syntax type.
   * @param syntax
   *    the BER code of the syntax.
   * @return
   *    a textual description like 'Integer32' for <code>syntax</code>
   *    as used in the Structure of Management Information (SMI) modules.
   *    '?' is returned if the supplied syntax is unknown.
   */
  public static String getSyntaxString(int syntax) {
    switch (syntax) {
      case BER.INTEGER:
        return "Integer32";
      case BER.BITSTRING:
        return "BIT STRING";
      case BER.OCTETSTRING:
        return "OCTET STRING";
      case BER.OID:
        return "OBJECT IDENTIFIER";
      case BER.TIMETICKS:
        return "TimeTicks";
      case BER.COUNTER:
        return "Counter";
      case BER.COUNTER64:
        return "Counter64";
      case BER.ENDOFMIBVIEW:
        return "EndOfMibView";
      case BER.GAUGE32:
        return "Gauge";
      case BER.IPADDRESS:
        return "IpAddress";
      case BER.NOSUCHINSTANCE:
        return "NoSuchInstance";
      case BER.NOSUCHOBJECT:
        return "NoSuchObject";
      case BER.NULL:
        return "Null";
      case BER.OPAQUE:
        return "Opaque";
    }
    return "?";
  }

  /**
   * Gets a textual description of this Variable.
   * @return
   *    a textual description like 'Integer32'
   *    as used in the Structure of Management Information (SMI) modules.
   *    '?' is returned if the syntax is unknown.
   * @since 1.7
   */
  public final String getSyntaxString() {
    return getSyntaxString(getSyntax());
  }

  /**
   * Returns the BER syntax ID for the supplied syntax string (as returned
   * by {@link #getSyntaxString(int)}).
   * @param syntaxString
   *    the textual representation of the syntax.
   * @return
   *    the corresponding BER ID.
   * @since 1.6
   */
  public static int getSyntaxFromString(String syntaxString) {
    for (Object[] aSYNTAX_NAME_MAPPING : SYNTAX_NAME_MAPPING) {
      if (aSYNTAX_NAME_MAPPING[0].equals(syntaxString)) {
        return (Integer) aSYNTAX_NAME_MAPPING[1];
      }
    }
    return BER.NULL;
  }

  /**
   * Indicates whether this variable is dynamic, which means that it might
   * change its value while it is being (BER) serialized. If a variable is
   * dynamic, it will be cloned on-the-fly when it is added to a {@link PDU}
   * with {@link PDU#add(VariableBinding)}. By cloning the value, it is
   * ensured that there are no inconsistent changes between determining the
   * length with {@link #getBERLength()} for encoding enclosing SEQUENCES and
   * the actual encoding of the Variable itself with {@link #encodeBER}.
   *
   * @return
   *    <code>false</code> by default. Derived classes may override this
   *    if implementing dynamic {@link Variable} instances.
   * @since 1.8
   */
  public boolean isDynamic() {
    return false;
  }

  /**
   * Tests if two variables have the same value.
   * @param a
   *   a variable.
   * @param b
   *   another variable.
   * @return
   *   <code>true</code> if
   *   <code>a == null) ?  (b == null) : a.equals(b)</code>.
   *   @since 2.0
   */
  public static boolean equal(AbstractVariable a, AbstractVariable b) {
    return (a == null) ?  (b == null) : a.equals(b);
  }

}
