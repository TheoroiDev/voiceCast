package com.theo.voicecast.audio;

import com.theo.voicecast.model.ModelConfig;
import com.theo.voicecast.model.ModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual end-to-end check with the real GTCRN model + sherpa natives: the
 * denoiser must strongly attenuate stationary noise while leaving quiet
 * passages quiet. Opt-in (downloads the ~523 KB model):
 * {@code gradlew :voicecast-common:test --tests '*DenoiserE2E*' -Dvoicecast.e2eDenoiser=true}
 */
class DenoiserE2ETest {

    @Test
    @EnabledIfSystemProperty(named = "voicecast.e2eDenoiser", matches = "true")
    void realDenoiserCleansNoisySpeech() throws Exception {
        Path gameDir = Files.createTempDirectory("voicecast-e2e-denoiser");
        ModelConfig config = ModelConfig.load(gameDir);
        NoiseSuppression ns = NoiseSuppression.create(gameDir, config, null);
        assertNotNull(ns, "denoiser must initialize against the default catalog");

        // k2-fsa's own noisy-speech sample (16 kHz/16-bit/mono); downloadFile
        // writes into config/voicecast/models/<modelId>/<name>
        new ModelManager(gameDir, config.probe()).downloadFile("e2e", "speech_with_noise.wav",
                java.util.List.of("https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/speech_with_noise.wav"),
                null, (done, total) -> { }, 1);
        Path wav = gameDir.resolve("config/voicecast/models/e2e/speech_with_noise.wav");
        com.k2fsa.sherpa.onnx.WaveReader reader = new com.k2fsa.sherpa.onnx.WaveReader(wav.toAbsolutePath().toString());
        float[] input = reader.getSamples();
        assertTrue(input.length > 16000, "test sample should carry real audio");

        // feed through the streaming denoiser in 200 ms chunks, like MicCapture
        short[] pcm = new short[input.length];
        for (int i = 0; i < input.length; i++) pcm[i] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(input[i] * 32768f)));
        int chunk = 3200;
        for (int off = 0; off < pcm.length; off += chunk) {
            int len = Math.min(chunk, pcm.length - off);
            double pre = 0;
            for (int i = off; i < off + len; i++) pre += (double) pcm[i] * pcm[i];
            ns.process(pcm, off, len);
            double post = 0;
            for (int i = off; i < off + len; i++) post += (double) pcm[i] * pcm[i];
            System.out.printf("chunk off=%d len=%d rmsIn=%.1f rmsOut=%.1f%n",
                    off, len, Math.sqrt(pre / len) * 1, Math.sqrt(post / len));
        }

        double energyIn = 0, energyOut = 0;
        for (int i = 0; i < pcm.length; i++) {
            short raw = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(input[i] * 32768f)));
            energyIn += (double) raw * raw;
            energyOut += (double) pcm[i] * pcm[i];
        }
        double rmsIn = Math.sqrt(energyIn / pcm.length);
        double rmsOut = Math.sqrt(energyOut / pcm.length);
        System.out.printf("noisy speech rms: in=%.1f out=%.1f (%.1f%%)%n", rmsIn, rmsOut, 100 * rmsOut / rmsIn);
        assertTrue(rmsOut < rmsIn, "denoiser must reduce overall level of a noisy sample");
        assertTrue(rmsOut > rmsIn * 0.05, "speech content must survive (not silence everything)");
        ns.release();
    }
}
