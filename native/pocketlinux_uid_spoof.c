/*
 * PocketLinux: spoof non-root ids for GUI apps only (LD_PRELOAD into pcmanfm-qt etc.).
 * Under proot -0, real euid is 0 so pcmanfm-qt paints a red "Root Instance" banner
 * (mainwindow.cpp: if (geteuid() == 0)). Returning 1000 hides that without dropping
 * actual superuser capability (kernel still sees the proot -0 process).
 *
 * Same rules as link_shim: no errno, -nodefaultlibs, resolve symbols from host.
 */
unsigned int getuid(void)  { return 1000; }
unsigned int geteuid(void) { return 1000; }
unsigned int getgid(void)  { return 1000; }
unsigned int getegid(void) { return 1000; }
/* 32-bit ABI aliases some platforms use */
int __getuid(void)  { return 1000; }
int __geteuid(void) { return 1000; }
void __register_atfork(void *a, void *b, void *c, void *d) {}
