#include <stdio.h>
#include <unistd.h>
#include <string.h>

/* Minimal link() shim that doesn't depend on external errno symbols.
   It uses rename and symlink to simulate link() in PRoot. */
int link(const char *oldpath, const char *newpath) {
    if (rename(oldpath, newpath) == 0) return 0;
    
    /* If rename fails, try to unlink the target and rename again */
    unlink(newpath);
    if (rename(oldpath, newpath) == 0) return 0;
    
    /* Fallback to symlink */
    if (symlink(oldpath, newpath) == 0) return 0;
    
    /* Final fallback: copy file content */
    FILE *s = fopen(oldpath, "rb");
    FILE *d = fopen(newpath, "wb");
    if (s && d) {
        char b[4096];
        size_t z;
        while ((z = fread(b, 1, sizeof(b), s)) > 0) {
            if (fwrite(b, 1, z, d) != z) break;
        }
        fclose(s);
        fclose(d);
        return 0;
    }
    if (s) fclose(s);
    if (d) fclose(d);
    return -1;
}

/* Compatibility stubs for musl/glibc preloads */
void __register_atfork(void *a, void *b, void *c, void *d) {}
