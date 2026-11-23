package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.ConfigFile;

final class ClientSettings {
    int maxFragments = 24;
    double effects = 1;
    float volume = 0.75f;

    static ClientSettings load() {
        return ConfigFile.load("fingershot-client.json", ClientSettings.class,
                new ClientSettings(), ClientSettings::validate);
    }

    private ClientSettings validate() {
        maxFragments = Math.clamp(maxFragments, 2, 128);
        effects = ConfigFile.limit(effects, 0, 2, 1);
        volume = (float) ConfigFile.limit(volume, 0, 1, 0.75);
        return this;
    }
}
