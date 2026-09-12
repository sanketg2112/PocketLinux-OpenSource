# PocketLinux

**PocketLinux** is a rootless GNU/Linux environment runner for Android that lets you run full Linux distributions and desktop environments on your phone or tablet without requiring root privileges.

---

## PocketLinux Application

PocketLinux is available on the Google Play Store:  
**[Get PocketLinux on Google Play Store](https://play.google.com/store/apps/details?id=com.sg.linuxgo)**

---

## Repository Structure

```
├── app/                  # Android application module (Kotlin + Jetpack Compose)
│   ├── src/main/kotlin/  # App orchestration, PRoot process management, X11/Wayland bridge, UI
│   ├── src/main/jniLibs/ # Native Android libraries (arm64-v8a)
│   ├── src/main/assets/  # Bundled runtime assets, shims, scripts
│   └── src/test/         # JVM unit test suite
├── display/              # Native Android Wayland & X server surface display module
├── a11y-fix-plugin/      # Gradle bytecode transform plugin for Android accessibility fixes
├── native/               # C sources for PRoot preload shims
│   ├── link_shim.c
│   ├── pocketlinux_uid_spoof.c
│   └── xbps_extract_shim.c
├── manifest.json         # Container image distribution catalog
├── build.gradle.kts      # Root build configuration
└── settings.gradle.kts   # Module settings
```

---

## Native Binaries & Build Instructions

Compile the native binaries below before building or running the application:

### 1. Custom Syscall Shims (C Sources)

PocketLinux provides three custom C preload shims to overcome Android filesystem and rootless jail constraints:

#### A. Hardlink & Symlink Shim (`liblink_shim.so` / `link_shim.so`)
- **Source**: [`native/link_shim.c`](native/link_shim.c)
- **Purpose**: Intercepts `link()`, `linkat()`, `symlink()`, and `symlinkat()` calls inside PRoot. Android filesystems often reject hardlinks and relative symlinks; this shim translates them into file copies or normalized paths to prevent guest package managers (`dpkg`, `xbps`) from failing.
- **Build Command**:
  ```bash
  # $HOST_TAG is darwin-x86_64 on macOS or linux-x86_64 on Linux
  $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android34-clang \
      -shared -fPIC -O2 -nodefaultlibs \
      -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
      -o liblink_shim.so native/link_shim.c
  ```
- **Target Destinations**:
  - `app/src/main/jniLibs/arm64-v8a/liblink_shim.so`
  - `app/src/main/assets/link_shim.so`

#### B. UID Spoof Shim (`libpocketlinux_uid_spoof.so` / `pocketlinux_uid_spoof.so`)
- **Source**: [`native/pocketlinux_uid_spoof.c`](native/pocketlinux_uid_spoof.c)
- **Purpose**: Spoofs non-root IDs (`getuid`, `geteuid`, `getgid`, `getegid` return 1000) for GUI applications like `pcmanfm-qt` to suppress misleading "Root Instance" warning banners while retaining rootless PRoot superuser capabilities.
- **Build Command**:
  ```bash
  $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android34-clang \
      -shared -fPIC -O2 -nodefaultlibs \
      -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
      -o libpocketlinux_uid_spoof.so native/pocketlinux_uid_spoof.c
  ```
- **Target Destinations**:
  - `app/src/main/jniLibs/arm64-v8a/libpocketlinux_uid_spoof.so`
  - `app/src/main/assets/pocketlinux_uid_spoof.so`

#### C. XBPS Extract Shim (`xbps_extract_shim.so`)
- **Source**: [`native/xbps_extract_shim.c`](native/xbps_extract_shim.c)
- **Purpose**: Preloaded specifically into Void Linux `xbps-install`. Strips `ARCHIVE_EXTRACT_SECURE_SYMLINKS` flags in libarchive that cause relative symlink extraction failures under PRoot.
- **Build Command**:
  ```bash
  $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android34-clang \
      -shared -fPIC -O2 -nodefaultlibs \
      -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
      -o xbps_extract_shim.so native/xbps_extract_shim.c
  ```
- **Target Destination**:
  - `app/src/main/assets/xbps_extract_shim.so`

> **Note on 16 KB Page Size Alignment**: All native shared libraries must be compiled with `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` to ensure compatibility with modern 16 KB page kernels in Android 15+.

---

### 2. Execution Engines

#### A. PRoot & PRoot Loader (`libproot.so`, `libprootloader.so`)
- **Upstream Source**: [Termux PRoot (GitHub)](https://github.com/termux/proot)
- **License**: GPL-2.0
- **Purpose**: Rootless execution engine implementing `chroot`, bind-mounting, and user-space syscall emulation via `ptrace`.
- **How to Build**:
  PRoot is built using the build recipes from [termux-packages](https://github.com/termux/termux-packages):
  ```bash
  # Inside a termux-packages build container:
  ./build-package.sh -a aarch64 proot
  ```
  The generated `proot` executable and `loader` are packaged as shared libraries (`libproot.so` and `libprootloader.so`) with 16 KB ELF alignment and placed in:
  - `app/src/main/jniLibs/arm64-v8a/libproot.so`
  - `app/src/main/jniLibs/arm64-v8a/libprootloader.so`

#### B. tawcroot (`libtawcroot.so`)
- **Upstream Source**: [wmww/tawc (GitHub)](https://github.com/wmww/tawc)
- **License**: GPL-3.0 / MIT
- **Purpose**: High-performance rootless execution sandbox using SECCOMP filters to reduce `ptrace` context-switch overhead.
- **How to Build**:
  Compile with Cargo and Android NDK toolchain targeting `aarch64-linux-android`:
  ```bash
  cargo ndk --target aarch64-linux-android --platform 34 build --release
  ```
  Target destination:
  - `app/src/main/jniLibs/arm64-v8a/libtawcroot.so`

---

### 3. Display Servers & Compositors

#### A. Wayland Compositor (`libwayland_compositor.so`)
- **Component**: Embedded Rust Wayland compositor utilizing Smithay and Android native window bindings.
- **How to Build**:
  Cross-compiled via Cargo NDK targeting `aarch64-linux-android`:
  ```bash
  RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384" \
  cargo ndk -t arm64-v8a -p 34 build --release
  ```
  Target destination:
  - `app/src/main/jniLibs/arm64-v8a/libwayland_compositor.so`

#### B. Embedded X Server
- **Component**: Handled by the [`display/`](display/) module included directly in this repository.
- Built automatically during the standard Gradle build (`./gradlew assembleDebug` or `assembleRelease`) using CMake.

---

### 4. Audio & Background Daemons (Termux Packages)

PocketLinux uses standalone native builds of standard Linux daemons adapted for Android:

#### A. PulseAudio & Audio Modules
- **Upstream Source**: [PulseAudio on Termux Packages](https://github.com/termux/termux-packages/tree/master/packages/pulseaudio)
- **Binaries**:
  - `libpulse.so`, `libpulsecore-17.0.so`, `libpulsecommon-17.0.so`, `libpulseaudio_exec.so`
  - AAudio and TCP modules: `module-aaudio-sink.so`, `module-native-protocol-tcp.so`, `libprotocol-native.so`
- **Audio Codecs**: `libFLAC.so`, `libogg.so`, `libopus.so`, `libvorbis.so`, `libvorbisenc.so`, `libsndfile.so`, `libsoxr.so`, `libspeexdsp.so`, `libmp3lame.so`.
- **Target Destinations**:
  - Core libraries and codecs: `app/src/main/jniLibs/arm64-v8a/`
  - Audio sink modules: `app/src/main/assets/`

#### B. System Utilities & Daemons
- **D-Bus Daemon**: `libdbus-1.so` (from Termux `dbus` package)
- **Toybox**: `libtoybox.so` (from Termux `toybox` package or AOSP toybox)
- **Package Manager & Helper Libs**: `libapk.so`, `libxkbcommon.so`, `libandroid-execinfo.so`, `libtalloc.so`, `libiconv.so`, `libltdl.so`
- **Target Destination**: `app/src/main/jniLibs/arm64-v8a/`

---

### 5. Graphics Acceleration (Libhybris Drivers)

- **Upstream Source**: [libhybris on GitHub](https://github.com/libhybris/libhybris)
- **Purpose**: Connects guest Linux applications to host Android vendor GPU drivers (Bionic to glibc translation).
- **Format**: Packaged as a `.tar` archive containing host EGL and GLES wrapper libraries.
- **Target Destination**: `app/src/main/assets/libhybris/arm64-v8a.tar`

---

## Building the Android Application

### Prerequisites
- **JDK 17** or newer
- **Android SDK** (API level 34 or 35, Build Tools 35.0.0)
- **Android NDK** (version 27+ recommended)
- **CMake** (3.22+)

### 1. Run the Unit Test Suite
```bash
./gradlew :app:testDebugUnitTest
```

### 2. Build Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

---

## Acknowledgements & Thanks

PocketLinux builds upon and is inspired by several open-source projects:

- **[PRoot](https://github.com/termux/proot)** — User-space `chroot`, bind-mounting, and rootless execution sandbox (GPL-2.0).
- **[proot-distro](https://github.com/termux/proot-distro)** — Linux distribution bootstrap and rootfs management.
- **[Termux](https://github.com/termux/termux-packages)** — Android packaging infrastructure; PulseAudio, D-Bus, toybox, and related aarch64 builds.
- **[Termux:X11 / Lorie](https://github.com/termux/termux-x11)** — Embedded X11 display server, input, and Android surface used by the `display/` module.
- **[X.Org](https://www.x.org)** — X server, libX11, pixman, xkb, and related libraries vendored under `display/`.
- **[tawc / tawcroot](https://github.com/wmww/tawc)** — High-performance rootless execution sandbox (Sophie Winter / wmww).
- **[Smithay](https://github.com/Smithay/smithay)** — Wayland compositor toolkit used by the experimental Android Wayland path.
- **[Mesa & Turnip](https://github.com/lfdevs/mesa-for-android-container)** — Guest GPU drivers and acceleration overlays.
- **[Toybox](http://landley.net/toybox)** — Standard command-line utilities used for extract and guest setup.
- **[PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/)** — Host audio daemon (Termux build) bridged into the guest.
- **[D-Bus](https://www.freedesktop.org/wiki/Software/dbus/)** — Session/system bus used with desktop environments.
- **[libhybris](https://github.com/wmww/libhybris)** — Stock-Android GPU translation (wmww fork of libhybris).
- **[Nerd Fonts](https://github.com/ryanoasis/nerd-fonts)** — Patched terminal fonts and symbol fallback bundled in the app.

---

## Container Images & Catalog (Releases)

Prebuilt Linux container images for PocketLinux are published as **GitHub Releases** on this repository.

- **Catalog (manifest):** [`manifest.json`](manifest.json)  
  Raw URL: `https://raw.githubusercontent.com/sanketg2112/PocketLinux-OpenSource/main/manifest.json`
- The app reads this manifest, selects the entry matching the requested distribution, desktop environment, and architecture (`aarch64`), verifies the archive `sha256`, and unpacks the guest rootfs into app storage.
- Container image archives live under **Releases → Assets**.

---

## License

This project is licensed under the **GNU General Public License v2.0 (GPL-2.0)**. See the [`LICENSE`](LICENSE) file for details.

