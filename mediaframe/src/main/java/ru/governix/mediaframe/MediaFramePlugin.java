package ru.governix.mediaframe;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class MediaFramePlugin extends JavaPlugin implements Listener {
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "GovernixMediaFrame-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<UUID, ActivePlayback> playbacks = new ConcurrentHashMap<>();
    private NamespacedKey urlKey;
    private NamespacedKey typeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        urlKey = new NamespacedKey(this, "media_url");
        typeKey = new NamespacedKey(this, "media_type");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("mediaframe") == null) {
            getLogger().severe("Команда mediaframe не объявлена в plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("mediaframe").setExecutor(new MediaFrameCommand(this));
        getCommand("mediaframe").setTabCompleter(new MediaFrameCommand(this));

        Bukkit.getScheduler().runTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    restoreChunk(chunk);
                }
            }
        });

        getLogger().info("GovernixMediaFrame включён для Paper 1.21.11.");
    }

    @Override
    public void onDisable() {
        for (ActivePlayback playback : playbacks.values()) {
            if (playback.task() != null) {
                playback.task().cancel();
            }
        }
        playbacks.clear();
        workers.shutdownNow();
    }

    public void loadForFrame(Player requester, ItemFrame frame, String url, String forcedType) {
        UUID requesterId = requester == null ? null : requester.getUniqueId();
        stopPlayback(frame.getUniqueId());

        if (!frame.isValid()) {
            notifyPlayer(requesterId, "§cРамка больше не существует.");
            return;
        }

        notifyPlayer(requesterId, "§7Загружаю медиа и готовлю карту...");

        try {
            workers.submit(() -> {
                try {
                    long maxBytes = Math.max(1, getConfig().getLong("max-download-mb", 25)) * 1024L * 1024L;
                    List<BufferedImage> frames = MediaLoader.load(
                            url,
                            forcedType,
                            maxBytes,
                            getConfig().getString("ffmpeg-path", "ffmpeg"),
                            getConfig().getString("yt-dlp-path", "yt-dlp"),
                            getConfig().getString("deno-path", "deno"),
                            getConfig().getString("youtube-cookies-file", "cookies.txt"),
                            getDataFolder().toPath(),
                            clamp(getConfig().getInt("video-fps", 6), 1, 20),
                            clamp(getConfig().getInt("max-video-seconds", 30), 1, 120),
                            clamp(getConfig().getInt("max-video-frames", 180), 1, 600),
                            clamp(getConfig().getInt("max-image-frames", 120), 1, 240)
                    );

                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!isEnabled() || !frame.isValid()) {
                            notifyPlayer(requesterId, "§cНе удалось установить медиа: рамка уже недоступна.");
                            return;
                        }
                        installMedia(frame, url, forcedType, frames);
                        notifyPlayer(requesterId, "§aГотово! §7В рамке отображается "
                                + (frames.size() > 1 ? "анимация (" + frames.size() + " кадров)." : "изображение."));
                    });
                } catch (Exception exception) {
                    getLogger().log(Level.WARNING, "Не удалось загрузить медиа для рамки: " + exception.getMessage());
                    notifyPlayer(requesterId, "§cНе удалось загрузить медиа: §7" + safeMessage(exception));
                }
            });
        } catch (RuntimeException exception) {
            notifyPlayer(requesterId, "§cПлагин выключается, попробуй ещё раз.");
        }
    }

    private void installMedia(ItemFrame frame, String url, String type, List<BufferedImage> frames) {
        stopPlayback(frame.getUniqueId());

        MapView mapView = Bukkit.createMap(frame.getWorld());
        for (MapRenderer renderer : new ArrayList<>(mapView.getRenderers())) {
            mapView.removeRenderer(renderer);
        }
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);
        mapView.setLocked(true);

        MediaRenderer renderer = new MediaRenderer(frames);
        mapView.addRenderer(renderer);

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(mapView);
        mapItem.setItemMeta(meta);
        frame.setItem(mapItem, false);

        frame.getPersistentDataContainer().set(urlKey, PersistentDataType.STRING, url);
        frame.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);

        BukkitTask task = null;
        if (renderer.isAnimated()) {
            int fps = clamp(getConfig().getInt("video-fps", 6), 1, 20);
            long interval = Math.max(1L, 20L / fps);
            task = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!frame.isValid()) {
                    stopPlayback(frame.getUniqueId());
                    return;
                }
                renderer.advance();
            }, interval, interval);
        }

        playbacks.put(frame.getUniqueId(), new ActivePlayback(renderer, task));
    }

    public void clearFrame(ItemFrame frame) {
        stopPlayback(frame.getUniqueId());
        frame.getPersistentDataContainer().remove(urlKey);
        frame.getPersistentDataContainer().remove(typeKey);
        frame.setItem(null, false);
    }

    public String getStoredUrl(ItemFrame frame) {
        return frame.getPersistentDataContainer().get(urlKey, PersistentDataType.STRING);
    }

    private void restoreChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemFrame frame) || !frame.isValid()) {
                continue;
            }

            String url = getStoredUrl(frame);
            if (url == null || playbacks.containsKey(frame.getUniqueId())) {
                continue;
            }

            String type = frame.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            loadForFrame(null, frame, url, type == null ? "auto" : type);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(this, () -> restoreChunk(event.getChunk()));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof ItemFrame frame) {
                stopPlayback(frame.getUniqueId());
            }
        }
    }

    private void stopPlayback(UUID frameId) {
        ActivePlayback playback = playbacks.remove(frameId);
        if (playback != null && playback.task() != null) {
            playback.task().cancel();
        }
    }

    private void notifyPlayer(UUID playerId, String message) {
        if (playerId == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "неизвестная ошибка";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private record ActivePlayback(MediaRenderer renderer, BukkitTask task) {
    }
}