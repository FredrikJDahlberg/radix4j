package org.limitless.radix4j;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class TimerWheel {

    private int[] timersByBucket;

    private int[][] blocks;

    private int count;

    private final long resolution;

    private TimeUnit timeUnit;

    private int timestamp;
    private int bucket;

    /**
     * Create a timing wheel with the given properties
     * @param limit positive number maximum timeout
     * @param resolution the minimum number of units per timer tick
     */
    public TimerWheel(TimeUnit timeUnit, long startTime, int limit, int resolution) {

        count = limit / resolution;
        if (limit <= 0 || resolution <= 0 || count <= 0) {
            throw new IllegalArgumentException("negative bucket count");
        }
        this.timeUnit = timeUnit;

        //buckets = new int[count];
        // counts = new int[count];
        this.resolution = resolution;
    }

    public boolean add(int timerId, int duration) {
        if (timerId <= 0) {
            throw new IllegalArgumentException("timerId must be positive");
        }
        if (duration / resolution <= 0) {
            return false;
        }
        return false;
    }

    public boolean remove(int timerId) {
        return false;
    }

    public boolean poll(final long now, TimerHandler handler, final long duration) {
        // process expired timers from current timestamp to timestamp
        if (now <= timestamp) {
            return false;
        }

        int stop = (int) ((now - timestamp) / resolution);
        for (int position = bucket; position < stop; ++position) {
            processExpired();
        }

        // FIXME
        // this.timestamp = timestamp;

        return false;
    }

    private void processExpired() {

        TimerHandler handler = (int o, int c, int[] arr) -> {
            return true;
        };

        handler.onExpiredTimers(0, 0, timersByBucket);

    }

    @FunctionalInterface
    public interface TimerHandler {

        boolean onExpiredTimers(int offset, int count, int[] timers);

    }
}
