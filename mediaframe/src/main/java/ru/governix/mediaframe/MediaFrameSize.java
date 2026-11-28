package ru.governix.mediaframe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record MediaFrameSize(int width, int height) {
    private static final Pattern SIZE_PATTERN = Pattern.compile("(?i)^(\\d+)[xх](\\d+)$");
    public static final int MAX_SIDE = 5;
    public static final int MAX_TILES = 25;

    public MediaFrameSize {
        if (width < 1 || width > MAX_SIDE || height < 1 || height > MAX_SIDE
                || (long) width * height > MAX_TILES) {
            throw new IllegalArgumentException(
                    "Размер должен быть от 1x1 до 5x5, но не более 25 рамок (например, 5x3 или 3x4)."
            );
        }
    }

    public static boolean looksLikeSize(String value) {
        return value != null && SIZE_PATTERN.matcher(value).matches();
    }

    public static MediaFrameSize parse(String value) {
        Matcher matcher = SIZE_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Размер указывается в формате ШИРИНАxВЫСОТА, например 3x4.");
        }

        try {
            return new MediaFrameSize(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Слишком большое значение размера.");
        }
    }

    public int pixelWidth() {
        return width * 128;
    }

    public int pixelHeight() {
        return height * 128;
    }

    public String label() {
        return width + "x" + height;
    }
}
