package com.theo.voicecast.engine;

import com.theo.voicecast.api.Pronunciation;
import com.theo.voicecast.api.SpeechOptions;
import com.theo.voicecast.api.engine.EngineSpec;
import com.theo.voicecast.model.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual end-to-end check against the real bilingual zipformer model in the
 * workspace {@code resources/models/}: starts the streaming recognizer with a
 * hotword vocabulary (the {@code cjkchar+bpe} + hotwords combination that
 * needs the model's bpe.vocab — the missing {@code bpeVocab} config used to
 * fail native creation outright), feeds one second of silence through the
 * decode loop and shuts down. Opt-in (needs the natives + model on disk):
 * {@code gradlew :voicecast-common:test --tests '*SherpaRecognizerE2E*' -Dvoicecast.e2eRecognizer=true}
 */
class SherpaRecognizerE2ETest {

    @Test
    @EnabledIfSystemProperty(named = "voicecast.e2eRecognizer", matches = "true")
    void streamingRecognizerStartsWithHotwordsOnRealModel() throws Exception {
        Path modelDir = findWorkspaceModel();
        assertTrue(modelDir != null,
                "workspace resources/models/sherpa-zipformer-bilingual-zh-en-int8 not found "
                + "(run the download e2e or pre-download the model first)");

        ModelConfig config = ModelConfig.load(Files.createTempDirectory("voicecast-e2e-cfg"));
        EngineSpec spec = new EngineSpec(
                config.familyFor("sherpa-zipformer-bilingual-zh-en-int8"),
                "sherpa-zipformer-bilingual-zh-en-int8",
                modelDir,
                config.languagesFor("sherpa-zipformer-bilingual-zh-en-int8"),
                config.optionsFor("sherpa-zipformer-bilingual-zh-en-int8"));
        assertTrue("cjkchar+bpe".equals(spec.option("modeling_unit", "")),
                "catalog must keep the cjkchar+bpe modeling unit for this coverage");

        SherpaStreamingRecognizer recognizer = new SherpaStreamingRecognizer(spec);
        recognizer.setVocabulary(List.of(
                new Pronunciation("e2e-spell", List.of(), List.of("爆裂火球", "explosion"))));

        recognizer.start(new SpeechOptions(true, 0.65f, modelDir.toString(), false));
        assertTrue(recognizer.isActive(), "recognizer must be active after start");

        recognizer.acceptPcm(new short[16000], 0, 16000); // 1 s of silence
        recognizer.finishUtterance();
        recognizer.stop();
    }

    /** Walk up from the working dir to locate the workspace model copy. */
    private static Path findWorkspaceModel() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path cand = p.resolve("resources/models/sherpa-zipformer-bilingual-zh-en-int8");
            if (Files.isRegularFile(cand.resolve("tokens.txt"))) return cand;
        }
        return null;
    }
}
