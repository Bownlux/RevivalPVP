// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.matchmaking;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.network.BackendHttpClient;
import net.revivalsmp.pvp.network.BackendWS;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side friends + duel-invite state. Sibling to MatchmakingService.
 *
 * Holds the in-memory mirror of /friends and /friends/requests; refreshed on
 * hub open and on relevant WS push events. Also tracks the currently-active
 * incoming duel invite (drives the top-right overlay in PVPHubScreen).
 */
public class FriendsService {

    /** A friend row from GET /friends. */
    public record Friend(String uuid, String username, String onlineState,
                         String kit, String rank, String division, int lp) {
        public boolean isOnline()       { return !"offline".equalsIgnoreCase(onlineState); }
        public boolean canInvite()      { return "idle".equalsIgnoreCase(onlineState); }
    }

    /** An incoming friend request awaiting accept/reject. */
    public record FriendRequest(long id, String senderUuid, String senderUsername) {}

    /** A friend request WE sent that the other side hasn't acted on yet.
     *  Surfaced in the pending section so the sender can see what's awaiting. */
    public record OutgoingRequest(long id, String targetUuid, String targetUsername) {}

    /** A duel invite, either incoming (we're the target) or outgoing (we sent it). */
    public record DuelInvite(String inviteId, String otherUuid, String otherUsername,
                             String kit, boolean ranked, long receivedAt) {
        public long secondsLeft() {
            long elapsed = (System.currentTimeMillis() - receivedAt) / 1000;
            return Math.max(0, 30 - elapsed);
        }
    }

    private final BackendWS ws;

    private final List<Friend>           friends      = new ArrayList<>();
    private final List<FriendRequest>    incoming     = new ArrayList<>();
    private final List<OutgoingRequest>  outgoing     = new ArrayList<>();
    private volatile DuelInvite       activeInvite;     // most recent incoming invite
    private volatile DuelInvite       pendingOutgoing;  // we sent one, waiting on accept/decline
    private volatile boolean          loading;
    private volatile long             lastErrAt;
    private volatile String           lastError;

