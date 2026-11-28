package ru.governix.mediaframe;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.List;

public final class MediaRenderer extends MapRenderer {
    private static final int TILE_SIZE = 128;

    private final Sequence sequence;
    private final int tileX;
    private final int tileY;
    private int renderedIndex = -1;

    public MediaRenderer(Sequence sequence, int tileX, int tileY) {
        super(false);
        this.sequence = sequence;
        this.tileX = tileX;
        this.tileY = tileY;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        int frameIndex = sequence.frameIndex();
        if (renderedIndex == frameIndex) {
            return;
        }

        BufferedImage fullFrame = sequence.frame(frameIndex);
        BufferedImage tile = fullFrame.getSubimage(
                tileX * TILE_SIZE,
                tileY * TILE_SIZE,
                TILE_SIZE,
                TILE_SIZE
        );
        canvas.drawImage(0, 0, tile);
        renderedIndex = frameIndex;
    }

    public static final class Sequence {
        private final List<BufferedImage> frames;
        private volatile int frameIndex;

        public Sequence(List<BufferedImage> frames) {
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

        public int frameIndex() {
            return frameIndex;
        }

        public BufferedImage frame(int index) {
            return frames.get(index);
        }
    }
}
