package com.theo.voicecast.engine;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.SpeechRecognizer;

import java.util.Collection;
import java.util.List;

/**
 * Fallback recognizer used when no engine is available (missing model/native
 * libs). Reports READY and accepts PCM, but never emits results - useful for
 * builds that need to run without shipping the full ASR stack.
 */
public final class NoopRecognizer implements SpeechRecognizer {
    private boolean active;

    @Override public String id() { return "noop"; }
    @Override public String displayName() { return "No-op (speech disabled)"; }

    @Override
    public void start(SpeechOptions options) { active = true; }
    @Override
    public void stop() { active = false; }
    @Override
    public boolean isActive() { return active; }
    @Override
    public void setVocabulary(Collection<Pronunciation> vocabulary) {}
    @Override
    public void acceptPcm(short[] samples, int offset, int length) {}
}
