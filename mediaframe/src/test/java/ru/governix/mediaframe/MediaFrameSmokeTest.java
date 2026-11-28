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
        validatesMultiFrameDimensionsAndCanvasResolution();

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

    private static void validatesMultiFrameDimensionsAndCanvasResolution() throws IOException {
        MediaFrameSize landscape = MediaFrameSize.parse("5x3");
        assertEquals("5x3", landscape.label(), "landscape frame grid");
        assertEquals(640, landscape.pixelWidth(), "5x3 canvas width");
        assertEquals(384, landscape.pixelHeight(), "5x3 canvas height");

        MediaFrameSize portrait = MediaFrameSize.parse("3x4");
        assertEquals(384, portrait.pixelWidth(), "3x4 canvas width");
        assertEquals(512, portrait.pixelHeight(), "3x4 canvas height");

        var fitted = MediaLoader.fitToCanvas(
                new java.awt.image.BufferedImage(1920, 1080, java.awt.image.BufferedImage.TYPE_INT_RGB),
                portrait.pixelWidth(),
                portrait.pixelHeight()
        );
        assertEquals(384, fitted.getWidth(), "fitted canvas width");
        assertEquals(512, fitted.getHeight(), "fitted canvas height");

        MediaFrameSize full = MediaFrameSize.parse("5x5");
        assertEquals(640, full.pixelWidth(), "5x5 canvas width");
        assertEquals(640, full.pixelHeight(), "5x5 canvas height");

        MediaFrameSize sixteen = MediaFrameSize.parse("4x4");
        assertEquals(16, sixteen.width() * sixteen.height(), "4x4 frame count");

        try {
            MediaFrameSize.parse("6x1");
            throw new AssertionError("A screen wider than five frames must be rejected.");
        } catch (IllegalArgumentException expected) {
            // No side may exceed five frames.
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
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

            if ("true".equalsIgnoreCase(System.getenv("MEDIAFRAME_TEST_LIVE_URLS"))) {
                liveUserMediaUrlsLoad(temporary, ffmpeg, ytDlp, deno);
            }
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

    private static void liveUserMediaUrlsLoad(
            Path pluginFolder,
            String ffmpeg,
            String ytDlp,
            String deno
    ) throws IOException {
        long maxBytes = 25L * 1024 * 1024;

        var imgurFrames = MediaLoader.load(
                "https://imgur.com/gallery/raturday-KhogDcz",
                "image",
                maxBytes,
                ffmpeg,
                ytDlp,
                deno,
                "",
                pluginFolder,
                2,
                5,
                12,
                120,
        1,
        1
        );
        if (imgurFrames.isEmpty()) {
            throw new AssertionError("Imgur gallery URL did not produce an image frame.");
        }
        System.out.println("Live Imgur gallery URL: OK (" + imgurFrames.size() + " frame)");

        var tenorFrames = MediaLoader.load(
                "https://tenor.com/ru/view/la-rams-rampage-scuba-rams-mascot-gif-4122962656881710658",
                "video",
                maxBytes,
                ffmpeg,
                ytDlp,
                deno,
                "",
                pluginFolder,
                6,
                2,
                12,
                120,
        1,
        1
        );
        if (tenorFrames.isEmpty()) {
            throw new AssertionError("Tenor page URL did not produce video frames.");
        }
        System.out.println("Live Tenor page URL: OK (" + tenorFrames.size() + " video frames)");

        try {
            var youtubeFrames = MediaLoader.load(
                    "https://www.youtube.com/watch?v=4vctkTaej18",
                    "video",
                    maxBytes,
                    ffmpeg,
                    ytDlp,
                    deno,
                    "",
                    pluginFolder,
                    2,
                    5,
                    12,
                    120,
            1,
            1
            );
            if (youtubeFrames.isEmpty()) {
                throw new AssertionError("YouTube URL did not produce video frames.");
            }
            System.out.println("Live YouTube URL: OK (" + youtubeFrames.size() + " video frames)");
        } catch (IOException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("YouTube блокирует запросы с IP сервера")) {
                System.out.println("Live YouTube URL: blocked by YouTube anti-bot on the CI IP; optional cookies.txt fallback is available.");
                return;
            }
            throw exception;
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