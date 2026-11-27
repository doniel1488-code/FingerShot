package ru.governix.mahoyu;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

public final class MahoragaYujiMod implements ModInitializer {
    public static final String MOD_ID = "mahoyu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        TechniqueManager.register();
        LOGGER.info("Mahoraga & Yuji techniques loaded for Minecraft 1.21.8");
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("jjk")
                .then(literal("mahoraga").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    boolean enabled = AdaptationManager.toggle(player);
                    player.sendMessage(Text.literal(enabled
                            ? "§6☸ Колесо Махораги активировано. Адаптация началась."
                            : "§7☸ Колесо Махораги отключено."), true);
                    return 1;
                }))
                .then(literal("yuji").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    boolean enabled = TechniqueManager.toggleYuji(player);
                    player.sendMessage(Text.literal(enabled
                            ? "§cЮдзи: усиление проклятой энергией включено."
                            : "§7Режим Юдзи выключен."), true);
                    return 1;
                }))
                .then(literal("divergent").executes(context -> TechniqueManager.divergentFist(context.getSource().getPlayerOrThrow())))
                .then(literal("blackflash").executes(context -> TechniqueManager.blackFlash(context.getSource().getPlayerOrThrow())))
                .then(literal("shrine").executes(context -> TechniqueManager.shrine(context.getSource().getPlayerOrThrow())))
                .then(literal("rct").executes(context -> TechniqueManager.reverseCursedTechnique(context.getSource().getPlayerOrThrow())))
                .then(literal("blood").executes(context -> TechniqueManager.piercingBlood(context.getSource().getPlayerOrThrow())))
                .then(literal("help").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    player.sendMessage(Text.literal("§6§lMAHORAGA & YUJI\n"
                            + "§eG §7— колесо адаптации\n"
                            + "§eH §7— режим Юдзи\n"
                            + "§eR §7— Divergent Fist\n"
                            + "§eV §7— Black Flash\n"
                            + "§eB §7— Shrine / Dismantle\n"
                            + "§eN §7— обратная проклятая техника\n"
                            + "§eP §7— Piercing Blood\n"
                            + "§8Команды: /jjk help"), false);
                    return 1;
                })));
    }
}
