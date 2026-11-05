<template>
  <router-view />
</template>

<style>
:root {
  --bg: #0f1419;
  --panel: #1a222c;
  --text: #e7eef7;
  --muted: #8aa0b5;
  --accent: #3d8bfd;
  --card-continue-accent: #86efac;
  --card-suspend-accent: #fbbf24;
}
* { box-sizing: border-box; }
html, body, #app { height: 100%; margin: 0; }
body {
  font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
  background: radial-gradient(1200px 600px at 10% -10%, #1b2a3d, var(--bg));
  color: var(--text);
}
</style>
