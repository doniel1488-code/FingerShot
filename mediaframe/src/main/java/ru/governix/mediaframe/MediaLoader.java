package ru.governix.mediaframe;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MediaLoader {
    private static final int MAP_SIZE = 128;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
            "(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
    );
    private static final Pattern EMBEDDED_MEDIA_URL = Pattern.compile(
            "(?i)https?://[^\\s\\\"'<>\\\\]+?\\.(?:mp4|webm|gif|png|jpe?g)(?:\\?[^\\s\\\"'<>\\\\]*)?"
    );
    private static final Pattern TENOR_ID = Pattern.compile("(?i)-gif-(\\d+)(?:$|[/?#])");
    private static final Pattern JSON_URL = Pattern.compile(
            "(?i)https?://[^\\s\\\"'<>\\\\]+?\\.(?:mp4|webm|gif|png|jpe?g)(?:\\?[^\\s\\\"'<>\\\\]*)?"
    );

    private MediaLoader() {
    }

    static List<BufferedImage> load(
            String rawUrl,
            String forcedType,
            long maxBytes,
            String ffmpegPath,
            String ytDlpPath,
            String denoPath,
            Path pluginFolder,
            int videoFps,
            int maxVideoSeconds,
            int maxVideoFrames,
            int maxImageFrames
    ) throws IOException {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Некорректный URL.", exception);
        }

        // YouTube does not expose a normal direct MP4 URL on its watch page.
        // yt-dlp downloads only the configured first segment; FFmpeg then turns it into map frames.
        if (isYoutube(uri)) {
            String actualFfmpeg = BinaryManager.ensureFfmpeg(ffmpegPath, pluginFolder);
            String actualYtDlp = BinaryManager.ensureYtDlp(ytDlpPath, pluginFolder);
            String actualDeno = BinaryManager.ensureDeno(denoPath, pluginFolder);
            byte[] videoBytes = downloadYoutube(uri, maxBytes, actualFfmpeg,
                    actualYtDlp, actualDeno, maxVideoSeconds);
            return decodeVideo(videoBytes, actualFfmpeg, videoFps, maxVideoSeconds, maxVideoFrames);
        }

        Download download = download(uri, maxBytes);
        URI mediaUri = download.finalUri();

        // Imgur galleries and Tenor pages are HTML pages, not media files.
        // Resolve their Open Graph / Twitter media metadata to the actual asset URL.
        if (isHtml(download)) {
            mediaUri = resolvePageMedia(download.bytes(), mediaUri, forcedType);
            download = download(mediaUri, maxBytes);
            mediaUri = download.finalUri();
        }

        if (isHtml(download)) {
            throw new IOException("Ссылка ведёт на веб-страницу, а не на прямой медиафайл. Не удалось найти og:image или og:video.");
        }

        boolean video = "video".equalsIgnoreCase(forcedType)
                || ("auto".equalsIgnoreCase(forcedType) && isVideo(download.contentType(), mediaUri));

        if (video) {
            String actualFfmpeg = BinaryManager.ensureFfmpeg(ffmpegPath, pluginFolder);
            return decodeVideo(download.bytes(), actualFfmpeg, videoFps,
                    maxVideoSeconds, maxVideoFrames);
        }

        return decodeImage(download.bytes(), maxImageFrames);
    }

    private static boolean isYoutube(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("youtube.com")
                || normalized.endsWith(".youtube.com")
                || normalized.equals("youtu.be")
                || normalized.equals("youtube-nocookie.com")
                || normalized.endsWith(".youtube-nocookie.com");
    }

    private static byte[] downloadYoutube(
            URI uri,
            long maxBytes,
            String ffmpegPath,
            String ytDlpPath,
            String denoPath,
            int maxSeconds
    ) throws IOException {
        validateYoutubeUri(uri);
        Path directory = Files.createTempDirectory("governix-youtube-");
        Path errorLog = directory.resolve("yt-dlp-error.log");
        String outputTemplate = directory.resolve("youtube.%(ext)s").toString();

        List<String> command = new ArrayList<>(List.of(
                ytDlpPath,
                "--ignore-config",
                "--no-playlist",
                "--no-warnings",
                "--no-progress",
                "--quiet",
                "--socket-timeout", "20",
                "--retries", "3",
                "--fragment-retries", "3",
                "--js-runtimes", "deno:" + denoPath,
                "--remote-components", "ejs:github",
                "--ffmpeg-location", ffmpegPath,
                "--download-sections", "*0-" + Math.max(1, maxSeconds),
                "--force-keyframes-at-cuts",
                "--format", "bv*[height<=480]+ba/b[height<=480]/bv*+ba/b",
                "--merge-output-format", "mkv",
                "--output", outputTemplate,
                uri.toString()
        ));

        try {
            Process process;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(errorLog.toFile())
                        .start();
            } catch (IOException exception) {
                throw new IOException("Не удалось запустить автоматически установленный yt-dlp: " + exception.getMessage(), exception);
            }

            boolean finished;
            try {
                finished = process.waitFor(240, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Загрузка YouTube была прервана.", exception);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("yt-dlp не успел получить видео за 240 секунд.");
            }

            List<Path> outputs;
            try (var files = Files.list(directory)) {
                outputs = files
                        .filter(path -> path.getFileName().toString().matches("youtube\\.(mp4|mkv|webm|mov|avi)"))
                        .toList();
            }

            if (process.exitValue() != 0 || outputs.isEmpty()) {
                String details = Files.exists(errorLog) ? Files.readString(errorLog) : "";
                if (details.length() > 500) {
                    details = details.substring(details.length() - 500);
                }
                throw new IOException("Не удалось получить видео с YouTube. Проверь доступность ролика и установку yt-dlp/FFmpeg. " + details);
            }

            Path videoFile = outputs.get(0);
            long size = Files.size(videoFile);
            if (size <= 0 || size > maxBytes) {
                throw new IOException("Фрагмент YouTube больше лимита загрузки (" + (maxBytes / 1024 / 1024) + " МБ).");
            }
            return Files.readAllBytes(videoFile);
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void validateYoutubeUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !isYoutube(uri)) {
            throw new IOException("Для YouTube разрешены только HTTPS-ссылки с youtube.com или youtu.be.");
        }
    }

    static URI resolvePageMedia(byte[] htmlBytes, URI pageUri, String forcedType) throws IOException {
        String html = new String(htmlBytes, StandardCharsets.UTF_8);
        Map<String, List<String>> metadata = new HashMap<>();

        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            Matcher attributes = HTML_ATTRIBUTE.matcher(tags.group());
            Map<String, String> values = new HashMap<>();
            while (attributes.find()) {
                String key = attributes.group(1).toLowerCase(Locale.ROOT);
                String value = attributes.group(2) != null ? attributes.group(2)
                        : attributes.group(3) != null ? attributes.group(3)
                        : attributes.group(4);
                values.put(key, decodeHtmlEntities(value));
            }

            String key = values.getOrDefault("property", values.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
            String value = values.get("content");
            if (!key.isBlank() && value != null && !value.isBlank()) {
                metadata.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value.trim());
            }
        }

        List<URI> videoUrls = collectMetadataUrls(metadata, pageUri, List.of(
                "og:video:secure_url",
                "og:video",
                "ogvideo",
                "twitter:player:stream",
                "contenturl"
        ));
        List<URI> imageUrls = collectMetadataUrls(metadata, pageUri, List.of(
                "og:image",
                "og:image:url",
                "ogimage",
                "twitter:image",
                "twitter:image:src"
        ));
        // Imgur's og:image often has a Facebook preview query (?fb) that redirects
        // to an HTML landing page. Prefer its twitter:image CDN thumbnail instead.
        if (pageUri.getHost() != null
                && pageUri.getHost().toLowerCase(Locale.ROOT).endsWith("imgur.com")) {
            List<URI> twitterImages = collectMetadataUrls(metadata, pageUri,
                    List.of("twitter:image", "twitter:image:src"));
            if (!twitterImages.isEmpty()) {
                imageUrls = twitterImages;
            }
        }

        // Some CDN pages expose their real assets only inside embedded JSON,
        // not as ordinary HTML meta tags. Normalize escaped URLs and scan them too.
        String searchableHtml = html
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u003A", ":")
                .replace("&amp;", "&")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/");
        Matcher embedded = EMBEDDED_MEDIA_URL.matcher(searchableHtml);
        while (embedded.find()) {
            try {
                URI candidate = URI.create(embedded.group());
                String host = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
                if (host.endsWith("tenor.com") || host.endsWith("imgur.com")) {
                    if (isVideoAsset(candidate)) {
                        if (!videoUrls.contains(candidate)) videoUrls.add(candidate);
                    } else if (isImageAsset(candidate)) {
                        if (!imageUrls.contains(candidate)) imageUrls.add(candidate);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Tenor and Imgur may publish their CDN URL as og:url/contentUrl rather
        // than as a conventional og:video/og:image field.
        for (String key : List.of("og:url", "ogurl", "url")) {
            for (String raw : metadata.getOrDefault(key, List.of())) {
                try {
                    URI candidate = pageUri.resolve(decodeHtmlEntities(raw));
                    String host = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
                    if (host.endsWith("tenor.com") || host.endsWith("imgur.com")) {
                        if (isVideoAsset(candidate) && !videoUrls.contains(candidate)) videoUrls.add(candidate);
                        if (isImageAsset(candidate) && !imageUrls.contains(candidate)) imageUrls.add(candidate);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Tenor can return a shell page without metadata to non-browser clients.
        // Its public GIF lookup endpoint is a fallback when a share page omits asset tags.
        if (videoUrls.isEmpty() && imageUrls.isEmpty()
                && pageUri.getHost() != null
                && pageUri.getHost().toLowerCase(Locale.ROOT).endsWith("tenor.com")) {
            resolveTenorApi(pageUri, videoUrls, imageUrls);
        }

        // Imgur's oEmbed endpoint is a fallback for gallery pages whose HTML hides OG metadata.
        if (videoUrls.isEmpty() && imageUrls.isEmpty()
                && pageUri.getHost() != null
                && pageUri.getHost().toLowerCase(Locale.ROOT).endsWith("imgur.com")) {
            resolveImgurOembed(pageUri, videoUrls, imageUrls);
        }

        boolean preferVideo = "video".equalsIgnoreCase(forcedType)
                || ("auto".equalsIgnoreCase(forcedType) && !videoUrls.isEmpty());
        List<URI> candidates = preferVideo ? videoUrls : imageUrls;
        if (candidates.isEmpty() && "auto".equalsIgnoreCase(forcedType)) {
            candidates = videoUrls;
        }

        for (URI candidate : candidates) {
            String scheme = candidate.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return candidate;
            }
        }

        if ("video".equalsIgnoreCase(forcedType)) {
            throw new IOException("На этой странице не найдена прямая ссылка og:video.");
        }
        throw new IOException("На этой странице не найдена прямая ссылка на изображение (og:image).");
    }

    private static List<URI> collectMetadataUrls(
            Map<String, List<String>> metadata,
            URI pageUri,
            List<String> keys
    ) {
        List<URI> result = new ArrayList<>();
        for (String key : keys) {
            for (String raw : metadata.getOrDefault(key, List.of())) {
                // A content-type meta value is not a URL.
                if (raw.startsWith("video/") || raw.startsWith("image/")) {
                    continue;
                }
                try {
                    URI candidate = pageUri.resolve(raw.trim());
                    if (!result.contains(candidate)) {
                        result.add(candidate);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Prefer playable MP4 over WebM/GIF when a page exposes several video assets.
        result.sort(Comparator.comparingInt(uri -> {
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".mp4")) return 0;
            if (path.endsWith(".webm")) return 1;
            if (path.endsWith(".gif")) return 2;
            return 3;
        }));
        return result;
    }

    private static void resolveTenorApi(
            URI pageUri,
            List<URI> videoUrls,
            List<URI> imageUrls
    ) {
        Matcher idMatcher = TENOR_ID.matcher(pageUri.getPath() == null ? "" : pageUri.getPath());
        if (!idMatcher.find()) {
            return;
        }

        String endpoint = "https://g.tenor.com/v1/gifs?ids=" + idMatcher.group(1)
                + "&key=LIVDSRZULELA&media_filter=basic";
        try {
            Download response = download(URI.create(endpoint), 2L * 1024 * 1024);
            String json = new String(response.bytes(), StandardCharsets.UTF_8)
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("&amp;", "&");
            Matcher urls = JSON_URL.matcher(json);
            while (urls.find()) {
                URI candidate = URI.create(urls.group());
                if (isVideoAsset(candidate) && !videoUrls.contains(candidate)) {
                    videoUrls.add(candidate);
                } else if (isImageAsset(candidate) && !imageUrls.contains(candidate)) {
                    imageUrls.add(candidate);
                }
            }
        } catch (Exception ignored) {
            // Keep the original, more useful "no media URL" error if the fallback is unavailable.
        }
    }

    private static void resolveImgurOembed(
            URI pageUri,
            List<URI> videoUrls,
            List<URI> imageUrls
    ) {
        String encoded = java.net.URLEncoder.encode(pageUri.toString(), StandardCharsets.UTF_8);
        String endpoint = "https://api.imgur.com/oembed.json?url=" + encoded;
        try {
            Download response = download(URI.create(endpoint), 2L * 1024 * 1024);
            String json = new String(response.bytes(), StandardCharsets.UTF_8)
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("&amp;", "&");
            Matcher urls = JSON_URL.matcher(json);
            while (urls.find()) {
                URI candidate = URI.create(urls.group());
                String host = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
                if (host.endsWith("imgur.com")) {
                    if (isVideoAsset(candidate) && !videoUrls.contains(candidate)) {
                        videoUrls.add(candidate);
                    } else if (isImageAsset(candidate) && !imageUrls.contains(candidate)) {
                        imageUrls.add(candidate);
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep the original, more useful "no media URL" error if the fallback is unavailable.
        }
    }

    private static boolean isVideoAsset(URI uri) {
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m4v")
                || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".avi");
    }

    private static boolean isImageAsset(URI uri) {
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private static String decodeHtmlEntities(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u003A", ":");
    }

    private static boolean isHtml(Download download) {
        if (download.contentType().contains("text/html")
                || download.contentType().contains("application/xhtml")) {
            return true;
        }
        String prefix = new String(download.bytes(), 0, Math.min(download.bytes().length, 512),
                StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        return prefix.startsWith("<!doctype html")
                || prefix.startsWith("<html")
                || prefix.startsWith("<head")
                || prefix.startsWith("<meta");
    }

    private static Download download(URI initialUri, long maxBytes) throws IOException {
        URI current = initialUri;

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validatePublicHttpUri(current);

            URLConnection rawConnection = current.toURL().openConnection();
            if (!(rawConnection instanceof HttpURLConnection connection)) {
                throw new IOException("Поддерживаются только HTTP и HTTPS URL.");
            }

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int) Duration.ofSeconds(8).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
            boolean mediaRequest = isImageAsset(current) || isVideoAsset(current);
            if (mediaRequest) {
                connection.setRequestProperty("Accept",
                        "image/avif,image/webp,image/apng,image/*,video/*,application/octet-stream;q=0.8,*/*;q=0.5");
                connection.setRequestProperty("Sec-Fetch-Dest", isVideoAsset(current) ? "video" : "image");
            } else {
                connection.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,image/avif,image/webp,image/*,video/*,application/octet-stream;q=0.8");
            }
            if (current.getHost() != null
                    && current.getHost().toLowerCase(Locale.ROOT).endsWith("imgur.com")) {
                connection.setRequestProperty("Referer", "https://imgur.com/");
            }
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new IOException("Слишком много перенаправлений или отсутствует Location.");
                    }

                    URI next = current.resolve(location);
                    if ("https".equalsIgnoreCase(current.getScheme())
                            && "http".equalsIgnoreCase(next.getScheme())) {
                        throw new IOException("Перенаправление с HTTPS на HTTP запрещено.");
                    }
                    current = next;
                    continue;
                }

                if (status < 200 || status >= 300) {
                    throw new IOException("Сайт вернул HTTP " + status + ".");
                }

                long contentLength = connection.getContentLengthLong();
                if (contentLength > maxBytes) {
                    throw new IOException("Файл больше разрешённого лимита загрузки.");
                }

                String contentType = connection.getContentType();
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            throw new IOException("Файл больше разрешённого лимита загрузки.");
                        }
                        output.write(buffer, 0, read);
                    }
                    if (total == 0) {
                        throw new IOException("Сайт вернул пустой файл.");
                    }
                    return new Download(output.toByteArray(),
                            contentType == null ? "" : contentType.toLowerCase(Locale.ROOT), current);
                }
            } finally {
                connection.disconnect();
            }
        }

        throw new IOException("Не удалось загрузить файл.");
    }

    private static void validatePublicHttpUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Разрешены только полные HTTP/HTTPS URL.");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("URL с логином или паролем не поддерживаются.");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost")
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local")
                || normalizedHost.endsWith(".internal")) {
            throw new IOException("Локальные адреса запрещены.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception exception) {
            throw new IOException("Не удалось определить IP-адрес URL.", exception);
        }

        if (addresses.length == 0) {
            throw new IOException("У URL нет доступного IP-адреса.");
        }
        for (InetAddress address : addresses) {
            if (isPrivateOrReserved(address)) {
                throw new IOException("URL указывает на локальную или закрытую сеть.");
            }
        }
    }

    private static boolean isPrivateOrReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xFF;
            int b = bytes[1] & 0xFF;
            return (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && b == 0);
        }

        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return (first & 0xFE) == 0xFC
                    || (first == 0xFE && (second & 0xC0) == 0x80);
        }
        return false;
    }

    private static boolean isVideo(String contentType, URI uri) {
        if (contentType.startsWith("video/")) {
            return true;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".m4v") || lower.endsWith(".mov")
                || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".gifv");
    }

    private static List<BufferedImage> decodeImage(byte[] bytes, int maxFrames) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Не удалось прочитать изображение.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Формат не поддерживается. Используй PNG, JPG, GIF или WebP через поддерживаемый декодер.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height);

                int frameCount = 1;
                if ("gif".equalsIgnoreCase(reader.getFormatName())) {
                    try {
                        frameCount = Math.max(1, reader.getNumImages(true));
                    } catch (IOException | UnsupportedOperationException ignored) {
                        frameCount = 1;
                    }
                }
                frameCount = Math.min(frameCount, Math.max(1, maxFrames));

                List<BufferedImage> frames = new ArrayList<>(frameCount);
                for (int index = 0; index < frameCount; index++) {
                    BufferedImage decoded = reader.read(index);
                    if (decoded == null) {
                        break;
                    }
                    validateImageDimensions(decoded.getWidth(), decoded.getHeight());
                    frames.add(fitToMap(decoded));
                }

                if (frames.isEmpty()) {
                    throw new IOException("Изображение не содержит кадров.");
                }
                return frames;
            } finally {
                reader.dispose();
            }
        }
    }

    private static List<BufferedImage> decodeVideo(
            byte[] bytes,
            String ffmpegPath,
            int fps,
            int maxSeconds,
            int maxFrames
    ) throws IOException {
        Path directory = Files.createTempDirectory("governix-mediaframe-");
        try {
            Path input = directory.resolve("source-video");
            Path outputPattern = directory.resolve("frame-%04d.png");
            Path errorLog = directory.resolve("ffmpeg-error.log");
            Files.write(input, bytes);

            List<String> command = List.of(
                    ffmpegPath,
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", input.toString(),
                    "-t", Integer.toString(Math.max(1, maxSeconds)),
                    "-vf", "fps=" + Math.max(1, fps)
                            + ",scale=128:128:force_original_aspect_ratio=decrease:flags=lanczos"
                            + ",pad=128:128:(ow-iw)/2:(oh-ih)/2:color=black",
                    "-frames:v", Integer.toString(Math.max(1, maxFrames)),
                    "-threads", "1",
                    outputPattern.toString()
            );

            Process process;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(errorLog.toFile())
                        .start();
            } catch (IOException exception) {
                throw new IOException("Не найден FFmpeg. Установи FFmpeg на сервер и проверь ffmpeg-path в config.yml.", exception);
            }

            boolean finished;
            try {
                finished = process.waitFor(75, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Обработка видео была прервана.", exception);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("FFmpeg не успел обработать видео за 75 секунд.");
            }
            if (process.exitValue() != 0) {
                String details = Files.exists(errorLog) ? Files.readString(errorLog) : "";
                if (details.length() > 400) {
                    details = details.substring(details.length() - 400);
                }
                throw new IOException("FFmpeg не смог обработать видео. " + details);
            }

            List<Path> frameFiles;
            try (var files = Files.list(directory)) {
                frameFiles = files
                        .filter(path -> path.getFileName().toString().matches("frame-[0-9]{4}\\.png"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(Math.max(1, maxFrames))
                        .toList();
            }

            if (frameFiles.isEmpty()) {
                throw new IOException("В видео не удалось найти кадры. Проверь прямую ссылку на MP4/WebM.");
            }

            List<BufferedImage> frames = new ArrayList<>(frameFiles.size());
            for (Path frameFile : frameFiles) {
                BufferedImage image = ImageIO.read(frameFile.toFile());
                if (image != null) {
                    frames.add(fitToMap(image));
                }
            }
            if (frames.isEmpty()) {
                throw new IOException("FFmpeg не создал читаемые изображения кадров.");
            }
            return frames;
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void validateImageDimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192
                || (long) width * height > 40_000_000L) {
            throw new IOException("Размер изображения слишком большой.");
        }
    }

    private static BufferedImage fitToMap(BufferedImage source) {
        BufferedImage result = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            double scale = Math.min((double) MAP_SIZE / source.getWidth(),
                    (double) MAP_SIZE / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            int x = (MAP_SIZE - width) / 2;
            int y = (MAP_SIZE - height) / 2;
            graphics.drawImage(source, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record Download(byte[] bytes, String contentType, URI finalUri) {
    }
}