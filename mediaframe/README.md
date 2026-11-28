# GovernixMediaFrame

Paper plugin for **Minecraft 1.21.11**. It displays images and video frames inside ordinary item frames by using a server-generated filled map. Players do not need a client mod or resource pack.

## Install

1. Download the JAR from the successful **GovernixMediaFrame Paper 1.21.11** GitHub Actions run in this repository.
2. Put it in the server's \`plugins/\` directory.
3. Restart Paper.
4. The plugin checks for FFmpeg automatically. When it cannot find it, it downloads a Linux binary into \`plugins/GovernixMediaFrame/bin/\`.
5. For YouTube, it also prepares yt-dlp and Deno automatically in the same folder.

The binaries are downloaded from their upstream GitHub release pages, SHA-256 verified before use, and cached. No sudo, apt, pip, npm, or manual PATH setup is required on supported Linux systems. The first video request can take longer because the tools must be downloaded once. Allow outbound HTTPS and enough free disk space (roughly 150 MB for the cached tools).

Automatic tool setup supports Linux x86_64 and Linux ARM64. On other operating systems, install FFmpeg/yt-dlp/Deno yourself and set their executable paths in config.yml.

## Commands

Look directly at the item frame and run:

- \`/mf https://example.com/image.png\` — detect the media format automatically.
- \`/mf https://imgur.com/gallery/...\` — resolve an Imgur page's Open Graph/embedded image URL.
- \`/mf https://tenor.com/view/...\` — resolve Tenor's MP4/WebM/GIF URL from its page metadata or embedded media data.
- \`/mf https://www.youtube.com/watch?v=...\` — retrieve the opening video segment using yt-dlp.
- \`/mf image <url>\` — force image/GIF mode.
- \`/mf video <url>\` — force video mode.
- \`/mf clear\` — remove the map and saved media URL from the frame.
- \`/mf info\` — show the URL saved in the targeted frame.

The full command name is \`/mediaframe\`; \`/mf\` is its alias.

## Supported media

- Static images: PNG, JPG/JPEG, GIF.
- Animated GIF: decoded to frames.
- Direct video: MP4, WebM, M4V, MOV, MKV and AVI (depending on FFmpeg codec support).
- Page URLs: Imgur and Tenor metadata/embedded CDN URL extraction.
- YouTube: public videos accessible to yt-dlp. Private, DRM-protected, age-gated or region-blocked videos are not guaranteed.

YouTube extraction uses yt-dlp with Deno's JavaScript runtime and the yt-dlp EJS challenge scripts. It does not play audio in Minecraft.

## Video limitations

Minecraft maps are 128×128 pixels and are not a native video surface. The plugin extracts frames with FFmpeg and updates the map over time. The default is 6 FPS and a maximum clip length of 30 seconds / 180 frames. The actual visible update rate is also limited by Minecraft map packet updates and client rendering.

For YouTube, only the first configured \`max-video-seconds\` are downloaded (30 seconds by default, configurable up to 120 seconds). The plugin does not fetch the entire long-form video. Audio is not played through the map.

## Configuration

- \`max-download-mb\`: maximum downloaded media size (default 25 MB).
- \`ffmpeg-path\`: optional FFmpeg executable or PATH command (default \`ffmpeg\`); otherwise auto-installed on supported Linux.
- \`yt-dlp-path\`: optional yt-dlp executable or PATH command (default \`yt-dlp\`); otherwise auto-installed on supported Linux.
- \`deno-path\`: optional Deno executable or PATH command (default \`deno\`); otherwise auto-installed for YouTube on supported Linux.
- \`video-fps\`: requested map frame rate (default 6).
- \`max-video-seconds\`: maximum video clip length (default 30, plugin maximum 120).
- \`max-video-frames\`: maximum extracted frames.

## Safety and limits

- Only HTTP/HTTPS media URLs are accepted. Local/private network destinations are blocked.
- Default media download limit: 25 MB.
- Network loading and media decoding run off the main server thread.
- Upstream executable downloads are SHA-256 checked before extraction or execution.
- The media URL is saved on the item frame, so the plugin reloads its display when the containing chunk loads again.
- Permission: \`mediaframe.use\` (default: operators).
- FFmpeg's LGPL build and the upstream yt-dlp/Deno binaries retain their upstream licensing; consult their release pages for the current license notices.
