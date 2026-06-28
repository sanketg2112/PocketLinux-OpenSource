# PocketLinux Open-Source Compliance & PRoot Engine Code

This repository contains the necessary open-source license, references, and custom shim source codes to adhere to the **GNU General Public License (GPL) version 2.0** for the native binaries shipped with the PocketLinux Android application.

## 📱 PocketLinux Application
PocketLinux is available on the Google Play Store:
👉 **[Get PocketLinux on Google Play Store](https://play.google.com/store/apps/details?id=com.sg.linuxgo)**

---

## ⚖️ Open-Source Components & Upstream References

The PocketLinux application bundles precompiled binaries that are subject to copyleft licensing. Below is the documentation and references to retrieve the exact corresponding source code:

### 1. PRoot Engine & Loader (`libproot.so` & `libprootloader.so`)
* **Upstream Source Code**: [Termux PRoot on GitHub](https://github.com/termux/proot)
* **License**: GNU General Public License v2.0 (GPL-2.0)
* **Description**: A user-space implementation of `chroot`, `mount --bind`, and `chroot` using `ptrace`. This tool is used as the execution jail environment to run guest Linux rootfs filesystems on Android without root privileges.
* **Building**: Built using the Termux PRoot build scripts for Android.

### 2. Custom Syscall Shim (`liblink_shim.so`)
* **Source Code**: Included locally in this repository as [link_shim.c](link_shim.c)
* **License**: GNU General Public License v2.0 (GPL-2.0)
* **Description**: A lightweight preloaded C shim library that intercepts `link()` system calls inside PRoot. Because Android filesystem partitions typically do not support hardlinks, this shim translates hardlink requests into file renames, symbolic links, or file copying. This prevents crashes in guest Linux software (e.g., socket lock file creation).
* **Building**: Can be cross-compiled for Android `arm64-v8a` using the Android NDK toolchain clang. For example:
  ```bash
  $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang \
      -shared -fPIC -O2 -nodefaultlibs \
      -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
      -o liblink_shim.so link_shim.c
  ```
