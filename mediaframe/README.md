# GovernixMediaFrame

Paper plugin for **Minecraft 1.21.11**. Displays images and video frames in ordinary item frames by using a server-generated filled map. Players do not need a client mod or resource pack.

## Install

1. Download the JAR from the successful **GovernixMediaFrame Paper 1.21.11** GitHub Actions run in this repository.
2. Put it in the server's \`plugins/\` directory.
3. Restart Paper.
4. For MP4/WebM/GIF video playback, install FFmpeg on the server and make sure it is available in PATH.
5. For YouTube links, also install \`yt-dlp\` and make sure it is available in PATH. Set \`yt-dlp-path\` in \`plugins/GovernixMediaFrame/config.yml\` if needed.

Images (PNG/JPG and animated GIF) do not require FFmpeg.

## Commands

Look directly at the item frame and run:

- \`/mf https://example.com/image.png\` — detect the media format automatically.
- \`/mf https://imgur.com/gallery/...\` — resolve an Imgur page's Open Graph image.
- \`/mf https://tenor.com/view/...\` — resolve Tenor's Open Graph video (MP4 preferred) or GIF.
- \`/mf https://www.youtube.com/watch?v=...\` — download a short opening segment through yt-dlp.
- \`/mf image <url>\` — force image/GIF mode.
- \`/mf video <url>\` — force video mode.
- \`/mf clear\` — remove the map and saved media URL from the frame.
- \`/mf info\` — show the URL saved in the targeted frame.

The full command name is \`/mediaframe\`; \`/mf\` is its alias.

## Supported page links

For ordinary website pages, the plugin reads Open Graph and Twitter metadata:

- Images: \`og:image\`, \`og:image:url\`, \`twitter:image\`.
- Videos: \`og:video:secure_url\`, \`og:video\`, \`twitter:player:stream\`.

The page must expose a public direct media URL in its metadata. Login-gated pages, private posts, DRM streams, and pages that do not publish media metadata are not supported.

YouTube is handled separately by \`yt-dlp\`, not by an embedded browser. Public videos that yt-dlp can access are supported. Age-restricted/private/region-blocked videos may require cookies and are not guaranteed.

## Video limitations

Minecraft maps are 128×128 pixels and are not a native video surface. The plugin extracts frames with FFmpeg and updates the map over time. The default is 6 FPS and a maximum clip length of 30 seconds / 180 frames. The actual visible update rate is also limited by Minecraft map packet updates and client rendering.

For YouTube, only the first configured \`max-video-seconds\` are downloaded (30 seconds by default, configurable up to 120 seconds). The plugin does not fetch the entire long-form video. Audio is not played through the map.

## Configuration

- \`max-download-mb\`: maximum downloaded media size (default 25 MB).
- \`ffmpeg-path\`: FFmpeg executable (default \`ffmpeg\`).
- \`yt-dlp-path\`: yt-dlp executable (default \`yt-dlp\`).
- \`video-fps\`: requested map frame rate (default 6).
- \`max-video-seconds\`: maximum video clip length (default 30, plugin maximum 120).
- \`max-video-frames\`: maximum extracted frames.

## Safety and limits

- Only HTTP/HTTPS URLs are accepted. Local/private network destinations are blocked.
- Default download limit: 25 MB.
- Network loading and media decoding run off the main server thread.
- The media URL is saved on the item frame, so the plugin reloads its display when the containing chunk loads again.
- Permission: \`mediaframe.use\` (default: operators).
