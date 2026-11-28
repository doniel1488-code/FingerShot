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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class MediaLoader {
    private static final int MAP_SIZE = 128;
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9.]+$");

    private MediaLoader() {
    }

    static List<BufferedImage> load(
            String rawUrl,
            String forcedType,
            long maxBytes,
            String ffmpegPath,
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

        Download download = download(uri, maxBytes);
        boolean video = "video".equalsIgnoreCase(forcedType)
                || ("auto".equalsIgnoreCase(forcedType) && isVideo(download.contentType(), uri));

        if ("image".equalsIgnoreCase(forcedType)) {
            video = false;
        }

        if (video) {
            return decodeVideo(download.bytes(), ffmpegPath, videoFps,
                    maxVideoSeconds, maxVideoFrames);
        }

        return decodeImage(download.bytes(), maxImageFrames);
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
            connection.setRequestProperty("User-Agent", "GovernixMediaFrame/1.0");
            connection.setRequestProperty("Accept", "image/*,video/*,application/octet-stream;q=0.8");

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
                            contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
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
                || lower.endsWith(".mkv") || lower.endsWith(".avi");
    }

    private static List<BufferedImage> decodeImage(byte[] bytes, int maxFrames) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Не удалось прочитать изображение.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Формат не поддерживается. Используй PNG, JPG или GIF.");
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
                String details = Files.exists(errorLog)
                        ? Files.readString(errorLog) : "";
                if (details.length() > 400) {
                    details = details.substring(0, 400);
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
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
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

    private record Download(byte[] bytes, String contentType) {
    }
}