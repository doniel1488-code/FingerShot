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
            plugin.clearFrame(frame);
            player.sendMessage("§aМедиа удалено из рамки.");
            return true;
        }

        if (first.equals("info")) {
            String url = plugin.getStoredUrl(frame);
            player.sendMessage(url == null
                    ? "§7В этой рамке нет медиа."
                    : "§7URL рамки: §f" + url);
            return true;
        }

        String type = "auto";
        String url;
        if ((first.equals("image") || first.equals("video")) && args.length >= 2) {
            type = first;
            url = String.join("", java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            url = String.join("", args);
        }

        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            player.sendMessage("§cУкажи полный URL, начинающийся с http:// или https://.");
            sendUsage(player, label);
            return true;
        }

        plugin.loadForFrame(player, frame, url, type);
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
        return hit instanceof ItemFrame frame ? frame : null;
    }

    private void sendUsage(Player player, String label) {
        player.sendMessage("§6MediaFrame §7— изображение и видео в рамке");
        player.sendMessage("§e/" + label + " <url> §7— определить формат автоматически");
        player.sendMessage("§e/" + label + " image <url> §7— загрузить картинку/GIF");
        player.sendMessage("§e/" + label + " video <url> §7— загрузить прямой MP4/WebM");
        player.sendMessage("§e/" + label + " clear §7— очистить рамку");
        player.sendMessage("§e/" + label + " info §7— показать сохранённый URL");
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