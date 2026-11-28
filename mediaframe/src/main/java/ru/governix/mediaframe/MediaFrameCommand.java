package ru.governix.mediaframe;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MediaFrameCommand implements CommandExecutor, TabCompleter {
    private final MediaFramePlugin plugin;

    public MediaFrameCommand(MediaFramePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эту команду нужно выполнить в игре, глядя на рамку.");
            return true;
        }
        if (!player.hasPermission("mediaframe.use")) {
            player.sendMessage("§cУ тебя нет прав на использование MediaFrame.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(player, label);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        ItemFrame frame = findTargetFrame(player);
        if (frame == null) {
            player.sendMessage("§cПосмотри прямо на рамку предмета (до "
                    + plugin.getConfig().getDouble("target-distance", 8.0) + " блоков).");
            return true;
        }

        if (first.equals("clear") || first.equals("remove")) {
            if (plugin.clearFrame(frame)) {
                player.sendMessage("§aМедиа удалено из всей сетки рамок.");
            } else {
                player.sendMessage("§7В этой рамке нет изображения MediaFrame.");
            }
            return true;
        }

        if (first.equals("info")) {
            String url = plugin.getStoredUrl(frame);
            player.sendMessage(url == null
                    ? "§7В этой рамке нет медиа."
                    : "§7URL: §f" + url + " §7| размер: §f" + plugin.getStoredDimensions(frame));
            return true;
        }

        int end = args.length;
        MediaFrameSize size = new MediaFrameSize(1, 1);
        if (end > 1 && MediaFrameSize.looksLikeSize(args[end - 1])) {
            try {
                size = MediaFrameSize.parse(args[end - 1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§c" + exception.getMessage());
                sendUsage(player, label);
                return true;
            }
            end--;
        }

        String type = "auto";
        int urlStart = 0;
        if (first.equals("image") || first.equals("video")) {
            type = first;
            urlStart = 1;
        }
        if (end <= urlStart) {
            sendUsage(player, label);
            return true;
        }

        String url = String.join("", Arrays.copyOfRange(args, urlStart, end));
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            player.sendMessage("§cУкажи полный URL, начинающийся с http:// или https://.");
            sendUsage(player, label);
            return true;
        }

        plugin.loadForFrame(player, frame, url, type, size);
        return true;
    }

    private ItemFrame findTargetFrame(Player player) {
        double distance = Math.max(1.0, plugin.getConfig().getDouble("target-distance", 8.0));
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                distance,
                0.15,
                entity -> entity instanceof ItemFrame
        );
        if (result == null) {
            return null;
        }
        Entity hit = result.getHitEntity();
        return hit instanceof ItemFrame itemFrame ? itemFrame : null;
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage("§6MediaFrame §7— изображения и видео на сетке рамок");
        player.sendMessage("§e/" + label + " <url> [ширинаxвысота] §7— загрузить медиа");
        player.sendMessage("§e/" + label + " image <url> [ширинаxвысота] §7— изображение/GIF");
        player.sendMessage("§e/" + label + " video <url> [ширинаxвысота] §7— видео");
        player.sendMessage("§eПримеры: §f/mf <url> 3x3 §7или §f/mf video <url> 5x3");
        player.sendMessage("§eРазмер: от 1x1 до 5x3 (не более 15 рамок).");
        player.sendMessage("§eПеред загрузкой поставь пустую сетку и целься в её верхнюю левую рамку.");
        player.sendMessage("§e/" + label + " clear §7— очистить всю сетку");
        player.sendMessage("§e/" + label + " info §7— информация о медиа");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String value : List.of("image", "video", "clear", "info")) {
                if (value.startsWith(prefix)) {
                    suggestions.add(value);
                }
            }
            return suggestions;
        }
        return List.of();
    }
}
