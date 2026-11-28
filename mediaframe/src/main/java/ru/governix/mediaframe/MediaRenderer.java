package ru.governix.mediaframe;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.List;

public final class MediaRenderer extends MapRenderer {
    private final List<BufferedImage> frames;
    private int frameIndex;
    private int renderedIndex = -1;

    public MediaRenderer(List<BufferedImage> frames) {
        super(false);
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Media must contain at least one frame");
        }
        this.frames = List.copyOf(frames);
    }

    public boolean isAnimated() {
        return frames.size() > 1;
    }

    public void advance() {
        if (isAnimated()) {
            frameIndex = (frameIndex + 1) % frames.size();
        }
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (renderedIndex == frameIndex) {
            return;
        }

        canvas.drawImage(0, 0, frames.get(frameIndex));
        renderedIndex = frameIndex;
    }
}