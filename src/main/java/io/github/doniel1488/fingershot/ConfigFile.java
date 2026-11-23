package io.github.doniel1488.fingershot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

public final class ConfigFile {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> T load(String filename, Class<T> type, T defaults, UnaryOperator<T> validate) {
        Path directory = FabricLoader.getInstance().getConfigDir();
        Path path = directory.resolve(filename);
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(directory);
                Files.writeString(path, JSON.toJson(defaults));
                return defaults;
            }
            T config = JSON.fromJson(Files.readString(path), type);
            if (config != null) {
                return validate.apply(config);
            }
            FingerShot.LOGGER.warn("{} is empty; using default settings", path);
        } catch (IOException | JsonParseException e) {
            FingerShot.LOGGER.warn("Could not load {}; using default settings", path, e);
        }
        return defaults;
    }

    public static double limit(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
