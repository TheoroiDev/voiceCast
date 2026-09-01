package com.theo.voicecast.config;

/**
 * Client-side constants for VoiceCast (HUD, PTT endpoint, debug toggles).
 * The only file-backed client setting is {@code [client] engine} in
 * {@code config/voicecast/voicecast.toml} (see {@link ClientVoiceConfig});
 * everything here is a compile-time constant or system property.
 */
public final class VoiceCastConfig {
    public boolean saveDebugWav = false;

    /**
     * Verbose pipeline logging. Can be toggled at runtime via
     * {@code /voicecast verbose} (once that command exists) or by setting
     * the system property {@code voicecast.verbose=true} at JVM start.
     */
    public boolean verboseLogging = Boolean.getBoolean("voicecast.verbose");
    /** When verboseLogging, log every Nth PCM chunk to avoid flooding. */
    public int logEveryNChunks = 25;

    /**
     * In PTT mode, automatically finish/flush the utterance after the speaker
     * pauses for a while (without waiting for key release). This lets users
     * chain incantations while holding the key.
     */
    public boolean silenceEndpoint = true;
    /** Silence duration (ms) that ends an utterance in PTT mode. */
    public long silenceEndpointMs = 700L;
    /** RMS below which audio is treated as silence for the endpoint timer. */
    public float silenceEndpointRms = 0.012f;
    /** Utterances shorter than this are treated as noise and not flushed. */
    public long minUtteranceMs = 250L;

    /** Whether to show the recognized-text readout under the waveform. */
    public boolean transcriptHud = true;
    /** Fade out the final result after this many milliseconds. */
    public long transcriptFadeMs = 3500L;

    public static final VoiceCastConfig INSTANCE = new VoiceCastConfig();
    private VoiceCastConfig() {}
}
