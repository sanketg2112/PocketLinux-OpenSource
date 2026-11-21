# Turnip for PocketLinux (DRI3 build)

PocketLinux installs Mesa for Adreno from
[lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container).
Its Turnip (Vulkan) build is KGSL-only, so Mesa's X11 window system code is compiled without
DRI3/Present/MIT-SHM and every Vulkan or Zink frame is read back to the CPU and sent through the
X socket. This directory rebuilds the same source with DRI3 kept on.

- Source: lfdevs tag `turnip-26.2.0-devel-20260709` (commit
  `8a8f9203c767d42d8471bf8c4e2cc3f59f6369c7`), the release PocketLinux ships.
- Meson options: lfdevs's own Turnip options, with `-Dfreedreno-kmds=kgsl,msm` instead of `kgsl`
  (adding msm keeps libdrm, so the X11 WSI gets DRI3; the KGSL backend is still the one used on
  Android) and `-Dcpp_args=-Wno-narrowing` (GCC 14 rejects `append_rate(1, 1, ~0)`).
- `patches/0001-kgsl-allow-khr-display.patch`: a libdrm build advertises `VK_KHR_display`;
  Turnip's KGSL backend refused to enumerate when an app enabled it, so vkcube and apps that
  enable every surface extension found no GPU. KGSL now reports no displays instead.

Built by `.github/workflows/build-turnip.yml` on arm64 runners inside each distro's container
(Debian trixie, Ubuntu noble / questing / resolute, Fedora 43 / 44, Void glibc). A run only adds
assets: one already published is never replaced, since PocketLinux pins each checksum.
Releases are tagged `turnip-<mesa version>-pl<n>`; assets keep lfdevs's tarball layout
(extract to `/`). License: MIT (Mesa).

Measured on a OnePlus OPD2403 (Adreno 750), glmark2 with Zink, windowed / fullscreen:
lfdevs Turnip 202 / 23, this build 392 / 181.
