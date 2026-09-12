/*
 * Preloaded only into Void's glibc xbps-install (not Android processes).
 * Same rules as link_shim: -nodefaultlibs, no DT_NEEDED on Bionic libdl.so
 * (that name does not exist in the guest — crash: "libdl.so: cannot open").
 * dlsym is left undefined and binds to guest libc when xbps-install starts.
 */
void *dlsym(void *handle, const char *symbol);
#define RTLD_NEXT ((void *)-1L)
#define RTLD_DEFAULT ((void *)0)
#define ARCHIVE_EXTRACT_SECURE_SYMLINKS 0x0100
#define ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS 0x10000

static int pl_drop_secure(int flags) {
    return flags & (int)~(ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS);
}

int archive_write_disk_set_options(void *a, int flags) {
    static int (*real)(void *, int);
    if (!real) {
        real = (int (*)(void *, int))dlsym(RTLD_NEXT, "archive_write_disk_set_options");
        if (!real) real = (int (*)(void *, int))dlsym(RTLD_DEFAULT, "archive_write_disk_set_options");
    }
    if (!real) return 0;
    return real(a, pl_drop_secure(flags));
}

int archive_read_extract(void *a, void *e, int flags) {
    static int (*real)(void *, void *, int);
    if (!real) {
        real = (int (*)(void *, void *, int))dlsym(RTLD_NEXT, "archive_read_extract");
        if (!real) real = (int (*)(void *, void *, int))dlsym(RTLD_DEFAULT, "archive_read_extract");
    }
    if (!real) return -1;
    return real(a, e, pl_drop_secure(flags));
}

void __register_atfork(void *a, void *b, void *c, void *d) {}
