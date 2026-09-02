package com.theo.voicecast.engine;

import com.theo.voicecast.VoiceCast;
import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.RecognitionResult;
import com.theo.voicecast.api.SpeechOptions;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Default speech recognizer backed by Vosk (offline). The model is loaded from
 * {@code <modelPath>} (typically {@code config/voicecast/models/default});
 * vocabulary is restricted to the registered aliases when
 * {@link SpeechOptions#grammarOnly()} is true, which dramatically improves
 * accuracy for short incantations and makes the engine robust to made-up
 * words (Vosk's grammar is phonetic, so arbitrary ASCII spellings work as
 * long as they're in the phrase list).
 *
 * <p>Vosk and JNA keep their original package names ({@code org.vosk} /
 * {@code com.sun.jna}); nothing is relocated at build time.
 */
public final class VoskTextRecognizer extends AbstractBufferedRecognizer {

    private Model model;
    private Model sharedModel;
    private boolean ownsModel;
    private Recognizer recognizer;
    private SpeechOptions options;
    private final List<String> grammar = new ArrayList<>();
    private long utteranceStart;

    public VoskTextRecognizer() {}

    /**
     * Use a shared, already-loaded Vosk {@link Model} (server-side: one model for
     * the whole server, one Recognizer per session). This recognizer will not
     * load or close the model. Call before {@link #start}.
     */
    public void useSharedModel(Model shared) {
        this.sharedModel = shared;
        this.ownsModel = false;
    }

    /** Shared implementation id — all Vosk word engines (vosk-en/cn/jp/kr) use
     * this class; the language comes from the configured model binding. */
    @Override public String id() { return "vosk-en"; }

    @Override public String displayName() { return "Vosk (offline)"; }

    @Override
    public synchronized void start(SpeechOptions options) throws Exception {
        this.options = options;
        if (sharedModel != null) {
            this.model = sharedModel;
            this.ownsModel = false;
            try { LibVosk.setLogLevel(LogLevel.WARNINGS); } catch (Throwable ignored) {}
        } else {
            Path modelDir = Path.of(options.modelPath());
            if (!modelDir.toFile().isDirectory()) {
                throw new IllegalStateException("Vosk model not found at " + modelDir.toAbsolutePath());
            }
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
            } catch (Throwable ignored) {
                // older Vosk versions may not expose setLogLevel
            }
            VoiceCast.LOGGER.info("Loading Vosk model from {}", modelDir.toAbsolutePath());
            this.model = new Model(modelDir.toAbsolutePath().toString());
            this.ownsModel = true;
        }
        rebuildRecognizer();
        super.start(options);
        VoiceCast.LOGGER.info("Vosk recognizer ready (grammarOnly={}, phrases={}, shared={})",
                options.grammarOnly(), grammar.size(), sharedModel != null);
    }

    @Override
    public synchronized void setVocabulary(Collection<Pronunciation> vocabulary) {
        super.setVocabulary(vocabulary);
        List<String> phrases = new ArrayList<>();
        for (Pronunciation p : this.vocabulary) {
            for (String a : p.aliases()) {
                String trimmed = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && !phrases.contains(trimmed)) phrases.add(trimmed);
            }
        }
        grammar.clear();
        grammar.addAll(phrases);
        if (model != null) {
            try { rebuildRecognizer(); } catch (Throwable t) {
                VoiceCast.LOGGER.warn("Failed to rebuild Vosk recognizer with grammar", t);
            }
        }
    }

    private void rebuildRecognizer() {
        if (recognizer != null) { try { recognizer.close(); } catch (Throwable ignored) {} }
        try {
            if (options != null && options.grammarOnly() && !grammar.isEmpty()) {
                String json = buildGrammarJson(grammar);
                try {
                    recognizer = new Recognizer(model, 16_000f, json);
                    VoiceCast.LOGGER.debug("Vosk grammar applied: {}", json);
                } catch (Throwable t) {
                    // HCLG ("big") models don't support grammar mode. Fall back to
                    // the full graph and filter results post-hoc.
                    VoiceCast.LOGGER.warn("Vosk model does not support grammar, "
                            + "falling back to full-vocab mode (results will be filtered)", t);
                    recognizer = new Recognizer(model, 16_000f);
                }
            } else {
                recognizer = new Recognizer(model, 16_000f);
            }
            if (recognizer != null) {
                recognizer.setWords(false);
                recognizer.setPartialWords(false);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to construct Vosk recognizer", t);
        }
    }

    private static String buildGrammarJson(List<String> phrases) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < phrases.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(phrases.get(i))).append('"');
        }
        sb.append(",\"[unk]\"]");
        return sb.toString();
    }

    /**
     * Remove Vosk's out-of-vocabulary {@code [unk]} token(s) from recognized
     * text. The grammar includes {@code [unk]} as an OOV escape hatch; when the
     * speech doesn't match a registered word Vosk emits {@code [unk]}, which must
     * never be shown or used for matching. Returns "" when nothing meaningful was
     * recognized.
     */
    private static String clean(String text) {
        if (text == null) return "";
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.equals("[unk]") || p.equals("unk")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    protected synchronized void decode(short[] samples, int offset, int length) {
        if (recognizer == null) return;
        if (utteranceStart == 0) utteranceStart = System.currentTimeMillis();
        short[] buf;
        if (offset == 0 && length == samples.length) {
            buf = samples;
        } else {
            buf = new short[length];
            System.arraycopy(samples, offset, buf, 0, length);
        }
        boolean endpoint = recognizer.acceptWaveForm(buf, length);
        if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
            VoiceCast.LOGGER.info("[Vosk] fed {} samples, endpoint={}", length, endpoint);
        }
        if (endpoint) {
            String json = recognizer.getResult();
            String text = clean(MiniJson.getString(json, "text"));
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                VoiceCast.LOGGER.info("[Vosk] RESULT json={} text='{}'", json, text);
            }
            if (!text.isEmpty()) {
                emit(text, List.of(), 1.0f, utteranceStart);
            }
            utteranceStart = 0;
        } else {
            String partialJson = recognizer.getPartialResult();
            String partial = clean(MiniJson.getString(partialJson, "partial"));
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging && !partial.isEmpty()) {
                VoiceCast.LOGGER.info("[Vosk] partial='{}'", partial);
            }
            if (!partial.isEmpty()) {
                emitPartial(partial, List.of(), 0.6f);
            }
        }
    }

    @Override
    public synchronized void finishUtterance() {
        if (recognizer == null) return;
        try {
            String json = recognizer.getFinalResult();
            String text = clean(MiniJson.getString(json, "text"));
            if (com.theo.voicecast.config.VoiceCastConfig.INSTANCE.verboseLogging) {
                VoiceCast.LOGGER.info("[Vosk] FINAL json={} text='{}'", json, text);
            }
            if (!text.isEmpty()) {
                emit(text, List.of(), 1.0f,
                        utteranceStart == 0 ? System.currentTimeMillis() : utteranceStart);
            }
            recognizer.reset();
        } catch (Throwable t) {
            VoiceCast.LOGGER.warn("finishUtterance failed", t);
        } finally {
            utteranceStart = 0;
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        if (recognizer != null) {
            try { recognizer.close(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        if (model != null && ownsModel) {
            try { model.close(); } catch (Throwable t) { VoiceCast.LOGGER.warn("model close failed", t); }
        }
        model = null;
        sharedModel = null;
    }

    /** Helper for other code to detect whether a model directory looks valid. */
    public static boolean looksLikeModel(File dir) {
        return dir != null && dir.isDirectory() && new File(dir, "conf").isDirectory();
    }
}
