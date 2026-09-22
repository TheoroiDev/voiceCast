package com.theo.voicecast.server;

import com.theo.voicecast.api.SessionVocabulary;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.engine.EngineFamilies;
import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 F-B2 end-to-end at the session seam: a model dir that passes the file
 * probe but fails the native load (or whose recognizer start throws) used to
 * be retried at FRAME rate by {@code ensureReady} — the silent death loop.
 * After the fix the failed build sends the player ERROR state, engages the
 * {@link BuildBackoff}, and the next frame does NOT re-enter the factory
 * (asserted with a counting {@link EngineFamilies} factory). The detached
 * session pattern follows {@code ServerSpeechSessionSubmitTest}; no Minecraft
 * bootstrap (sendState is null-player guarded).
 */
class ServerSpeechSessionBuildRetryTest {

    private static final String ENGINE = "r2-retry-engine";
    private static final String FAMILY = "r2-retry-family";
    /** create() invocations — the "no reload loop" counter. */
    private static final AtomicInteger FACTORY_CALLS = new AtomicInteger();
    /** 0 = factory throws (broken model), 1 = start() throws, 2 = healthy. */
    private static final AtomicInteger FACTORY_MODE = new AtomicInteger(0);

    /** Healthy stub: starts and stays active. */
    private static final class HealthyRecognizer implements SpeechRecognizer {
        @Override public String id() { return "healthy"; }
        @Override public String displayName() { return "healthy"; }
        @Override public void start(SpeechOptions options) {}
        @Override public void stop() {}
        @Override public boolean isActive() { return true; }
        @Override public void setVocabulary(SessionVocabulary vocabulary) {}
    }

    /** start() throws — the "probe passed but native load failed" shape. */
    private static final class ThrowingStartRecognizer implements SpeechRecognizer {
        @Override public String id() { return "throwing-start"; }
        @Override public String displayName() { return "throwing-start"; }
        @Override public void start(SpeechOptions options) {
            throw new RuntimeException("simulated native load failure");
        }
        @Override public void stop() {}
        @Override public boolean isActive() { return false; }
        @Override public void setVocabulary(SessionVocabulary vocabulary) {}
    }

    @BeforeAll
    static void registerCountingFamily() {
        try {
            EngineFamilies.register(FAMILY, spec -> {
                FACTORY_CALLS.incrementAndGet();
                return switch (FACTORY_MODE.get()) {
                    case 1 -> new ThrowingStartRecognizer();
                    case 2 -> new HealthyRecognizer();
                    default -> throw new RuntimeException("simulated native load failure");
                };
            });
        } catch (IllegalStateException rerun) {
            // same-JVM rerun: the family is already registered
        }
    }

    @TempDir
    Path temp;

