package io.github.doniel1488.fingershot;

import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.server.ShotServer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FingerShot implements ModInitializer {
    public static final String ID = "fingershot";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    public static Identifier id(String path) {
        return Identifier.of(ID, path);
    }

    @Override
    public void onInitialize() {
        ShotPackets.register();
        ShotSounds.register();
        ShotServer.register();
    }
}
