# Bloxorz Mobile

Android WebView wrapper for https://bloxorz.io/.

## v0.1.1

- Loads Bloxorz directly over HTTPS in Android System WebView (Chromium-based).
- Keeps WebView cookies, localStorage, IndexedDB and cache inside the app profile.
- Native touch controls: Up / Down / Left / Right / Space.
- Controls are translucent.
- Short tap sends the game key.
- Long-press (~450 ms) enters drag mode.
- Dragging any direction button moves the whole D-pad.
- SPACE can be moved independently.
- Control positions are saved and restored on the next launch.
- Landscape immersive full-screen mode.

The website currently runs the Flash game through Ruffle and loads:
- /game/bloxors/ruffle/ruffle.js
- /game/bloxors/bloxors.swf
