package org.bgi.flexlab.gaea.tools.jointcalling.util;

import java.text.NumberFormat;
import java.util.concurrent.TimeUnit;

public class SimpleTimer {
    protected static final double NANO_TO_SECOND_DOUBLE = 1.0 / TimeUnit.SECONDS.toNanos(1);
    private static final long MILLI_TO_NANO= TimeUnit.MILLISECONDS.toNanos(1);
    /*private static final ThreadLocal<NumberFormat> NUMBER_FORMAT = new ThreadLocal<NumberFormat>() {
        @Override
        protected NumberFormat initialValue() {
            return NumberFormat.getIntegerInstance();
        }
    };*/

    /**
     * Allowable clock drift in nanoseconds.
     */
    private static final long CLOCK_DRIFT = TimeUnit.SECONDS.toNanos(5);
    private final String name;

    /**
     * The difference between system time and JVM time at last sync.
     * This is used to detect JVM checkpoint/restart events, and should be 
     * reset when a JVM checkpoint/restart is detected.
     */
    private long nanoTimeOffset;

    /**
     * The elapsedTimeNano time in nanoSeconds of this timer.  The elapsedTimeNano time is the
     * sum of times between starts/restrats and stops.
     */
    private long elapsedTimeNano = 0l;

    /**
     * The start time of the last start/restart in nanoSeconds
     */
    private long startTimeNano = 0l;

    /**
     * Is this timer currently running (i.e., the last call was start/restart)
     */
    private boolean running = false;

    /**
     * Creates an anonymous simple timer
     */
    public SimpleTimer() {
        this("Anonymous");
    }

    /**
     * Creates a simple timer named name
     * @param name of the timer, must not be null
     */
    public SimpleTimer(final String name) {
        if ( name == null ) throw new IllegalArgumentException("SimpleTimer name cannot be null");
        this.name = name;

        this.nanoTimeOffset = getNanoOffset();
    }

    /**
     * @return the name associated with this timer
     */
    public synchronized String getName() {
        return name;
    }

    /**
     * Starts the timer running, and sets the elapsedTimeNano time to 0.  This is equivalent to
     * resetting the time to have no history at all.
     *
     * @return this object, for programming convenience
     */
    public synchronized SimpleTimer start() {
        elapsedTimeNano = 0l;
        return restart();
    }

    /**
     * Starts the timer running, without resetting the elapsedTimeNano time.  This function may be
     * called without first calling start().  The only difference between start and restart
     * is that start resets the elapsedTimeNano time, while restart does not.
     *
     * @return this object, for programming convenience
     */
    public synchronized SimpleTimer restart() {
        running = true;
        startTimeNano = currentTimeNano();
        nanoTimeOffset = getNanoOffset();
        return this;
    }

    /**
     * @return is this timer running?
     */
    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * @return A convenience function to obtain the current time in milliseconds from this timer
     */
    public long currentTime() {
        return System.currentTimeMillis();
    }

    /**
     * @return A convenience function to obtain the current time in nanoSeconds from this timer
     */
    public long currentTimeNano() {
        return System.nanoTime();
    }

    /**
     * Stops the timer.  Increases the elapsedTimeNano time by difference between start and now.
     * This method calls `ensureClockSync` to make sure that the JVM and system clocks
     * are roughly in sync since the start of the timer. If they are not, then the time
     * elapsed since the previous 'stop' will not be added to the timer.
     *
     * It's ok to call stop on a timer that's not running.  It has no effect on the timer.
     *
     * @return this object, for programming convenience
     */
    public synchronized SimpleTimer stop() {
        if ( running ) {
            running = false;
            if (ensureClockSync()) {
                elapsedTimeNano += currentTimeNano() - startTimeNano;
            }
        }
        return this;
    }

    /**
     * Returns the total elapsedTimeNano time of all start/stops of this timer.  If the timer is currently
     * running, includes the difference from currentTime() and the start as well
     *
     * @return this time, in seconds
     */
    public synchronized double getElapsedTime() {
        return nanoToSecondsAsDouble(getElapsedTimeNano());
    }

    protected static double nanoToSecondsAsDouble(final long nano) {
        return nano * NANO_TO_SECOND_DOUBLE;
    }

    /**
     * @see #getElapsedTime() but returns the result in nanoseconds
     *
     * @return the elapsed time in nanoseconds
     */
    public synchronized long getElapsedTimeNano() {
        if (running && ensureClockSync()) {
            return currentTimeNano() - startTimeNano + elapsedTimeNano;
        } else {
            return elapsedTimeNano;
        }
    }

    /**
     * Add the elapsed time from toAdd to this elapsed time
     *
     * @param toAdd the timer whose elapsed time we want to add to this timer
     */
    public synchronized void addElapsed(final SimpleTimer toAdd) {
        elapsedTimeNano += toAdd.getElapsedTimeNano();
    }

    /**
     * Get the current offset of nano time from system time.
     */
    private static long getNanoOffset() {
        return System.nanoTime() - (System.currentTimeMillis() * MILLI_TO_NANO);
    }

    /**
     * Ensure that the JVM time has remained in sync with system time.
     * This will also reset the clocks to avoid gradual drift.
     *
     * @return true if the clocks are in sync, false otherwise
     */
    private boolean ensureClockSync() {
        final long currentOffset = getNanoOffset();
        final long diff = Math.abs(currentOffset - nanoTimeOffset);
        final boolean ret = (diff <= CLOCK_DRIFT);
        if (!ret) {
        }
        // Reset the drift meter to stay in sync.
        this.nanoTimeOffset = currentOffset;
        return ret;
    }
}
