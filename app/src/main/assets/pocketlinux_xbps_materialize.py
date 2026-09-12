#!/usr/bin/env python3
# PocketLinux xbps materialize v3
"""Copy xbps soname links as regular files so PRoot unpack can skip symlink().

xbps archives are zstd-compressed tar. Void often has libzstd (used by xbps)
without the `zstd` CLI. Prefer Python 3.14 compression.zstd, then a zstd binary.
"""
import glob
import os
import shutil
import subprocess
import sys
import tarfile

SKIP = {
    "./props.plist",
    "./files.plist",
    "./INSTALL",
    "./REMOVE",
    "props.plist",
    "files.plist",
    "INSTALL",
    "REMOVE",
}

ZSTD_PATHS = (
    "/usr/bin/zstd",
    "/bin/zstd",
    "/usr/local/bin/zstd",
    "/usr/bin/zstdcat",
    "/bin/zstdcat",
)


def guest_path(name):
    n = name[1:] if name.startswith("./") else name
    if not n.startswith("/"):
        n = "/" + n
    return n


def dest_path(root, gpath):
    if root == "/":
        return gpath
    return root.rstrip("/") + gpath


def replace_with_copy(src, dest):
    """Copy src to dest as a regular file. dest may already be a symlink to src."""
    if os.path.lexists(dest):
        os.unlink(dest)
    shutil.copyfile(src, dest)


def open_zstd_reader(path):
    """Return (fileobj, closer). Never assumes `zstd` is on PATH."""
    try:
        from compression.zstd import ZstdFile

        handle = ZstdFile(path, "rb")
        return handle, handle.close
    except Exception:
        pass
    try:
        import zstandard

        raw = open(path, "rb")
        reader = zstandard.ZstdDecompressor().stream_reader(raw)

        def close_zstd():
            try:
                reader.close()
            finally:
                raw.close()

        return reader, close_zstd
    except Exception:
        pass
    cmd = None
    found = shutil.which("zstd") or shutil.which("zstdcat")
    if found:
        if os.path.basename(found) == "zstdcat":
            cmd = [found, path]
        else:
            cmd = [found, "-d", "-c", path]
    if cmd is None:
        for candidate in ZSTD_PATHS:
            if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                if candidate.endswith("zstdcat"):
                    cmd = [candidate, path]
                else:
                    cmd = [candidate, "-d", "-c", path]
                break
    if cmd is None:
        raise FileNotFoundError(
            "cannot decompress xbps: need Python 3.14 compression.zstd "
            "or the zstd program (xbps does not ship the CLI)"
        )
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)

    def close_proc():
        if proc.stdout:
            proc.stdout.close()
        proc.wait()

    return proc.stdout, close_proc


def materialize(pkg, root, noextract):
    pending = []
    tf = None
    closer = None
    try:
        zf, closer = open_zstd_reader(pkg)
        tf = tarfile.open(fileobj=zf, mode="r|")
        for member in tf:
            name = member.name or ""
            if name in SKIP or name.rstrip("/") in SKIP:
                continue
            if member.isdir():
                continue
            gpath = guest_path(name)
            dest = dest_path(root, gpath)
            if member.issym() or member.islnk():
                pending.append((dest, member.linkname or "", gpath))
                noextract.append(gpath)
                continue
            if not member.isfile():
                continue
            parent = os.path.dirname(dest)
            if parent:
                os.makedirs(parent, exist_ok=True)
            src = tf.extractfile(member)
            if src is None:
                continue
            if os.path.lexists(dest):
                os.unlink(dest)
            with open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
            mode = member.mode & 0o777 if member.mode else 0o644
            try:
                os.chmod(dest, mode or 0o644)
            except OSError:
                pass
    except tarfile.ReadError:
        pass
    finally:
        if tf is not None:
            try:
                tf.close()
            except Exception:
                pass
        if closer is not None:
            try:
                closer()
            except Exception:
                pass
    for dest, linkname, _gpath in pending:
        parent = os.path.dirname(dest)
        if parent:
            os.makedirs(parent, exist_ok=True)
        if linkname.startswith("/"):
            target = dest_path(root, linkname)
        else:
            target = os.path.join(parent or "/", linkname)
        if not os.path.isfile(target):
            continue
        try:
            replace_with_copy(target, dest)
            os.chmod(dest, 0o755)
        except OSError:
            pass


def main(argv):
    root = "/"
    cache = "/var/cache/xbps"
    conf = "/etc/xbps.d/00-pocketlinux-noextract.conf"
    pkgs = []
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--root" and i + 1 < len(argv):
            root = argv[i + 1]
            i += 2
            continue
        if arg == "--cache" and i + 1 < len(argv):
            cache = argv[i + 1]
            i += 2
            continue
        if arg == "--conf" and i + 1 < len(argv):
            conf = argv[i + 1]
            i += 2
            continue
        if not arg.startswith("-"):
            pkgs.append(arg)
        i += 1
    if not pkgs:
        pkgs = sorted(glob.glob(os.path.join(cache, "*.xbps")))
    noextract = []
    for pkg in pkgs:
        if not os.path.isfile(pkg):
            continue
        try:
            materialize(pkg, root, noextract)
        except Exception as exc:
            sys.stderr.write("materialize %s: %s\n" % (pkg, exc))
    if conf:
        parent = os.path.dirname(conf)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(conf, "w") as out:
            for path in noextract:
                out.write("noextract=%s\n" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
