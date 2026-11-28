package ru.governix.mediaframe;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class MediaFrameSmokeTest {
    private static final String TENOR_MP4 =
            "https://media.tenor.com/OTe0sSmilkIAAAPo/la-rams-rampage.mp4";
    private static final String IMGUR_JPEG =
            "https://i.imgur.com/E0UGLyD.jpeg?fb";

    private MediaFrameSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        resolvesTenorVideoFromOpenGraphMetadata();
        resolvesTenorVideoFromEmbeddedJson();
        resolvesImgurImageFromOpenGraphMetadata();

        if ("true".equalsIgnoreCase(System.getenv("MEDIAFRAME_TEST_PROVISIONING"))) {
            managedLinuxToolsCanBeDownloadedVerifiedAndExecuted();
        }

        System.out.println("GovernixMediaFrame smoke tests passed.");
    }

    private static void resolvesTenorVideoFromOpenGraphMetadata() throws IOException {
        String html = "<html><head><meta property=\"og:video:secure_url\" content=\""
                + TENOR_MP4 + "\"></head></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(StandardCharsets.UTF_8),
                URI.create("https://tenor.com/view/sample-gif-4122962656881710658"),
                "video"
        );

        assertEquals(TENOR_MP4, resolved.toString(), "Tenor Open Graph URL");
    }

    private static void resolvesTenorVideoFromEmbeddedJson() throws IOException {
        String html = "<html><script>window.media={src:\"" + TENOR_MP4 + "\"};</script></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(StandardCharsets.UTF_8),
                URI.create("https://tenor.com/view/sample-gif-4122962656881710658"),
                "video"
        );

        assertEquals(TENOR_MP4, resolved.toString(), "Tenor embedded JSON URL");
    }

    private static void resolvesImgurImageFromOpenGraphMetadata() throws IOException {
        String html = "<html><head><meta property=\"og:image\" content=\""
                + IMGUR_JPEG + "\"></head></html>";

        URI resolved = MediaLoader.resolvePageMedia(
                html.getBytes(StandardCharsets.UTF_8),
                URI.create("https://imgur.com/gallery/raturday-KhogDcz"),
                "image"
        );

        assertEquals(IMGUR_JPEG, resolved.toString(), "Imgur Open Graph URL");
    }

    private static void managedLinuxToolsCanBeDownloadedVerifiedAndExecuted() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64")
                || arch.equals("aarch64") || arch.equals("arm64"))) {
            throw new IllegalStateException("Tool smoke test requires supported Linux.");
        }

        Path temporary = Files.createTempDirectory("governix-mediaframe-smoke-");
        try {
            String missing = temporary.resolve("intentionally-missing-tool").toString();

            String ffmpeg = BinaryManager.ensureFfmpeg(missing, temporary);
            String ytDlp = BinaryManager.ensureYtDlp(missing, temporary);
            String deno = BinaryManager.ensureDeno(missing, temporary);

            assertExecutable(ffmpeg, "FFmpeg");
            assertExecutable(ytDlp, "yt-dlp");
            assertExecutable(deno, "Deno");
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

    private static void assertExecutable(String path, String name) {
        if (path == null || path.isBlank() || !Files.isExecutable(Path.of(path))) {
            throw new AssertionError(name + " is not executable after provisioning.");
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}