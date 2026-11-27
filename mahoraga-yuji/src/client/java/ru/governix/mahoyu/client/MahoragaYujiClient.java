package ru.governix.mahoyu.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class MahoragaYujiClient implements ClientModInitializer {
    private record Ability(KeyBinding key, String command) { }

    private static List<Ability> abilities;

    private static Ability key(String translationKey, int defaultKey, String command) {
        KeyBinding binding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, defaultKey, "key.mahoyu.category"));
        return new Ability(binding, command);
    }

    @Override
    public void onInitializeClient() {
        abilities = List.of(
                key("key.mahoyu.mahoraga", GLFW.GLFW_KEY_G, "mahoraga"),
                key("key.mahoyu.yuji", GLFW.GLFW_KEY_H, "yuji"),
                key("key.mahoyu.divergent", GLFW.GLFW_KEY_R, "divergent"),
                key("key.mahoyu.black_flash", GLFW.GLFW_KEY_V, "blackflash"),
                key("key.mahoyu.shrine", GLFW.GLFW_KEY_B, "shrine"),
                key("key.mahoyu.rct", GLFW.GLFW_KEY_N, "rct"),
                key("key.mahoyu.blood", GLFW.GLFW_KEY_P, "blood")
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null || client.currentScreen != null) {
                return;
            }
            for (Ability ability : abilities) {
                while (ability.key().wasPressed()) {
                    client.getNetworkHandler().sendChatCommand("jjk " + ability.command());
                }
            }
        });
    }
}
