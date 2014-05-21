package org.snmp4j.concurrent;

public abstract class ControlableRunnable implements Runnable {
  private volatile boolean shouldStop = false;

  /**
   * Used by the runnable to check if it should stop or not.
   */
  protected boolean shouldStop() {
    return shouldStop;
  }

  /**
   * Ask this Runnable to stop what it is doing. It may not obey.
   */
  public void askToStop() {
    shouldStop = true;
  }

  /**
   * Should return a name that describes this runnable. Used by the {@link org.snmp4j.util.ThreadFactory}
   * for example.
   * @see org.snmp4j.util.ThreadFactory
   */
  public abstract String getName();
}
