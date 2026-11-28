# GovernixMediaFrame

Paper plugin for **Minecraft 1.21.11**. It displays images and video across rectangular grids of ordinary item frames using server-generated filled maps. Players do not need a client mod or resource pack.

## Install

1. Download the JAR from the successful **GovernixMediaFrame Paper 1.21.11** GitHub Actions run in this repository.
2. Put it in the server's `plugins/` directory.
3. Restart Paper.
4. The plugin checks for FFmpeg automatically. When it cannot find it, it downloads a Linux binary into `plugins/GovernixMediaFrame/bin/`.
5. For YouTube, it also prepares yt-dlp and Deno automatically in the same folder.

The binaries are downloaded from their upstream GitHub release pages, SHA-256 verified before use, and cached. No sudo, apt, pip, npm, or manual PATH setup is required on supported Linux systems. The first video request can take longer because the tools must be downloaded once. Allow outbound HTTPS and enough free disk space (roughly 150 MB for the cached tools).

Automatic tool setup supports Linux x86_64 and Linux ARM64. On other operating systems, install FFmpeg/yt-dlp/Deno yourself and set their executable paths in config.yml.

## Multi-frame screens

Place a rectangular grid of empty item frames on a flat wall. All frames must face the same direction and have the same rotation. Look at the **top-left frame** (from the point of view of someone looking at the wall) and run the command with the desired width and height.

Each block is one 128×128 map tile. Up to 25 tiles (5×5) are supported. The plugin renders the complete image at the combined resolution first, then crops it into tiles. For example:

| Grid | Combined resolution |
|---|---:|
| 1x1 | 128×128 |
| 3x3 | 384×384 |
| 3x4 | 384×512 |
| 5x3 | 640×384 |

Supported dimensions are 1–5 on each side, with a maximum of **25 frames per screen (5x5)**. The image is fitted proportionally into the requested canvas; it is not stretched. A source image with too little resolution cannot gain real detail from upscaling.

## Commands

Look at the top-left frame and run:

- `/mf <url>` — one frame (1x1) by default.
- `/mf <url> 3x3` — image/video across a 3×3 grid.
- `/mf <url> 3x4` — portrait grid, 384×512 pixels.
- `/mf <url> 5x3` — wide grid, 640×384 pixels.
- `/mf image <url> 3x3` — force image/GIF mode.
- `/mf video <url> 5x3` — force video mode.
- `/mf clear` — clear the entire connected MediaFrame screen.
- `/mf info` — show the URL and screen dimensions.

The full command name is `/mediaframe`; `/mf` is its alias. If a target frame is part of an existing MediaFrame screen, running a new command while looking at its top-left tile replaces the old screen, including tiles outside the new dimensions. The plugin will not overwrite ordinary items or frames belonging to a different screen.

## Supported media

- Static images: PNG, JPG/JPEG, GIF.
- Animated GIF: decoded to frames.
- Direct video: MP4, WebM, M4V, MOV, MKV and AVI (depending on FFmpeg codec support).
- Page URLs: Imgur and Tenor metadata/embedded CDN URL extraction.
- YouTube: public videos accessible to yt-dlp. Private, DRM-protected, age-gated or region-blocked videos are not guaranteed.

YouTube extraction uses yt-dlp with Deno's JavaScript runtime and the yt-dlp EJS challenge scripts. It does not play audio in Minecraft.

## Video limitations

Minecraft maps are 128×128 pixels and are not a native video surface. Video frames are decoded at the combined screen resolution, and every tile uses one shared playback clock so the whole image changes in sync. The default is 20 FPS (the practical ceiling for the Bukkit MapRenderer path at the server's standard 20 TPS) and a maximum clip length of 30 seconds / 180 decoded frames (subject to the 128 MiB decoded-frame memory limit). The actual visible update rate is also limited by Minecraft map packet updates and client rendering.

For YouTube, only the first configured `max-video-seconds` are downloaded (30 seconds by default, configurable up to 120 seconds). The plugin does not fetch the entire long-form video. Audio is not played through the map.

## Configuration

- `max-download-mb`: maximum downloaded media size (default 25 MB).
- `target-distance`: maximum distance to target the top-left frame (default 8 blocks).
- `ffmpeg-path`: optional FFmpeg executable or PATH command (default `ffmpeg`); otherwise auto-installed on supported Linux.
- `yt-dlp-path`: optional yt-dlp executable or PATH command (default `yt-dlp`); otherwise auto-installed on supported Linux.
- `deno-path`: optional Deno executable or PATH command (default `deno`); otherwise auto-installed for YouTube on supported Linux.
- `youtube-cookies-file`: optional Netscape-format YouTube cookies file (default `cookies.txt`).
- `video-fps`: requested map frame rate (default 20, maximum 20 with the Bukkit map renderer).
- `max-video-seconds`: maximum video clip length (default 30, plugin maximum 120).
- `max-video-frames`: maximum decoded video frames (default 180, also capped by memory). Existing 1.3.0 configs with the untouched 6 FPS default are migrated to 20 FPS; custom values are preserved.
- `max-image-frames`: maximum decoded GIF frames (default 120, also capped by memory).

## Safety and limits

- Only HTTP/HTTPS media URLs are accepted. Local/private network destinations are blocked.
- Default media download limit: 25 MB.
- Network loading and media decoding run off the main server thread.
- Upstream executable downloads are SHA-256 checked before use.
- The media URL and grid coordinates are saved on each frame, so the complete screen can be restored when its chunks are loaded again.
- Permission: `mediaframe.use` (default: operators).
- FFmpeg's LGPL build and the upstream yt-dlp/Deno binaries retain their upstream licensing; consult their release pages for the current license notices.