    public FriendsService(BackendWS ws) {
        this.ws = ws;
        ws.on("friend_request_received", this::onFriendRequestReceived);
        ws.on("friend_added",            this::onFriendAdded);
        ws.on("duel_invite_received",    this::onDuelInviteReceived);
        ws.on("duel_invite_sent",        this::onDuelInviteSent);
        ws.on("duel_invite_resolved",    this::onDuelInviteResolved);
        ws.on("friend_error",            this::onFriendError);
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    /** Pulls /friends + /friends/requests in parallel. Idempotent, caller can
     *  invoke per hub-open without worrying about stacking. */
    public synchronized void refresh() {
        if (loading) return;
        loading = true;
        BackendHttpClient.listFriends().thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            if (resp != null && resp.has("friends")) {
                synchronized (FriendsService.this) {
                    friends.clear();
                    for (var el : resp.getAsJsonArray("friends")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String kit = null, rank = null, div = null; int lp = 0;
                        if (o.has("kit_rank") && o.get("kit_rank").isJsonObject()) {
                            JsonObject kr = o.getAsJsonObject("kit_rank");
                            kit  = optStr(kr, "kit");
                            rank = optStr(kr, "rank");
                            div  = optStr(kr, "division");
                            lp   = kr.has("lp") && !kr.get("lp").isJsonNull() ? kr.get("lp").getAsInt() : 0;
                        }
                        // Defensive: skip friend rows missing required fields
                        // rather than crash. A schema drift on the backend
                        // shouldn't break the entire friends list.
                        if (!o.has("uuid") || !o.has("username")) continue;
                        friends.add(new Friend(
                            o.get("uuid").getAsString(),
                            o.get("username").getAsString(),
                            optStr(o, "online_state"),
                            kit, rank, div, lp
                        ));
                    }
                }
            }
            loading = false;
        }));
        BackendHttpClient.listFriendRequests().thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            if (resp == null) return;
            synchronized (FriendsService.this) {
                if (resp.has("incoming")) {
                    incoming.clear();
                    for (var el : resp.getAsJsonArray("incoming")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        incoming.add(new FriendRequest(
                            o.get("id").getAsLong(),
                            o.get("sender_uuid").getAsString(),
                            o.get("sender_username").getAsString()
                        ));
                    }
                }
                if (resp.has("outgoing")) {
                    outgoing.clear();
                    for (var el : resp.getAsJsonArray("outgoing")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        outgoing.add(new OutgoingRequest(
                            o.get("id").getAsLong(),
                            o.get("target_uuid").getAsString(),
                            o.get("target_username").getAsString()
                        ));
                    }
                }
            }
        }));
    }

    // ── Actions (HTTP, server returns the canonical state on success) ───────

    public void sendRequest(String username, Runnable onDone) {
        BackendHttpClient.sendFriendRequest(username).thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            if (resp == null || resp.has("_error")) {
                lastError = resp != null && resp.has("_error")
                    ? resp.get("_error").getAsString()
                    : "Friend request failed";
                lastErrAt = System.currentTimeMillis();
            } else {
                refresh();
            }
            if (onDone != null) onDone.run();
        }));
    }

    public void accept(long requestId) {
        BackendHttpClient.acceptFriendRequest(requestId)
            .thenAccept(r -> Minecraft.getInstance().execute(this::refresh));
    }

    public void reject(long requestId) {
        BackendHttpClient.rejectFriendRequest(requestId)
            .thenAccept(r -> Minecraft.getInstance().execute(() -> {
                synchronized (this) {
                    incoming.removeIf(req -> req.id() == requestId);
                }
            }));
    }

    public void unfriend(String friendUuid) {
        BackendHttpClient.unfriend(friendUuid)
            .thenAccept(r -> Minecraft.getInstance().execute(() -> {
                synchronized (this) {
                    friends.removeIf(f -> f.uuid().equals(friendUuid));
                }
            }));
    }

    public void invite(String friendUuid, String kit, boolean ranked) {
        BackendHttpClient.sendDuelInvite(friendUuid, kit, ranked).thenAccept(r -> {
            // Server pushes duel_invite_sent over WS → onDuelInviteSent populates pendingOutgoing.
            if (r == null) Minecraft.getInstance().execute(() -> {
                lastError = "Failed to send duel invite";
                lastErrAt = System.currentTimeMillis();
            });
        });
    }

    public void acceptIncomingInvite() {
        DuelInvite inv = activeInvite;
        if (inv == null) return;
        BackendHttpClient.acceptDuelInvite(inv.inviteId());
        activeInvite = null;
    }

    public void declineIncomingInvite() {
        DuelInvite inv = activeInvite;
        if (inv == null) return;
        BackendHttpClient.declineDuelInvite(inv.inviteId());
        activeInvite = null;
    }

    // ── WS push handlers ────────────────────────────────────────────────────

    private void onFriendRequestReceived(JsonObject msg) {
        Minecraft.getInstance().execute(() -> {
            synchronized (this) {
                incoming.add(new FriendRequest(
                    msg.get("request_id").getAsLong(),
                    msg.get("sender_uuid").getAsString(),
                    msg.get("sender_username").getAsString()
                ));
            }
        });
    }

    private void onFriendAdded(JsonObject msg) {
        Minecraft.getInstance().execute(this::refresh);
    }

    private void onDuelInviteReceived(JsonObject msg) {
        Minecraft.getInstance().execute(() -> {
            activeInvite = new DuelInvite(
                msg.get("invite_id").getAsString(),
                msg.get("sender_uuid").getAsString(),
                msg.get("sender_username").getAsString(),
                msg.get("kit").getAsString(),
                msg.has("ranked") && msg.get("ranked").getAsBoolean(),
                System.currentTimeMillis()
            );
        });
    }

    private void onDuelInviteSent(JsonObject msg) {
        Minecraft.getInstance().execute(() -> {
            pendingOutgoing = new DuelInvite(
                msg.get("invite_id").getAsString(),
                msg.get("target_uuid").getAsString(),
                msg.get("target_username").getAsString(),
                msg.get("kit").getAsString(),
                msg.has("ranked") && msg.get("ranked").getAsBoolean(),
                System.currentTimeMillis()
            );
        });
    }

    private void onDuelInviteResolved(JsonObject msg) {
        Minecraft.getInstance().execute(() -> {
            String state = optStr(msg, "state");
            String invId = optStr(msg, "invite_id");
            if (activeInvite != null && invId != null && invId.equals(activeInvite.inviteId())) {
                activeInvite = null;
            }
            if (pendingOutgoing != null && invId != null && invId.equals(pendingOutgoing.inviteId())) {
                pendingOutgoing = null;
            }
            if ("expired".equals(state) || "declined".equals(state)) {
                lastError = "duel invite " + state;
                lastErrAt = System.currentTimeMillis();
            }
        });
    }

    private void onFriendError(JsonObject msg) {
        Minecraft.getInstance().execute(() -> {
            lastError = optStr(msg, "reason");
            lastErrAt = System.currentTimeMillis();
            RevivalPVPMod.LOGGER.warn("Friend error: {}", lastError);
        });
    }

    // ── Auto-expire active invite client-side after 30s ──────────────────────

    public void tick() {
        DuelInvite inv = activeInvite;
        if (inv != null && inv.secondsLeft() <= 0) {
            activeInvite = null;
        }
        DuelInvite out = pendingOutgoing;
        if (out != null && out.secondsLeft() <= 0) {
            pendingOutgoing = null;
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public synchronized List<Friend>           friends()         { return List.copyOf(friends); }
    public synchronized List<FriendRequest>    incomingRequests() { return List.copyOf(incoming); }
    public synchronized List<OutgoingRequest>  outgoingRequests() { return List.copyOf(outgoing); }
    public DuelInvite                       activeInvite()     { return activeInvite; }
    public DuelInvite                       pendingOutgoing()  { return pendingOutgoing; }
    public String                           lastError()        { return lastError; }
    public long                             lastErrorAt()      { return lastErrAt; }
    public void                             clearError()       { lastError = null; }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}