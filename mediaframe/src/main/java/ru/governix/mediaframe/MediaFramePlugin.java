package ru.governix.mediaframe;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class MediaFramePlugin extends JavaPlugin implements Listener {
    private static final double POSITION_TOLERANCE = 0.18;
    private static final double GROUP_SEARCH_RADIUS = 8.0;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "GovernixMediaFrame-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, ActivePlayback> playbacks = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingFrames = ConcurrentHashMap.newKeySet();

    private NamespacedKey urlKey;
    private NamespacedKey typeKey;
    private NamespacedKey groupKey;
    private NamespacedKey widthKey;
    private NamespacedKey heightKey;
    private NamespacedKey columnKey;
    private NamespacedKey rowKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        urlKey = new NamespacedKey(this, "media_url");
        typeKey = new NamespacedKey(this, "media_type");
        groupKey = new NamespacedKey(this, "media_group");
        widthKey = new NamespacedKey(this, "media_width");
        heightKey = new NamespacedKey(this, "media_height");
        columnKey = new NamespacedKey(this, "media_column");
        rowKey = new NamespacedKey(this, "media_row");

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("mediaframe") == null) {
            getLogger().severe("Команда mediaframe не объявлена в plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MediaFrameCommand command = new MediaFrameCommand(this);
        getCommand("mediaframe").setExecutor(command);
        getCommand("mediaframe").setTabCompleter(command);

        Bukkit.getScheduler().runTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    restoreChunk(chunk);
                }
            }
        });

        getLogger().info("GovernixMediaFrame 1.3.1 включён для Paper 1.21.11 (до 20 кадров/с через Bukkit MapRenderer).");
    }

    private void migrateConfig() {
        int configVersion = getConfig().getInt("config-version", 1);
        if (configVersion < 2) {
            // Previous releases shipped with 6 FPS as their default.
            // Raise only that untouched default; preserve custom FPS values.
            if (getConfig().getInt("video-fps", 6) == 6) {
                getConfig().set("video-fps", 20);
            }
            getConfig().set("config-version", 2);
            saveConfig();
            getLogger().info("Конфигурация обновлена: стандартная частота видео теперь 20 FPS.");
        }
    }

    @Override
    public void onDisable() {
        for (ActivePlayback playback : playbacks.values()) {
            if (playback.task() != null) {
                playback.task().cancel();
            }
        }
        playbacks.clear();
        pendingFrames.clear();
        workers.shutdownNow();
    }

    public void loadForFrame(
            Player requester,
            ItemFrame anchor,
            String url,
            String forcedType,
            MediaFrameSize size
    ) {
        if (!anchor.isValid()) {
            notifyPlayer(requester, "§cРамка больше не существует.");
            return;
        }

        List<ItemFrame> screenFrames = findScreenFrames(anchor, size);
        if (screenFrames == null) {
            notifyPlayer(requester, "§cНе найдена вся сетка рамок " + size.label()
                    + ". Поставь рамки вплотную и целься в верхнюю левую рамку.");
            return;
        }

        String previousGroup = getPersistentString(anchor, groupKey);
        for (ItemFrame frame : screenFrames) {
            if (!isReplaceable(frame, anchor, previousGroup)) {
                notifyPlayer(requester, "§cРамка сетки " + size.label()
                        + " уже занята предметом или другой MediaFrame-картинкой. Ничего не изменено.");
                return;
            }
        }

        beginLoading(requester, anchor, screenFrames, url, forcedType, size,
                previousGroup, UUID.randomUUID().toString(), false);
    }

    private void beginLoading(
            Player requester,
            ItemFrame anchor,
            List<ItemFrame> screenFrames,
            String url,
            String forcedType,
            MediaFrameSize size,
            String previousGroup,
            String newGroup,
            boolean restoring
    ) {
        List<UUID> reserved = new ArrayList<>();
        for (ItemFrame frame : screenFrames) {
            UUID id = frame.getUniqueId();
            if (!pendingFrames.add(id)) {
                reserved.forEach(pendingFrames::remove);
                notifyPlayer(requester, "§eЭта сетка уже загружается. Подожди завершения текущей загрузки.");
                return;
            }
            reserved.add(id);
        }

        notifyPlayer(requester, "§7Загружаю медиа для сетки §f" + size.label()
                + " §7(" + size.pixelWidth() + "×" + size.pixelHeight() + " пикселей)...");

        long maxBytes = Math.max(1, getConfig().getLong("max-download-mb", 25)) * 1024L * 1024L;
        String ffmpegPath = getConfig().getString("ffmpeg-path", "ffmpeg");
        String ytDlpPath = getConfig().getString("yt-dlp-path", "yt-dlp");
        String denoPath = getConfig().getString("deno-path", "deno");
        String cookiesFile = getConfig().getString("youtube-cookies-file", "cookies.txt");
        int fps = clamp(getConfig().getInt("video-fps", 20), 1, 20);
        int maxVideoSeconds = clamp(getConfig().getInt("max-video-seconds", 30), 1, 120);
        int maxVideoFrames = clamp(getConfig().getInt("max-video-frames", 180), 1, 600);
        int maxImageFrames = clamp(getConfig().getInt("max-image-frames", 120), 1, 240);

        try {
            workers.submit(() -> {
                try {
                    List<BufferedImage> frames = MediaLoader.load(
                            url,
                            forcedType,
                            maxBytes,
                            ffmpegPath,
                            ytDlpPath,
                            denoPath,
                            cookiesFile,
                            getDataFolder().toPath(),
                            fps,
                            maxVideoSeconds,
                            maxVideoFrames,
                            maxImageFrames,
                            size.width(),
                            size.height()
                    );

                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            if (!isEnabled() || screenFrames.stream().anyMatch(frame -> !frame.isValid())) {
                                notifyPlayer(requester, "§cНе удалось установить медиа: одна из рамок больше недоступна.");
                                return;
                            }

                            if (restoring) {
                                if (screenFrames.stream().anyMatch(frame ->
                                        !newGroup.equals(getPersistentString(frame, groupKey)))) {
                                    return;
                                }
                            } else {
                                for (ItemFrame frame : screenFrames) {
                                    if (!isReplaceable(frame, anchor, previousGroup)) {
                                        notifyPlayer(requester, "§cСетка изменилась во время загрузки. Ничего не установлено.");
                                        return;
                                    }
                                }

                                if (previousGroup != null) {
                                    clearManagedGroup(anchor, previousGroup);
                                } else if (getStoredUrl(anchor) != null) {
                                    clearLegacyFrame(anchor);
                                }
                            }

                            installMedia(screenFrames, url, forcedType, size, newGroup, frames);
                            notifyPlayer(requester, "§aГотово! §7Изображение установлено на §f"
                                    + size.label() + " §7блоков; "
                                    + (frames.size() > 1 ? "анимация, " + frames.size() + " кадров." : "одно изображение."));
                        } finally {
                            reserved.forEach(pendingFrames::remove);
                        }
                    });
                } catch (Exception exception) {
                    getLogger().log(Level.WARNING,
                            "Не удалось загрузить медиа для сетки " + size.label() + ": " + exception.getMessage());
                    reserved.forEach(pendingFrames::remove);
                    notifyPlayer(requester, "§cНе удалось загрузить медиа: §7" + safeMessage(exception));
                }
            });
        } catch (RuntimeException exception) {
            reserved.forEach(pendingFrames::remove);
            notifyPlayer(requester, "§cПлагин выключается, попробуй ещё раз.");
        }
    }

    private void installMedia(
            List<ItemFrame> screenFrames,
            String url,
            String type,
            MediaFrameSize size,
            String groupId,
            List<BufferedImage> frames
    ) {
        stopPlayback(groupId);
        MediaRenderer.Sequence sequence = new MediaRenderer.Sequence(frames);

        for (int row = 0; row < size.height(); row++) {
            for (int column = 0; column < size.width(); column++) {
                ItemFrame frame = screenFrames.get(row * size.width() + column);
                MapView mapView = Bukkit.createMap(frame.getWorld());
                for (MapRenderer renderer : new ArrayList<>(mapView.getRenderers())) {
                    mapView.removeRenderer(renderer);
                }
                mapView.setTrackingPosition(false);
                mapView.setUnlimitedTracking(false);
                mapView.setLocked(true);

                mapView.addRenderer(new MediaRenderer(sequence, column, row));

                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                meta.setMapView(mapView);
                mapItem.setItemMeta(meta);
                frame.setItem(mapItem, false);

                PersistentDataContainer data = frame.getPersistentDataContainer();
                data.set(urlKey, PersistentDataType.STRING, url);
                data.set(typeKey, PersistentDataType.STRING, type);
                data.set(groupKey, PersistentDataType.STRING, groupId);
                data.set(widthKey, PersistentDataType.INTEGER, size.width());
                data.set(heightKey, PersistentDataType.INTEGER, size.height());
                data.set(columnKey, PersistentDataType.INTEGER, column);
                data.set(rowKey, PersistentDataType.INTEGER, row);
            }
        }

        BukkitTask task = null;
        if (sequence.isAnimated()) {
            int fps = clamp(getConfig().getInt("video-fps", 6), 1, 20);
            long interval = Math.max(1L, 20L / fps);
            task = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (screenFrames.stream().anyMatch(frame -> !frame.isValid())) {
                    stopPlayback(groupId);
                    return;
                }
                sequence.advance();
            }, interval, interval);
        }

        playbacks.put(groupId, new ActivePlayback(sequence, task));
    }

    public boolean clearFrame(ItemFrame frame) {
        String groupId = getPersistentString(frame, groupKey);
        if (groupId != null) {
            clearManagedGroup(frame, groupId);
            return true;
        }

        if (getStoredUrl(frame) != null) {
            clearLegacyFrame(frame);
            stopPlayback(frame.getUniqueId().toString());
            return true;
        }

        return false;
    }

    private void clearLegacyFrame(ItemFrame frame) {
        PersistentDataContainer data = frame.getPersistentDataContainer();
        data.remove(urlKey);
        data.remove(typeKey);
        frame.setItem(null, false);
    }

    private void clearManagedGroup(ItemFrame reference, String groupId) {
        stopPlayback(groupId);
        for (ItemFrame frame : findGroupFrames(reference, groupId)) {
            PersistentDataContainer data = frame.getPersistentDataContainer();
            data.remove(urlKey);
            data.remove(typeKey);
            data.remove(groupKey);
            data.remove(widthKey);
            data.remove(heightKey);
            data.remove(columnKey);
            data.remove(rowKey);
            frame.setItem(null, false);
        }
    }

    public String getStoredUrl(ItemFrame frame) {
        return getPersistentString(frame, urlKey);
    }

    public String getStoredDimensions(ItemFrame frame) {
        Integer width = frame.getPersistentDataContainer().get(widthKey, PersistentDataType.INTEGER);
        Integer height = frame.getPersistentDataContainer().get(heightKey, PersistentDataType.INTEGER);
        if (width == null || height == null) {
            return "1x1";
        }
        return width + "x" + height;
    }

    private List<ItemFrame> findScreenFrames(ItemFrame anchor, MediaFrameSize size) {
        BlockFace facing = anchor.getFacing();
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH
                && facing != BlockFace.EAST && facing != BlockFace.WEST) {
            return null;
        }

        Vector normal = new Vector(facing.getModX(), 0, facing.getModZ()).normalize();
        Vector right = new Vector(0, 1, 0).crossProduct(normal).normalize();
        Vector origin = anchor.getLocation().toVector();
        ItemFrame[] grid = new ItemFrame[size.width() * size.height()];
        grid[0] = anchor;

        Collection<Entity> nearby = anchor.getWorld().getNearbyEntities(
                anchor.getLocation(),
                size.width() + 1.0,
                size.height() + 1.0,
                size.width() + 1.0,
                entity -> entity instanceof ItemFrame
        );

        for (Entity entity : nearby) {
            if (!(entity instanceof ItemFrame candidate) || !candidate.isValid()
                    || candidate.getFacing() != facing
                    || candidate.getRotation() != anchor.getRotation()) {
                continue;
            }

            Vector delta = candidate.getLocation().toVector().subtract(origin);
            double horizontalOffset = delta.dot(right);
            double normalOffset = delta.dot(normal);
            int column = (int) Math.round(horizontalOffset);
            int row = (int) Math.round(-delta.getY());

            if (Math.abs(horizontalOffset - column) > POSITION_TOLERANCE
                    || Math.abs(-delta.getY() - row) > POSITION_TOLERANCE
                    || Math.abs(normalOffset) > POSITION_TOLERANCE
                    || column < 0 || column >= size.width()
                    || row < 0 || row >= size.height()) {
                continue;
            }

            grid[row * size.width() + column] = candidate;
        }

        List<ItemFrame> result = new ArrayList<>(grid.length);
        for (int index = 0; index < grid.length; index++) {
            if (grid[index] == null) {
                return null;
            }
            result.add(grid[index]);
        }
        return result;
    }

    private boolean isReplaceable(ItemFrame frame, ItemFrame anchor, String previousGroup) {
        String frameGroup = getPersistentString(frame, groupKey);
        if (previousGroup != null && previousGroup.equals(frameGroup)) {
            return true;
        }
        if (frame == anchor && getStoredUrl(frame) != null) {
            return true;
        }

        ItemStack item = frame.getItem();
        return item == null || item.getType() == Material.AIR;
    }

    private List<ItemFrame> findGroupFrames(ItemFrame reference, String groupId) {
        List<ItemFrame> result = new ArrayList<>();
        Collection<Entity> nearby = reference.getWorld().getNearbyEntities(
                reference.getLocation(),
                GROUP_SEARCH_RADIUS,
                GROUP_SEARCH_RADIUS,
                GROUP_SEARCH_RADIUS,
                entity -> entity instanceof ItemFrame
                        && groupId.equals(getPersistentString((ItemFrame) entity, groupKey))
        );

        for (Entity entity : nearby) {
            if (entity instanceof ItemFrame frame && frame.isValid()) {
                result.add(frame);
            }
        }
        if (getPersistentString(reference, groupKey) != null
                && groupId.equals(getPersistentString(reference, groupKey))
                && !result.contains(reference)) {
            result.add(reference);
        }
        return result;
    }

    private void restoreChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemFrame frame) || !frame.isValid()) {
                continue;
            }

            String groupId = getPersistentString(frame, groupKey);
            if (groupId == null || playbacks.containsKey(groupId) || pendingFrames.contains(frame.getUniqueId())) {
                continue;
            }

            ItemFrame anchor = null;
            for (ItemFrame member : findGroupFrames(frame, groupId)) {
                Integer column = member.getPersistentDataContainer().get(columnKey, PersistentDataType.INTEGER);
                Integer row = member.getPersistentDataContainer().get(rowKey, PersistentDataType.INTEGER);
                if (Integer.valueOf(0).equals(column) && Integer.valueOf(0).equals(row)) {
                    anchor = member;
                    break;
                }
            }
            if (anchor == null) {
                continue;
            }

            Integer width = anchor.getPersistentDataContainer().get(widthKey, PersistentDataType.INTEGER);
            Integer height = anchor.getPersistentDataContainer().get(heightKey, PersistentDataType.INTEGER);
            String url = getStoredUrl(anchor);
            String type = getPersistentString(anchor, typeKey);
            if (width == null || height == null || url == null || type == null) {
                continue;
            }

            MediaFrameSize size;
            try {
                size = new MediaFrameSize(width, height);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Пропускаю повреждённую сетку MediaFrame " + groupId + ".");
                continue;
            }

            List<ItemFrame> screenFrames = findScreenFrames(anchor, size);
            if (screenFrames == null || screenFrames.stream().anyMatch(member ->
                    !groupId.equals(getPersistentString(member, groupKey)))) {
                // A screen may span chunks. A later chunk-load event will retry the restore.
                continue;
            }

            beginLoading(null, anchor, screenFrames, url, type, size, groupId, groupId, true);
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
                String groupId = getPersistentString(frame, groupKey);
                if (groupId != null) {
                    stopPlayback(groupId);
                }
            }
        }
    }

    private void stopPlayback(String groupId) {
        ActivePlayback playback = playbacks.remove(groupId);
        if (playback != null && playback.task() != null) {
            playback.task().cancel();
        }
    }

    private void notifyPlayer(Player player, String message) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && online.isOnline()) {
                online.sendMessage(message);
            }
        });
    }

    private static String getPersistentString(ItemFrame frame, NamespacedKey key) {
        return frame.getPersistentDataContainer().get(key, PersistentDataType.STRING);
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

    private record ActivePlayback(MediaRenderer.Sequence sequence, BukkitTask task) {
    }
}
