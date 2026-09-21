package com.theo.voicecast.server;

import com.mojang.authlib.GameProfile;
import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.SpeechOptions;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refiner M13 R2-A: session-level cast-mode state machine on
 * {@link VoiceCastServer} (exhaustive table rows V1/V2/V4/V5).
 *
 * <ul>
 *   <li>V1+V2 — setCastMode with no session stashes the declaration; the
 *       stash is replayed onto the session created afterwards
 *       ({@code VoiceCastServer.session}; the stash is retained by design —
 *       declarations outlive session rebuilds).</li>
 *   <li>V4 — setCastMode(null) with a live session clears both the stash and
 *       the session's declaration (null = full vocabulary re-route).</li>
 *   <li>V5 — onPlayerQuit removes + disposes the session and clears the
 *       stash (both maps empty afterwards).</li>
 * </ul>
 *
 * <p>No production code is touched: the {@link VoiceCastServer} singleton's
 * {@code sessions}/{@code castModes} maps (and {@code defaultEngine}) are
 * swapped via reflection; sessions are either detached
 * {@link ServerSpeechSession} instances built with
 * {@link Unsafe#allocateInstance} (as in {@link ServerSpeechSessionSubmitTest})
 * or created through the real private {@code session()} path. The fake
 * {@link ServerPlayer} carries only what these paths touch: {@code uuid}
 * (map keys) and {@code gameProfile} (the session constructor's worker
 * thread naming). No Mockito.
 */
class ServerSpeechSessionCastModeStateTest {

    /** Records every setVocabulary payload (order-preserving); else a stub. */
    private static final class RecordingRecognizer implements SpeechRecognizer {
        final CountDownLatch calls;
        final List<SessionVocabulary> received = new ArrayList<>();

        RecordingRecognizer(int expectedCalls) { this.calls = new CountDownLatch(expectedCalls); }

        @Override public String id() { return "recording"; }
        @Override public String displayName() { return "recording"; }
        @Override public void start(SpeechOptions options) {}
        @Override public void stop() {}
        @Override public boolean isActive() { return true; }
        @Override public synchronized void setVocabulary(SessionVocabulary vocabulary) {
            received.add(vocabulary == null ? SessionVocabulary.EMPTY : vocabulary);
            calls.countDown();
        }

        SessionVocabulary awaitLastPayload() throws InterruptedException {
            assertTrue(calls.await(5, TimeUnit.SECONDS), "recognizer calls did not arrive in time");
            synchronized (this) { return received.get(received.size() - 1); }
        }
    }

    // ---- Unsafe + reflection plumbing (same pattern as ServerSpeechSessionSubmitTest) ----

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value); // OK for instance (also final) fields via setAccessible
    }

    private static Object getField(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /**
     * Detached ServerPlayer: only {@code uuid} (ConcurrentHashMap keys; a null
     * key would NPE in sessions.put/remove) and {@code gameProfile} (worker
     * thread naming in the real session constructor) are populated. The
     * vanilla bootstrap is needed because {@code Entity}'s static init builds
     * the registries ("Not bootstrapped" otherwise) and its datafixers need
     * the version detected first — vanilla {@code Main} does both in this
     * order; both calls are idempotent, pure Java.
     */
    private static ServerPlayer fakePlayer(UUID uuid) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ServerPlayer sp = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
        setField(Entity.class, sp, "uuid", uuid);
        setField(Player.class, sp, "gameProfile", new GameProfile(uuid, "player-" + uuid.toString().substring(0, 8)));
        return sp;
    }

    /**
     * Detached session on a live single-thread worker. Field initializers do
     * not run on {@link Unsafe#allocateInstance} instances, so {@code engine}
     * and {@code vocabulary} are injected explicitly — in production
     * {@code vocabulary} is never null (constructor initializer + the null
     * guard in {@code setVocabulary}); the session-level re-route
     * ({@code routedVocabulary()}) returns it as the full candidate set on
     * language-agnostic engines like noop.
     */
    private static ServerSpeechSession detachedSession() throws Exception {
        ServerSpeechSession s = (ServerSpeechSession) unsafe().allocateInstance(ServerSpeechSession.class);
        setField(ServerSpeechSession.class, s, "engine", "noop");
        setField(ServerSpeechSession.class, s, "vocabulary", SessionVocabulary.EMPTY);
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "test-castmode-state-worker");
            t.setDaemon(true);
            return t;
        });
        worker.allowCoreThreadTimeOut(true);
        setField(ServerSpeechSession.class, s, "worker", worker);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerSpeechSession> sessions() throws Exception {
        return (Map<UUID, ServerSpeechSession>) getField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "sessions");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> castModes() throws Exception {
        return (Map<UUID, Object>) getField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "castModes");
    }

    /** Fresh singleton state: empty maps, noop default engine, no config. */
    private static void resetServerState() throws Exception {
        setField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "sessions", new ConcurrentHashMap<>());
        setField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "castModes", new ConcurrentHashMap<>());
        setField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "defaultEngine", "noop");
        setField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "vocabulary", SessionVocabulary.EMPTY);
    }

    private static ServerSpeechSession createSessionViaPrivateMethod(ServerPlayer sp) throws Exception {
        Method session = VoiceCastServer.class.getDeclaredMethod("session", ServerPlayer.class);
        session.setAccessible(true);
        return (ServerSpeechSession) session.invoke(VoiceCastServer.INSTANCE, sp);
    }

    private static Object declaration(Object stashEntry, String component) throws Exception {
        return getField(stashEntry.getClass(), stashEntry, component);
    }

    // ---- V1 + V2: stash before the session exists, replay on session() ----

    @Test
    void stashWrittenWithoutSessionThenReplayedOnSessionCreation() throws Exception {
        resetServerState();
        UUID id = UUID.randomUUID();
        ServerPlayer sp = fakePlayer(id);
        List<String> spells = List.of("wizardreal:ignis", "wizardreal:aqua");

        // V1: no session yet — the declaration lands in the castModes stash...
        VoiceCastServer.INSTANCE.setCastMode(sp, CastMode.CHANT_CONFIRM, spells);

        Map<UUID, Object> stash = castModes();
        assertEquals(1, stash.size(), "declaration must be stashed for the not-yet-created session");
        Object decl = stash.get(id);
        assertEquals(CastMode.CHANT_CONFIRM, declaration(decl, "mode"));
        assertEquals(spells, declaration(decl, "spellIds"));
        assertTrue(sessions().isEmpty(), "no session may be created by setCastMode");

        // ...and the real session() creation path replays it onto the new session.
        ServerSpeechSession s = createSessionViaPrivateMethod(sp);

        assertEquals(CastMode.CHANT_CONFIRM, getField(ServerSpeechSession.class, s, "castMode"),
                "session() must apply the stashed mode");
        assertEquals(spells, getField(ServerSpeechSession.class, s, "castSpellIds"),
                "session() must replay the stashed spell ids");
        assertEquals(1, sessions().size(), "session() must register the created session");
        assertEquals(1, stash.size(), "stash is retained after replay (design: outlives session rebuilds)");

        s.dispose();
    }

    // ---- V4: null declaration clears stash AND live session ----

    @Test
    void nullDeclarationClearsStashAndLiveSession() throws Exception {
        resetServerState();
        UUID id = UUID.randomUUID();
        ServerPlayer sp = fakePlayer(id);
        List<String> spells = List.of("wizardreal:ignis");
        SessionVocabulary p0 = new SessionVocabulary(List.of(new SessionVocabulary.Entry("wizardreal:ignis", List.of(), List.of("ignis"), null, null)));
        setField(VoiceCastServer.class, VoiceCastServer.INSTANCE, "vocabulary", p0);

        ServerSpeechSession s = detachedSession();
        // Seed the session's vocabulary the way session()/setVocabulary would.
        setField(ServerSpeechSession.class, s, "vocabulary", p0);
        RecordingRecognizer recognizer = new RecordingRecognizer(2); // one call per setCastMode
        setField(ServerSpeechSession.class, s, "recognizer", recognizer);
        sessions().put(id, s);

        // Pre-state: a live declaration reaches both the stash and the session.
        VoiceCastServer.INSTANCE.setCastMode(sp, CastMode.CHANT_CONFIRM, spells);
        assertEquals(CastMode.CHANT_CONFIRM, getField(ServerSpeechSession.class, s, "castMode"));
        assertEquals(spells, getField(ServerSpeechSession.class, s, "castSpellIds"));
        assertEquals(1, castModes().size());

        // V4: null declaration double-clears (VoiceCastServer.setCastMode lines 423-429).
        VoiceCastServer.INSTANCE.setCastMode(sp, null, null);

        assertTrue(castModes().isEmpty(), "null mode must remove the stash entry");
        assertNull(getField(ServerSpeechSession.class, s, "castMode"),
                "null mode must clear the session's declaration");
        assertEquals(List.of(), getField(ServerSpeechSession.class, s, "castSpellIds"));
        // The clearing re-route still reaches the recognizer (null mode = full vocabulary).
        assertEquals(List.of("wizardreal:ignis"), recognizer.awaitLastPayload().entries().stream().map(SessionVocabulary.Entry::id).toList());

        s.dispose();
    }

    // ---- V5: onPlayerQuit removes + disposes the session and clears the stash ----

    @Test
    void playerQuitDisposesSessionAndClearsStash() throws Exception {
        resetServerState();
        UUID id = UUID.randomUUID();
        ServerPlayer sp = fakePlayer(id);

        ServerSpeechSession s = detachedSession();
        sessions().put(id, s);
        VoiceCastServer.INSTANCE.setCastMode(sp, CastMode.OPEN, List.of());

        assertEquals(1, sessions().size());
        assertEquals(1, castModes().size());

        // V5 (VoiceCastServer.onPlayerQuit): sessions remove + dispose, castModes remove.
        VoiceCastServer.INSTANCE.onPlayerQuit(sp);

        assertTrue(sessions().isEmpty(), "quit must remove the session");
        assertTrue(castModes().isEmpty(), "quit must remove the stashed declaration");
        ThreadPoolExecutor worker = (ThreadPoolExecutor) getField(ServerSpeechSession.class, s, "worker");
        assertTrue(worker.isTerminated(), "quit must dispose the removed session");
    }
}
