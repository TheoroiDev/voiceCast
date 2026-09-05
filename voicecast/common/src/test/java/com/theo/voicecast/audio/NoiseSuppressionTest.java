package com.theo.voicecast.audio;

import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Noise suppression without natives in the test JVM: catalog wiring
 * (denoiser model present) and passthrough semantics for a denoiser-less
 * instance. The real-native path is covered by the opt-in DenoiserE2E test.
 */
class NoiseSuppressionTest {

    @TempDir
    Path runDir;

    @Test
    void defaultCatalogCarriesDenoiserEntry() {
        ModelConfig cfg = ModelConfig.load(runDir);
        assertNotNull(cfg.denoiserModel(), "default catalog carries the gtcrn entry");
    }

    @Test
    void nullDenoiserIsPurePassthrough() {
        NoiseSuppression ns = new NoiseSuppression(null);
        short[] pcm = new short[1600];
        Arrays.fill(pcm, (short) 1234);
        ns.process(pcm, 0, pcm.length);
        for (short v : pcm) {
            assertEquals((short) 1234, v, "a denoiser-less instance must pass audio through unprocessed");
        }
        ns.reset();
        ns.release();
    }
}
