# GovernixMediaFrame

Paper plugin for **Minecraft 1.21.11**. It displays images and video frames inside ordinary item frames by using a server-generated filled map. Players do not need a client mod or resource pack.

## Install

1. Download the JAR from the successful **MediaFrame Paper 1.21.11** GitHub Actions run in this repository.
2. Put it in the server's \`plugins/\` directory.
3. Restart Paper.
4. For MP4/WebM playback, install FFmpeg on the server and make sure \`ffmpeg\` is available in PATH. Set \`ffmpeg-path\` in \`plugins/GovernixMediaFrame/config.yml\` if needed.

Images (PNG/JPG/GIF) do not require FFmpeg.

## Commands

Look directly at the item frame and run:

- \`/mf https://example.com/image.png\` — detect the media format automatically.
- \`/mf image https://example.com/image.gif\` — force image/GIF mode.
- \`/mf video https://example.com/video.mp4\` — force video mode.
- \`/mf clear\` — remove the map and saved media URL from the frame.
- \`/mf info\` — show the URL saved in the targeted frame.

The full command name is \`/mediaframe\`; \`/mf\` is its alias.

## Video limitations

Minecraft maps are 128×128 pixels and are not a native video surface. The plugin extracts frames with FFmpeg and updates the map over time. The default is 6 FPS and a maximum clip length of 30 seconds / 180 frames. The actual visible update rate is also limited by Minecraft map packet updates and client rendering.

Video URLs must be direct HTTP/HTTPS links to a media file that FFmpeg can read (for example MP4 or WebM). YouTube watch pages and sites requiring browser login/cookies are not direct media URLs and are not supported.

## Safety and limits

- Only HTTP/HTTPS URLs are accepted. Local/private network destinations are blocked.
- Default download limit: 25 MB.
- Network loading and video decoding run off the main server thread.
- The media URL is saved on the item frame, so the plugin reloads its display when the containing chunk loads again.
- Permission: \`mediaframe.use\` (default: operators).
