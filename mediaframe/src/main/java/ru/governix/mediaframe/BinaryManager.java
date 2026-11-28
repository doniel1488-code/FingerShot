package ru.governix.mediaframe;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class BinaryManager {
    private static final Logger LOGGER = Logger.getLogger("GovernixMediaFrame");
    private static final String FFMPEG_RELEASE =
            "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/latest/download/";
    private static final String YTDLP_RELEASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String DENO_RELEASE =
            "https://github.com/denoland/deno/releases/latest/download/";
    private static final long FFMPEG_ARCHIVE_LIMIT = 180L * 1024 * 1024;
    private static final long TOOL_BINARY_LIMIT = 100L * 1024 * 1024;
    private static final Pattern SHA_LINE =
            Pattern.compile("(?im)^\\s*([0-9a-f]{64})\\s+\\*?(.+?)\\s*$");

    private BinaryManager() {
    }

    static synchronized String ensureFfmpeg(String configuredPath, Path pluginFolder) throws IOException {
        String system = findExecutable(configuredPath, "-version");
        if (system != null) {
            return system;
        }

        Platform platform = requireLinuxPlatform();
        Path bin = pluginFolder.resolve("bin");
        Files.createDirectories(bin);
        Path executable = bin.resolve("ffmpeg");

        if (isUsable(executable, "-version")) {
            return executable.toAbsolutePath().toString();
        }

        String asset = platform.ffmpegAsset();
        Path archive = bin.resolve(asset);
        Path archiveChecksum = bin.resolve("ffmpeg-checksums.sha256");
        Path archiveTemporary = bin.resolve(asset + ".part");
        Path binaryTemporary = bin.resolve("ffmpeg.part");

        LOGGER.info("MediaFrame: системный FFmpeg не найден; автоматически загружаю FFmpeg для " + platform.label() + ".");
        try {
            download(FFMPEG_RELEASE + "checksums.sha256", archiveChecksum, 2L * 1024 * 1024);
            download(FFMPEG_RELEASE + asset, archiveTemporary, FFMPEG_ARCHIVE_LIMIT);
            verifySha256(archiveTemporary, checksumFor(archiveChecksum, asset), asset);

            extractFfmpeg(archiveTemporary, binaryTemporary);
            makeExecutable(binaryTemporary);
            moveAtomically(binaryTemporary, executable);
            if (!isUsable(executable, "-version")) {
                throw new IOException("Загруженный FFmpeg не запускается на этом сервере.");
            }
            LOGGER.info("MediaFrame: FFmpeg установлен в " + executable.toAbsolutePath());
            return executable.toAbsolutePath().toString();
        } finally {
            Files.deleteIfExists(archiveTemporary);
            Files.deleteIfExists(binaryTemporary);
        }
    }

    static synchronized String ensureYtDlp(String configuredPath, Path pluginFolder) throws IOException {
        String system = findExecutable(configuredPath, "--version");
        if (system != null) {
            return system;
        }

        Platform platform = requireLinuxPlatform();
        Path bin = pluginFolder.resolve("bin");
        Files.createDirectories(bin);
        Path executable = bin.resolve("yt-dlp");
        if (isUsable(executable, "--version")) {
            return executable.toAbsolutePath().toString();
        }

        String asset = platform.ytDlpAsset();
        Path checksum = bin.resolve("yt-dlp-SHA2-256SUMS");
        Path temporary = bin.resolve("yt-dlp.part");

        LOGGER.info("MediaFrame: yt-dlp не найден; автоматически загружаю официальный бинарный файл.");
        try {
            download(YTDLP_RELEASE + "SHA2-256SUMS", checksum, 2L * 1024 * 1024);
            download(YTDLP_RELEASE + asset, temporary, TOOL_BINARY_LIMIT);
            verifySha256(temporary, checksumFor(checksum, asset), asset);
            makeExecutable(temporary);
            moveAtomically(temporary, executable);
            if (!isUsable(executable, "--version")) {
                throw new IOException("Загруженный yt-dlp не запускается на этом сервере.");
            }
            LOGGER.info("MediaFrame: yt-dlp установлен в " + executable.toAbsolutePath());
            return executable.toAbsolutePath().toString();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static synchronized String ensureDeno(String configuredPath, Path pluginFolder) throws IOException {
        String system = findExecutable(configuredPath, "--version");
        if (system != null) {
            return system;
        }

        Platform platform = requireLinuxPlatform();
        Path bin = pluginFolder.resolve("bin");
        Files.createDirectories(bin);
        Path executable = bin.resolve("deno");
        if (isUsable(executable, "--version")) {
            return executable.toAbsolutePath().toString();
        }

        String asset = platform.denoAsset();
        Path archive = bin.resolve(asset);
        Path checksum = bin.resolve(asset + ".sha256sum");
        Path archiveTemporary = bin.resolve(asset + ".part");
        Path binaryTemporary = bin.resolve("deno.part");

        LOGGER.info("MediaFrame: для YouTube автоматически загружаю Deno (JavaScript runtime).");
        try {
            download(DENO_RELEASE + asset + ".sha256sum", checksum, 1024 * 1024);
            download(DENO_RELEASE + asset, archiveTemporary, TOOL_BINARY_LIMIT);
            verifySha256(archiveTemporary, checksumFor(checksum, asset), asset);
            extractZipExecutable(archiveTemporary, binaryTemporary, "deno");
            makeExecutable(binaryTemporary);
            moveAtomically(binaryTemporary, executable);
            if (!isUsable(executable, "--version")) {
                throw new IOException("Загруженный Deno не запускается на этом сервере.");
            }
            LOGGER.info("MediaFrame: Deno установлен в " + executable.toAbsolutePath());
            return executable.toAbsolutePath().toString();
        } finally {
            Files.deleteIfExists(archiveTemporary);
            Files.deleteIfExists(binaryTemporary);
        }
    }

    private static String findExecutable(String configured, String versionArgument) {
        if (configured == null || configured.isBlank()) {
            return null;
        }

        Path candidate;
        try {
            candidate = Path.of(configured);
        } catch (RuntimeException ignored) {
            candidate = null;
        }

        if (candidate != null && (candidate.isAbsolute() || configured.contains("/") || configured.contains("\\"))) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                    && isUsable(candidate, versionArgument)) {
                return candidate.toAbsolutePath().toString();
            }
            return null;
        }

        return isUsable(configured, versionArgument) ? configured : null;
    }

    private static boolean isUsable(Path executable, String argument) {
        return Files.isRegularFile(executable) && Files.isExecutable(executable)
                && isUsable(executable.toAbsolutePath().toString(), argument);
    }

    private static boolean isUsable(String executable, String argument) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, argument)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private static Platform requireLinuxPlatform() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            throw new IOException("Автоустановка видеоинструментов сейчас поддерживается на Linux. Установи FFmpeg/yt-dlp/Deno вручную и укажи пути в config.yml.");
        }

        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return new Platform("Linux x86_64", "ffmpeg-master-latest-linux64-lgpl.tar.xz",
                    "yt-dlp_linux", "deno-x86_64-unknown-linux-gnu.zip");
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return new Platform("Linux ARM64", "ffmpeg-master-latest-linuxarm64-lgpl.tar.xz",
                    "yt-dlp_linux_aarch64", "deno-aarch64-unknown-linux-gnu.zip");
        }

        throw new IOException("Автоустановка MediaFrame не поддерживает архитектуру " + arch
                + ". Установи инструменты вручную и укажи пути в config.yml.");
    }

    private static void download(String source, Path destination, long maxBytes) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(source).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout((int) Duration.ofSeconds(25).toMillis());
            connection.setReadTimeout((int) Duration.ofMinutes(3).toMillis());
            connection.setRequestProperty("User-Agent", "GovernixMediaFrame/1.2 (+https://github.com/doniel1488-code/FingerShot)");
            connection.setRequestProperty("Accept", "*/*");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Сервер загрузки инструментов вернул HTTP " + status + ": " + source);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > maxBytes) {
                throw new IOException("Загружаемый инструмент превышает безопасный лимит размера.");
            }

            Files.createDirectories(destination.getParent());
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Загружаемый инструмент превысил безопасный лимит размера.");
                    }
                    output.write(buffer, 0, read);
                }
                if (total == 0) {
                    throw new IOException("Сервер вернул пустой файл инструмента.");
                }
            }

            moveAtomically(temporary, destination);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            Files.deleteIfExists(temporary);
        }
    }

    private static String checksumFor(Path checksumFile, String assetName) throws IOException {
        for (String line : Files.readAllLines(checksumFile)) {
            Matcher matcher = SHA_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            String listedName = matcher.group(2).trim().replace('\\', '/');
            String baseName = listedName.substring(listedName.lastIndexOf('/') + 1);
            if (baseName.equals(assetName)) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }

        String onlyHash = Files.readString(checksumFile).trim();
        if (onlyHash.matches("(?i)[0-9a-f]{64}")) {
            return onlyHash.toLowerCase(Locale.ROOT);
        }
        throw new IOException("Не удалось найти SHA-256 для " + assetName + " в файле контрольных сумм.");
    }

    private static void verifySha256(Path file, String expected, String description) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) {
                actual.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            if (!actual.toString().equalsIgnoreCase(expected)) {
                Files.deleteIfExists(file);
                throw new IOException("Проверка SHA-256 не прошла для " + description + ". Файл удалён.");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 недоступен в Java.", exception);
        }
    }

    private static void extractFfmpeg(Path archive, Path destination) throws IOException {
        try (InputStream fileInput = Files.newInputStream(archive);
             XZCompressorInputStream xzInput = new XZCompressorInputStream(fileInput);
             TarArchiveInputStream tar = new TarArchiveInputStream(xzInput)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isFile() || !(name.equals("bin/ffmpeg") || name.endsWith("/bin/ffmpeg"))) {
                    continue;
                }

                long size = entry.getSize();
                if (size <= 0 || size > 200L * 1024 * 1024) {
                    throw new IOException("Недопустимый размер файла FFmpeg внутри архива.");
                }

                try (OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = size;
                    while (remaining > 0) {
                        int read = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            throw new IOException("Архив FFmpeg повреждён или обрезан.");
                        }
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                return;
            }
        }
        throw new IOException("В архиве FFmpeg не найден исполняемый файл bin/ffmpeg.");
    }

    private static void extractZipExecutable(Path archive, Path destination, String expectedName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                String baseName = name.substring(name.lastIndexOf('/') + 1);
                if (entry.isDirectory() || !baseName.equals(expectedName)) {
                    continue;
                }

                try (OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > TOOL_BINARY_LIMIT) {
                            throw new IOException("Исполняемый файл Deno превышает лимит размера.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                return;
            }
        }
        throw new IOException("В архиве Deno не найден файл " + expectedName + ".");
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE
            );
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!file.toFile().setExecutable(true, true)) {
                throw new IOException("Не удалось установить право запуска для " + file.getFileName() + ".");
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Platform(String label, String ffmpegAsset, String ytDlpAsset, String denoAsset) {
    }
}