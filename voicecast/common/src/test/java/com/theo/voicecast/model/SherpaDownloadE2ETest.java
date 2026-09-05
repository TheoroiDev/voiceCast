package com.theo.voicecast.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual end-to-end check: really downloads the default bilingual sherpa
 * archive (≈230 MB, GitHub release) and verifies the extraction pipeline
 * lands tokens.txt + .onnx at the model root. Opt-in because it needs the
 * network (proxy via HTTPS_PROXY env or -Dhttps.proxyHost):
 * {@code gradlew :voicecast-common:test --tests '*SherpaDownloadE2E*' -Dvoicecast.e2eDownload=true}
 */
class SherpaDownloadE2ETest {

    @Test
    @EnabledIfSystemProperty(named = "voicecast.e2eDownload", matches = "true")
    void downloadsAndExtractsRealBilingualModel() throws Exception {
        Path gameDir = Files.createTempDirectory("voicecast-e2e");
        try {
            ModelConfig config = ModelConfig.load(gameDir);
            ModelConfig.ModelEntry entry = config.model("sherpa-zipformer-bilingual-zh-en-int8");
            assertTrue(entry != null, "default catalog must carry the bilingual streaming model");

            Path dir = SherpaModel.resolveOrDownload(gameDir, config, entry,
                    (done, total) -> System.out.printf("download: %.1f MB / %s%n",
                            done / 1048576.0, total > 0 ? (total / 1048576.0 + " MB") : "?"));
            assertTrue(SherpaModel.isValidModelDir(dir), "extracted model must validate: " + dir);
            System.out.println("model ready at " + dir);
        } finally {
            // keep the downloaded model for inspection on failure; temp dirs
            // are cleaned by the OS/build cache, not worth deleting ~230 MB
            // of re-downloadable data eagerly
        }
    }
}
