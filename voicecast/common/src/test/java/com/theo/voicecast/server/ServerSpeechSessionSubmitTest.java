package com.theo.voicecast.server;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechRecognizer;
import com.theo.voicecast.api.SpeechOptions;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refine M13 F1: {@link ServerSpeechSession#setVocabulary} and
 * {@link ServerSpeechSession#setCastMode} must go through the REE-tolerant
 * {@code submit} helper — a game-thread caller racing {@link ServerSpeechSession#dispose()}
 * gets a silent no-op, never an uncaught {@link java.util.concurrent.RejectedExecutionException}.
 *
 * <p>The real constructor needs a live {@code ServerPlayer} (thread-factory
 * naming), so the tests build a detached instance via
 * {@link Unsafe#allocateInstance} and inject the worker executor directly —
 * no Minecraft bootstrap required.
 */
class ServerSpeechSessionSubmitTest {

    /** Records setVocabulary payloads; everything else is a no-op stub. */
    private static final class RecordingRecognizer implements SpeechRecognizer {
        final CountDownLatch called = new CountDownLatch(1);
        volatile Collection<Pronunciation> received;

        @Override public String id() { return "recording"; }
        @Override public String displayName() { return "recording"; }
        @Override public void start(SpeechOptions options) {}
        @Override public void stop() {}
        @Override public boolean isActive() { return true; }
        @Override public void setVocabulary(Collection<Pronunciation> vocabulary) {
            received = List.copyOf(vocabulary);
            called.countDown();
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static ServerSpeechSession detachedSession() throws Exception {
        ServerSpeechSession s = (ServerSpeechSession) unsafe().allocateInstance(ServerSpeechSession.class);
        setField(s, "engine", "noop");
        return s;
    }

    private static ThreadPoolExecutor newWorker() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "test-session-worker");
            t.setDaemon(true);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ServerSpeechSession.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value); // OK for instance (also final) fields via setAccessible
    }

    @Test
    void setVocabularyAfterDisposeIsSilent() throws Exception {
        ServerSpeechSession s = detachedSession();
        ThreadPoolExecutor dead = newWorker();
        dead.shutdown(); // submit() now throws RejectedExecutionException
        setField(s, "worker", dead);

        assertDoesNotThrow(() -> s.setVocabulary(List.of()));
    }

    @Test
    void setCastModeAfterDisposeIsSilent() throws Exception {
        ServerSpeechSession s = detachedSession();
        ThreadPoolExecutor dead = newWorker();
        dead.shutdown();
        setField(s, "worker", dead);

        assertDoesNotThrow(() -> s.setCastMode(CastMode.OPEN, List.of()));
        assertDoesNotThrow(() -> s.setCastMode(null, null));
    }

    @Test
    void liveSessionRoutesVocabularyThroughWorker() throws Exception {
        ServerSpeechSession s = detachedSession();
        ThreadPoolExecutor worker = newWorker();
        setField(s, "worker", worker);
        RecordingRecognizer recognizer = new RecordingRecognizer();
        setField(s, "recognizer", recognizer);
        Pronunciation p = new Pronunciation("wizardreal:ignis", List.of(), List.of("ignis"));

        s.setVocabulary(List.of(p));
        assertTrue(recognizer.called.await(5, TimeUnit.SECONDS), "worker task did not run");
        assertEquals(List.of("wizardreal:ignis"),
                recognizer.received.stream().map(Pronunciation::id).toList());

        s.setCastMode(CastMode.OPEN, List.of());
        assertTrue(recognizer.called.await(5, TimeUnit.SECONDS), "worker task did not re-run");

        worker.shutdownNow();
    }
}
