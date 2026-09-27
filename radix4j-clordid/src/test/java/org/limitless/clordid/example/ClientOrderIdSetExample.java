package org.limitless.clordid.example;

import org.limitless.clordid.ClientOrderIdSet;
import org.limitless.clordid.Pattern;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Duplicate ClOrdID check in a FIX order gateway. Each session is a scope of the set, and the ClOrdID (tag 11) of
 * every NewOrderSingle is checked in place in the received bytes, without creating a String. Messages here are
 * bodies only, without the header and trailer fields.
 * <pre>
 *   ./gradlew :radix4j-clordid:testClasses
 *   java -cp radix4j-clordid/build/classes/java/test:radix4j-clordid/build/classes/java/main:&lt;fsmp4j jar&gt; \
 *       org.limitless.clordid.example.ClientOrderIdSetExample
 * </pre>
 */
public final class ClientOrderIdSetExample implements AutoCloseable {

    private static final byte SOH = 1;
    private static final byte[] CL_ORD_ID = "11=".getBytes(StandardCharsets.US_ASCII);

    private final ClientOrderIdSet ids = new ClientOrderIdSet();
    private final Map<String, Integer> scopes = new HashMap<>();

    /**
     * Give a session its scope when it first logs on. The pattern is set before the session's first id and kept
     * across trading days; {@link Pattern#auto()} detects it from the first ids of each day instead.
     * @return scope
     */
    public int logon(final String session, final Pattern pattern) {
        Integer scope = scopes.get(session);
        if (scope == null) {
            scope = scopes.size();
            scopes.put(session, scope);
            ids.pattern(scope, pattern);
        }
        return scope;
    }

    /**
     * Check the ClOrdID of a NewOrderSingle
     * @return null when accepted, otherwise the reject reason
     */
    public String onNewOrderSingle(final int scope, final byte[] message, final int offset, final int length) {
        final int start = valueStart(message, offset, length);
        if (start < 0) {
            return "missing ClOrdID";
        }
        int end = start;
        while (end < offset + length && message[end] != SOH) {
            ++end;
        }
        try {
            return ids.add(scope, message, start, end - start) == ClientOrderIdSet.ADDED ? null : "duplicate ClOrdID";
        } catch (final IllegalArgumentException invalid) {
            return "invalid ClOrdID";
        }
    }

    /**
     * End of the trading day: all ids are freed, and the ids may be used again
     */
    public void endOfDay() {
        ids.rollover();
    }

    @Override
    public void close() {
        ids.close();
    }

    private static int valueStart(final byte[] message, final int offset, final int length) {
        final int end = offset + length - CL_ORD_ID.length;
        for (int i = offset; i <= end; ++i) {
            if ((i == offset || message[i - 1] == SOH) && message[i] == CL_ORD_ID[0] &&
                message[i + 1] == CL_ORD_ID[1] && message[i + 2] == CL_ORD_ID[2]) {
                return i + CL_ORD_ID.length;
            }
        }
        return -1;
    }

    private static byte[] newOrderSingle(final String clOrdId) {
        return ("35=D\u000111=" + clOrdId + "\u000155=VOD.L\u000154=1\u000138=100\u000140=2\u000144=101.5\u0001")
            .getBytes(StandardCharsets.US_ASCII);
    }

    private static void send(final ClientOrderIdSetExample gateway, final String session, final int scope,
                             final String clOrdId) {
        final byte[] message = newOrderSingle(clOrdId);
        final String reject = gateway.onNewOrderSingle(scope, message, 0, message.length);
        System.out.printf("%-7s %-36s %s%n", session, clOrdId.replace("\t", "\\t"), reject == null ? "accepted" : "rejected: " + reject);
    }

    public static void main(final String[] args) {
        try (ClientOrderIdSetExample gateway = new ClientOrderIdSetExample()) {
            final int fund = gateway.logon("FUND-A", Pattern.auto());        // ORD-000001, ORD-000002, ...
            final int algo = gateway.logon("ALGO-B", Pattern.decimal(3));    // ARB00045612X7Y: number, 3-character suffix
            final int bank = gateway.logon("BANK-C", Pattern.none());        // UUIDs, no number to extract

            send(gateway, "FUND-A", fund, "ORD-000001");
            send(gateway, "FUND-A", fund, "ORD-000001");                     // resent after a reconnect
            send(gateway, "ALGO-B", algo, "ORD-000001");                     // another session may use it
            send(gateway, "FUND-A", fund, "ORD-0000000000000000000000000000001");
            send(gateway, "FUND-A", fund, "ORD\t000002");

            for (int i = 2; i <= 1_000_000; ++i) {
                final byte[] message = newOrderSingle(String.format("ORD-%06d", i));
                gateway.onNewOrderSingle(fund, message, 0, message.length);
            }
            for (int i = 1; i <= 200_000; ++i) {
                final byte[] message = newOrderSingle(String.format("ARB%08d%s", 45_000 + 3 * i, "X7Y"));
                gateway.onNewOrderSingle(algo, message, 0, message.length);
            }
            for (int i = 1; i <= 10_000; ++i) {
                final byte[] message = newOrderSingle(UUID.randomUUID().toString().replace("-", ""));
                gateway.onNewOrderSingle(bank, message, 0, message.length);
            }

            final ClientOrderIdSet ids = gateway.ids;
            final ClientOrderIdSet.Statistics statistics = ids.statistics();
            System.out.printf("%nFUND-A pattern %s, ALGO-B pattern %s, BANK-C pattern %s%n",
                ids.pattern(fund), ids.pattern(algo), ids.pattern(bank));
            System.out.printf("%,d ids: %,d counters under %,d prefixes, %,d whole ids%n",
                ids.size(), statistics.counterIds(), statistics.prefixes(), statistics.fallbackIds());
            System.out.printf("%,d KB off-heap, %,d KB heap, %.1f bytes per id%n%n",
                statistics.offHeapBytes() >> 10, statistics.heapBytes() >> 10,
                (double) (statistics.offHeapBytes() + statistics.heapBytes()) / ids.size());

            gateway.endOfDay();
            send(gateway, "FUND-A", fund, "ORD-000001");                     // a new trading day
        }
    }
}
