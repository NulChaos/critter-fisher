package com.nul.critterfisher;

/** One automation mode (fishing, pinball, ...). Each runs on its own thread. */
public interface Mode extends Runnable {
    String name();
    String counterLabel();
    boolean isRunning();
    void setRunning(boolean r);
    void kill();
    Bot.Status status();
}
