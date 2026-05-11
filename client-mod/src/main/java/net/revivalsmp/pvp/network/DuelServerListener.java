// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.client.RevivalPVPClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Handles plugin message channel communication with the duel server.
 *
 * On join to the duel server:
 *   → sends session token on "revivalpvp:auth" channel
 *
 * Receives from duel server:
 *   "revivalpvp:duel_start" , duel is about to begin (countdown)
 *   "revivalpvp:duel_end"   , duel ended; payload = winner UUID
 *   "revivalpvp:return"     , server tells client to reconnect to original server
 *
 * 26.1 migration: plugin messages are now CustomPacketPayloads. We carry the raw
 * bytes verbatim so the wire format stays identical to the legacy protocol the
 * server-side plugin expects (DataInputStream / DataOutputStream framing).
 */
public class DuelServerListener {

    public static final Identifier CH_AUTH        = Identifier.fromNamespaceAndPath("revivalpvp", "auth");
    public static final Identifier CH_DUEL_START  = Identifier.fromNamespaceAndPath("revivalpvp", "duel_start");
    public static final Identifier CH_DUEL_END    = Identifier.fromNamespaceAndPath("revivalpvp", "duel_end");
    public static final Identifier CH_RETURN      = Identifier.fromNamespaceAndPath("revivalpvp", "return");

    public static final CustomPacketPayload.Type<AuthC2S>      AUTH_TYPE       = new CustomPacketPayload.Type<>(CH_AUTH);
    public static final CustomPacketPayload.Type<DuelStartS2C> DUEL_START_TYPE = new CustomPacketPayload.Type<>(CH_DUEL_START);
    public static final CustomPacketPayload.Type<DuelEndS2C>   DUEL_END_TYPE   = new CustomPacketPayload.Type<>(CH_DUEL_END);
    public static final CustomPacketPayload.Type<ReturnS2C>    RETURN_TYPE     = new CustomPacketPayload.Type<>(CH_RETURN);

    public static void register() {
        // Register payload types
        PayloadTypeRegistry.serverboundPlay().register(AUTH_TYPE, AuthC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DUEL_START_TYPE, DuelStartS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DUEL_END_TYPE, DuelEndS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RETURN_TYPE, ReturnS2C.CODEC);

        // Send auth token immediately on joining the duel server
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!PendingDuelSession.hasPending()) return;

            String token = PendingDuelSession.token();
            PendingDuelSession.clear();

            // Match the legacy DataInputStream framing: int length + UTF-8 bytes
            byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeInt(tokenBytes.length);
                dos.write(tokenBytes);
                ClientPlayNetworking.send(new AuthC2S(baos.toByteArray()));
                RevivalPVPMod.LOGGER.info("Sent duel auth token to server.");
            } catch (Exception e) {
                RevivalPVPMod.LOGGER.warn("Failed to send duel auth token: {}", e.getMessage());
            }
        });

        // duel_start, transition from MATCH_FOUND → IN_DUEL
        ClientPlayNetworking.registerGlobalReceiver(DUEL_START_TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                RevivalPVPClient.get().matchmaking().onDuelStart();
                ctx.client().gui.setOverlayMessage(Component.literal("§a§lFIGHT!"), false);
            });
        });

        // duel_end, show result screen, transition to RESULT state
        ClientPlayNetworking.registerGlobalReceiver(DUEL_END_TYPE, (payload, ctx) -> {
            // Match the server's DataOutputStream framing exactly
            try {
                DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload.bytes()));
                boolean won      = dis.readBoolean();
                int lpChange     = dis.readInt();
                int rankLen      = dis.readInt();
                byte[] rankBytes = new byte[rankLen];
                dis.readFully(rankBytes);
                String newRank   = new String(rankBytes, StandardCharsets.UTF_8);
                boolean promoted = dis.readBoolean();

                ctx.client().execute(() ->
                    RevivalPVPClient.get().matchmaking()
                        .onDuelEnd(won, lpChange, newRank, promoted));
            } catch (Exception e) {
                RevivalPVPMod.LOGGER.warn("Bad duel_end payload: {}", e.getMessage());
            }
        });

        // return, reconnect to original server after duel
        ClientPlayNetworking.registerGlobalReceiver(RETURN_TYPE, (payload, ctx) -> {
            ctx.client().execute(DuelServerConnector::returnFromDuel);
        });
    }

    // ── Payload records ───────────────────────────────────────────────────────

    public record AuthC2S(byte[] bytes) implements CustomPacketPayload {
        public static final StreamCodec<FriendlyByteBuf, AuthC2S> CODEC = rawCodec(AuthC2S::new, AuthC2S::bytes);
        @Override public Type<AuthC2S> type() { return AUTH_TYPE; }
    }

    public record DuelStartS2C(byte[] bytes) implements CustomPacketPayload {
        public static final StreamCodec<FriendlyByteBuf, DuelStartS2C> CODEC = rawCodec(DuelStartS2C::new, DuelStartS2C::bytes);
        @Override public Type<DuelStartS2C> type() { return DUEL_START_TYPE; }
    }

    public record DuelEndS2C(byte[] bytes) implements CustomPacketPayload {
        public static final StreamCodec<FriendlyByteBuf, DuelEndS2C> CODEC = rawCodec(DuelEndS2C::new, DuelEndS2C::bytes);
        @Override public Type<DuelEndS2C> type() { return DUEL_END_TYPE; }
    }

    public record ReturnS2C(byte[] bytes) implements CustomPacketPayload {
        public static final StreamCodec<FriendlyByteBuf, ReturnS2C> CODEC = rawCodec(ReturnS2C::new, ReturnS2C::bytes);
        @Override public Type<ReturnS2C> type() { return RETURN_TYPE; }
    }

    /** Codec that writes the bytes verbatim and reads everything remaining in the buffer. */
    private static <T> StreamCodec<FriendlyByteBuf, T> rawCodec(
            java.util.function.Function<byte[], T> ctor,
            java.util.function.Function<T, byte[]> getter) {
        return StreamCodec.of(
                (buf, value) -> buf.writeBytes(getter.apply(value)),
                buf -> {
                    byte[] arr = new byte[((ByteBuf) buf).readableBytes()];
                    buf.readBytes(arr);
                    return ctor.apply(arr);
                });
    }
}
