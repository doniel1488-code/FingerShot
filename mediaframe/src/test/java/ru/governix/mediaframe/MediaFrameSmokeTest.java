package ru.governix.mediaframe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class MediaFrameSmokeTest {
    private static final String TENOR_MP4 =
            "https://media.tenor.com/OTe0sSmilkIAAAPo/la-rams-rampage.mp4";
    private static final String IMGUR_JPEG =
            "https://i.imgur.com/E0UGLyD.jpeg?fb";

    @Test
    void resolvesTenorVideoFromOpenGraphMetadata() throws IOException {
        String html = "<html><head><meta property=\"og:video:secure_url\" content=\""
                + TENOR_MP4 + "\"></head></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                URI.create("https://tenor.com/view/sample-gif-4122962656881710658"),
                "video"
        );

        assertEquals(TENOR_MP4, resolved.toString());
    }

    @Test
    void resolvesTenorVideoFromEmbeddedJson() throws IOException {
        String html = "<html><script>window.media={src:\"" + TENOR_MP4 + "\"};</script></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                URI.create("https://tenor.com/view/sample-gif-4122962656881710658"),
                "video"
        );

        assertEquals(TENOR_MP4, resolved.toString());
    }

    @Test
    void resolvesImgurImageFromOpenGraphMetadata() throws IOException {
        String html = "<html><head><meta property=\"og:image\" content=\""
                + IMGUR_JPEG + "\"></head></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                URI.create("https://imgur.com/gallery/raturday-KhogDcz"),
                "image"
        );

        assertEquals(IMGUR_JPEG, resolved.toString());
    }

    @Test
    void managedLinuxToolsCanBeDownloadedVerifiedAndExecuted() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("MEDIAFRAME_TEST_PROVISIONING")),
                "Managed-tool smoke test is enabled only on the dedicated GitHub Actions build.");

        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        assumeTrue(os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64")
                || arch.equals("aarch64") || arch.equals("arm64")),
                "Automatic binary provisioning smoke test requires supported Linux.");

        Path temporary = Files.createTempDirectory("governix-mediaframe-smoke-");
        try {
            String missing = temporary.resolve("intentionally-missing-tool").toString();

            String ffmpeg = BinaryManager.ensureFfmpeg(missing, temporary);
            String ytDlp = BinaryManager.ensureYtDlp(missing, temporary);
            String deno = BinaryManager.ensureDeno(missing, temporary);

            assertTrue(Files.isExecutable(Path.of(ffmpeg)), "FFmpeg should be installed and executable");
            assertTrue(Files.isExecutable(Path.of(ytDlp)), "yt-dlp should be installed and executable");
            assertTrue(Files.isExecutable(Path.of(deno)), "Deno should be installed and executable");
            assertFalse(ffmpeg.isBlank());
            assertFalse(ytDlp.isBlank());
            assertFalse(deno.isBlank());
        } finally {
            try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}