    @BeforeEach
    void injectSingletonState() throws Exception {
        // Minimal v2 catalog pointing ENGINE at the counting family.
        Path cfgDir = temp.resolve("config/voicecast");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("models.json"), """
                {
                  "version": 2,
                  "mirrorProbe": {"enabled": false},
                  "models": {
                    "%s": {
                      "properties": {"lang": ["en"], "type": "offline", "family": "%s"},
                      "source": {"kind": "sherpa-archive",
                                 "urls": ["https://example.invalid/%s.tar.bz2"]}
                    }
                  }
                }""".formatted(ENGINE, FAMILY, ENGINE), StandardCharsets.UTF_8);
        // A model dir that passes the R2 F-B1 content gate (tokens + ≥1 MB
        // onnx) — the whole point: gate-passing files do NOT mean a loadable
        // model, so the factory below simulates the native load failure.
        Path modelDir = temp.resolve("config/voicecast/models").resolve(ENGINE);
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("tokens.txt"), "tok", StandardCharsets.UTF_8);
        Files.write(modelDir.resolve("encoder.int8.onnx"), new byte[1 << 20]);
        setServerField("modelConfig", ModelConfig.load(temp));
        setServerField("runDir", temp);
        setEngineState(ENGINE, "READY");
        FACTORY_CALLS.set(0);
        FACTORY_MODE.set(0);
    }

    @AfterEach
    void restoreSingletonState() throws Exception {
        engineStates().remove(ENGINE);
        setServerField("modelConfig", null);
        setServerField("runDir", null);
    }

    // ---- singleton plumbing (VoiceCastServer is an enum singleton) --------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> engineStates() throws Exception {
        Field f = VoiceCastServer.class.getDeclaredField("engineStates");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(VoiceCastServer.INSTANCE);
    }

    private static void setEngineState(String engine, String state) throws Exception {
        Class<?> es = Class.forName("com.theo.voicecast.server.VoiceCastServer$EngineState");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object constant = Enum.valueOf((Class<? extends Enum>) es, state);
        engineStates().put(engine, constant);
    }

    private static void setServerField(String name, Object value) throws Exception {
        Field f = VoiceCastServer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(VoiceCastServer.INSTANCE, value);
    }

    // ---- detached session (no Minecraft bootstrap) ------------------------

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static ServerSpeechSession detachedSession() throws Exception {
        ServerSpeechSession s = (ServerSpeechSession) unsafe().allocateInstance(ServerSpeechSession.class);
        setField(s, "engine", ENGINE);
        setField(s, "vocabulary", SessionVocabulary.EMPTY);
        setField(s, "castSpellIds", List.of());
        setField(s, "buildBackoff", new BuildBackoff());
        return s;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ServerSpeechSession.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static BuildBackoff backoffOf(ServerSpeechSession s) throws Exception {
        return (BuildBackoff) getField(s, "buildBackoff");
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = ServerSpeechSession.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void callEnsureReady(ServerSpeechSession s) throws Exception {
        Method m = ServerSpeechSession.class.getDeclaredMethod("ensureReady");
        m.setAccessible(true);
        m.invoke(s);
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void brokenModelBuildFailsOnceThenBacksOffNoReloadLoop() throws Exception {
        FACTORY_MODE.set(0); // factory itself throws (native load failure inside create)
        ServerSpeechSession s = detachedSession();

        callEnsureReady(s); // frame 1: build attempt → failure

        assertEquals(1, FACTORY_CALLS.get(), "exactly one build attempt");
        BuildBackoff backoff = backoffOf(s);
        assertEquals(1, backoff.consecutiveFailures(), "failure recorded");
        assertFalse(backoff.allowed(System.currentTimeMillis()),
                "ERROR state was sent and the session is now backing off");

        callEnsureReady(s); // frame 2 (milliseconds later): must NOT reload
        callEnsureReady(s); // frame 3: ditto
        assertEquals(1, FACTORY_CALLS.get(),
                "no frame-rate reload loop — the backoff gates ensureReady (R2 F-B2)");
    }

    @Test
    void throwingStartAlsoBacksOffAfterReportingError() throws Exception {
        FACTORY_MODE.set(1); // factory returns a recognizer whose start() throws
        ServerSpeechSession s = detachedSession();

        callEnsureReady(s);

        assertEquals(1, FACTORY_CALLS.get());
        BuildBackoff backoff = backoffOf(s);
        assertEquals(1, backoff.consecutiveFailures());
        assertFalse(backoff.allowed(System.currentTimeMillis()));
        assertFalse((Boolean) getField(s, "active"), "session must stay inactive after the failure");
        callEnsureReady(s);
        assertEquals(1, FACTORY_CALLS.get(), "no loop for the configure-start failure path either");
    }

    @Test
    void successfulBuildResetsTheBackoff() throws Exception {
        FACTORY_MODE.set(0);
        ServerSpeechSession s = detachedSession();
        callEnsureReady(s);
        assertEquals(1, backoffOf(s).consecutiveFailures());

        FACTORY_MODE.set(2); // engine "recovers"
        callEnsureReady(s);  // after the quiet period this would run — backoff still engaged?
        // Still inside the quiet period: the retry is gated even though the
        // engine is healthy again — that is the point of a backoff.
        assertEquals(1, FACTORY_CALLS.get(), "quiet period still holds");

        backoffOf(s).force(); // e.g. the player re-selected the engine
        callEnsureReady(s);
        assertEquals(2, FACTORY_CALLS.get(), "forced retry happens");
        assertEquals(0, backoffOf(s).consecutiveFailures(), "success resets the backoff");
        assertTrue((Boolean) getField(s, "active"), "session goes active on the healthy build");
        callEnsureReady(s);
        assertEquals(2, FACTORY_CALLS.get(), "steady state: active session builds nothing new");
    }
}
