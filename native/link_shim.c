#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

/*
 * Hardlink shim for Android/PRoot guests (LD_PRELOAD into guest ELF only).
 *
 * Critical rules:
 *  - NEVER reference errno/__errno (breaks musl/glibc mismatch -> every binary dies)
 *  - NEVER rename() - dpkg does link(status, status-old); rename would delete status
 *  - Built with -nodefaultlibs; open/read/write resolve from the preloaded process
 */
#define PL_AT_FDCWD ((long)-100)
#define PL_SYS_MKDIRAT 34
#define PL_SYS_UNLINKAT 35
#define PL_SYS_SYMLINKAT 36

static long pl_sys3(long n, long a, long b, long c) {
    register long x8 asm("x8") = n;
    register long x0 asm("x0") = a;
    register long x1 asm("x1") = b;
    register long x2 asm("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static void pl_mkdir_p(const char *dir) {
    char tmp[4096];
    size_t i, m = 0;
    if (!dir || !dir[0]) return;
    for (i = 0; dir[i] && m < sizeof(tmp) - 1; i++) {
        tmp[m++] = dir[i];
        tmp[m] = 0;
        if (dir[i] == '/' && m > 1) pl_sys3(PL_SYS_MKDIRAT, PL_AT_FDCWD, (long)tmp, 0755);
    }
    pl_sys3(PL_SYS_MKDIRAT, PL_AT_FDCWD, (long)tmp, 0755);
}

static void pl_parent(const char *path, char *out, size_t cap) {
    size_t n = 0, last = 0;
    if (!path || cap == 0) { if (cap) out[0] = 0; return; }
    while (path[n] && n + 1 < cap) {
        if (path[n] == '/') last = n;
        out[n] = path[n];
        n++;
    }
    out[last] = 0;
}

/* xbps unpacks as ./usr/lib/... or usr/lib/... after libarchive cleanup. Treat as from /. */
static int pl_make_abs(const char *path, char *out, size_t cap) {
    size_t i = 0, j = 0;
    if (!path || cap < 2) return -1;
    if (path[0] == '/') {
        while (path[i] && j + 1 < cap) out[j++] = path[i++];
        out[j] = 0;
        return 0;
    }
    out[j++] = '/';
    if (path[0] == '.' && path[1] == '/') i = 2;
    while (path[i] && j + 1 < cap) out[j++] = path[i++];
    out[j] = 0;
    return 0;
}

/* Copy src -> dst (follows src if it is a symlink). Used when PRoot rejects link/symlink. */
static int pl_copy_file(const char *src, const char *dst) {
    struct stat st;
    int infd, outfd, ok;
    char buf[8192];
    ssize_t n;
    if (!src || !dst) return -1;
    if (lstat(src, &st) != 0) return -1;
    if (S_ISDIR(st.st_mode)) return -1;
    unlink(dst);
    infd = open(src, O_RDONLY);
    if (infd < 0) return -1;
    outfd = open(dst, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 0777);
    if (outfd < 0) { close(infd); return -1; }
    ok = 1;
    while ((n = read(infd, buf, sizeof(buf))) > 0) {
        char *p = buf;
        ssize_t left = n;
        while (left > 0) {
            ssize_t w = write(outfd, p, (size_t)left);
            if (w <= 0) { ok = 0; break; }
            p += w;
            left -= w;
        }
        if (!ok) break;
    }
    if (n < 0) ok = 0;
    close(infd);
    if (close(outfd) != 0) ok = 0;
    if (ok) {
        chmod(dst, st.st_mode & 0777);
        return 0;
    }
    unlink(dst);
    return -1;
}

int link(const char *oldpath, const char *newpath) {
    if (oldpath == 0 || newpath == 0) return -1;
    return pl_copy_file(oldpath, newpath);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    (void)olddirfd; (void)newdirfd; (void)flags;
    return link(oldpath, newpath);
}

/*
 * xbps/libarchive extracts soname links with symlink(target, "./usr/lib/libfoo.so.1").
 * PRoot often returns ENOENT (relative target resolved from cwd, not the link dir).
 * Retry as absolute paths; if that still fails, copy the real library (Firefox
 * only needs libatomic.so.1 to exist as a loadable ELF).
 */
int symlink(const char *target, const char *linkpath) {
    char parent[4096], abs_l[4096], abs_t[4096];
    long r;
    size_t i, j;
    if (!target || !linkpath) return -1;
    pl_parent(linkpath, parent, sizeof(parent));
    if (parent[0]) pl_mkdir_p(parent);
    pl_sys3(PL_SYS_UNLINKAT, PL_AT_FDCWD, (long)linkpath, 0);
    r = pl_sys3(PL_SYS_SYMLINKAT, (long)target, PL_AT_FDCWD, (long)linkpath);
    if (r >= 0) return 0;

    if (pl_make_abs(linkpath, abs_l, sizeof(abs_l)) != 0) return -1;
    pl_parent(abs_l, parent, sizeof(parent));
    if (parent[0]) pl_mkdir_p(parent);
    if (target[0] == '/') {
        if (pl_make_abs(target, abs_t, sizeof(abs_t)) != 0) return -1;
    } else {
        j = 0;
        while (parent[j] && j + 2 < sizeof(abs_t)) { abs_t[j] = parent[j]; j++; }
        abs_t[j++] = '/';
        i = 0;
        if (target[0] == '.' && target[1] == '/') i = 2;
        while (target[i] && j + 1 < sizeof(abs_t)) abs_t[j++] = target[i++];
        abs_t[j] = 0;
    }
    pl_sys3(PL_SYS_UNLINKAT, PL_AT_FDCWD, (long)abs_l, 0);
    r = pl_sys3(PL_SYS_SYMLINKAT, (long)abs_t, PL_AT_FDCWD, (long)abs_l);
    if (r >= 0) return 0;
    if (pl_copy_file(abs_t, abs_l) == 0) return 0;
    if (pl_copy_file(target, abs_l) == 0) return 0;
    return pl_copy_file(target, linkpath);
}

int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    long r;
    if (!target || !linkpath) return -1;
    if (newdirfd == (int)PL_AT_FDCWD)
        return symlink(target, linkpath);
    r = pl_sys3(PL_SYS_SYMLINKAT, (long)target, (long)newdirfd, (long)linkpath);
    if (r >= 0) return 0;
    return symlink(target, linkpath);
}

void __register_atfork(void *a, void *b, void *c, void *d) {}

