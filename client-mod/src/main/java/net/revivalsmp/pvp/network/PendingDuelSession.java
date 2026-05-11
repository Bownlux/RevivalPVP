// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

/**
 * Holds the session token for the in-flight duel connection.
 * The token is sent to the duel server via a plugin message on join
 * so the RevivalPVP plugin can authenticate and start the duel.
 */
public final class PendingDuelSession {

    private static volatile String token = null;
    private static volatile String host  = null;
    private static volatile int    port  = 0;

    public static void set(String sessionToken, String duelHost, int duelPort) {
        token = sessionToken;
        host  = duelHost;
        port  = duelPort;
    }

    public static String token() { return token; }
    public static String host()  { return host; }
    public static int    port()  { return port; }

    public static boolean hasPending() { return token != null; }

    public static void clear() {
        token = null;
        host  = null;
        port  = 0;
    }
}
