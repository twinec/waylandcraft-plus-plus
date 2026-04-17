# waylandcraft
Wayland Compositor in Minecraft

## Implemented protocols
- core
	- wl_compositor
	- wl_subcompositor
	- wl_data_device_manager
	- wl_shm
	- wl_seat *(pointer, keyboard)*
	- wl_output
- xdg-shell
- viewporter
- single-pixel-buffer-v1
- linux-dmabuf-v1
- cursor-shape-v1 *(partially)*
- pointer-constraints-unstable-v1 *(only locked pointers)*
- relative-pointer-unstable-v1

## System dependencies
- OS: Linux
- Minecraft 1.20.6
- Fabric mod loader
- xkbcommon library 1.11.0
- xkbcommon tools (xkbcli)

## Disclaimer
This compositor still has lots of issues and bugs. Use it at your own risk or whatever.

The entire project was written **without the usage of any generative AI**.
