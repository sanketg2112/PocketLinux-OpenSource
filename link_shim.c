#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

/*
 * Hardlink shim for Android/PRoot guests (LD_PRELOAD into guest ELF only).
 * Never reference errno/__errno. Never rename() (breaks dpkg status backup).
 */
int link(const char *oldpath, const char *newpath) {
    struct stat st;
    if (oldpath == 0 || newpath == 0) return -1;
    if (lstat(oldpath, &st) != 0) return -1;
    if (S_ISDIR(st.st_mode)) return -1;

    unlink(newpath);

    int src = open(oldpath, O_RDONLY);
    if (src < 0) {
        if (symlink(oldpath, newpath) == 0) return 0;
        return -1;
    }
    int dst = open(newpath, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777);
    if (dst < 0) {
        close(src);
        if (symlink(oldpath, newpath) == 0) return 0;
        return -1;
    }

    char buf[8192];
    ssize_t n;
    int ok = 1;
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        char *p = buf;
        ssize_t left = n;
        while (left > 0) {
            ssize_t w = write(dst, p, (size_t)left);
            if (w <= 0) { ok = 0; break; }
            p += w;
            left -= w;
        }
        if (!ok) break;
    }
    if (n < 0) ok = 0;
    close(src);
    if (close(dst) != 0) ok = 0;
    if (ok) {
        chmod(newpath, st.st_mode & 0777);
        return 0;
    }
    unlink(newpath);
    if (symlink(oldpath, newpath) == 0) return 0;
    return -1;
}

void __register_atfork(void *a, void *b, void *c, void *d) {}
