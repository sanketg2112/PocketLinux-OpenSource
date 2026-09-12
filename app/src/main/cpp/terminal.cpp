#include <jni.h>
#include <string>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <sys/wait.h>
#include <android/log.h>
#include <stdlib.h>
#include <signal.h>
#include <dirent.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

#define LOG_TAG "TerminalBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef IUTF8
#define IUTF8 0040000
#endif

static void apply_pty_hygiene(int ptm, int rows, int cols, int xpixel, int ypixel) {
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_xpixel = (unsigned short) (xpixel > 0 ? xpixel : 0);
    ws.ws_ypixel = (unsigned short) (ypixel > 0 ? ypixel : 0);
    ioctl(ptm, TIOCSWINSZ, &ws);
}

static void close_inherited_fds(int keep_fd) {
    DIR *self_dir = opendir("/proc/self/fd");
    if (self_dir == NULL) return;
    int dir_fd = dirfd(self_dir);
    struct dirent *entry;
    while ((entry = readdir(self_dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != dir_fd && fd != keep_fd) {
            close(fd);
        }
    }
    closedir(self_dir);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sg_linuxgo_TerminalBridge_spawnShell(JNIEnv *env, jobject thiz, jstring shell_path,
                                              jstring cwd, jobjectArray args, jobjectArray envp,
                                              jintArray pid_out, jint rows, jint cols,
                                              jint xpixel, jint ypixel) {
    const char *c_shell_path = env->GetStringUTFChars(shell_path, NULL);
    const char *c_cwd = env->GetStringUTFChars(cwd, NULL);

    int args_count = 0;
    if (args != NULL) {
        args_count = env->GetArrayLength(args);
    }

    char **argv = (char **) malloc(sizeof(char *) * (args_count + 2));
    argv[0] = (char *) c_shell_path;
    for (int i = 0; i < args_count; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args, i);
        argv[i + 1] = (char *) env->GetStringUTFChars(arg, NULL);
    }
    argv[args_count + 1] = NULL;

    int env_count = 0;
    if (envp != NULL) {
        env_count = env->GetArrayLength(envp);
    }

    char **env_arr = (char **) malloc(sizeof(char *) * (env_count + 1));
    for (int i = 0; i < env_count; i++) {
        jstring e = (jstring) env->GetObjectArrayElement(envp, i);
        env_arr[i] = (char *) env->GetStringUTFChars(e, NULL);
    }
    env_arr[env_count] = NULL;

    int master_fd = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master_fd < 0) {
        master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    }
    if (master_fd == -1 || grantpt(master_fd) == -1 || unlockpt(master_fd) == -1) {
        LOGE("Failed to open PTY master");
        if (pid_out != NULL && env->GetArrayLength(pid_out) > 0) {
            jint fail = -1;
            env->SetIntArrayRegion(pid_out, 0, 1, &fail);
        }
        return -1;
    }

    char *slave_name = ptsname(master_fd);
    if (slave_name == NULL) {
        LOGE("Failed to get PTY slave name");
        close(master_fd);
        return -1;
    }

    apply_pty_hygiene(master_fd, rows, cols, xpixel, ypixel);

    pid_t pid = fork();
    if (pid == -1) {
        LOGE("Failed to fork");
        close(master_fd);
        return -1;
    }

    if (pid == 0) {
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        setsid();
        int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd == -1) _exit(1);

        ioctl(slave_fd, TIOCSCTTY, 0);
        dup2(slave_fd, 0);
        dup2(slave_fd, 1);
        dup2(slave_fd, 2);
        if (slave_fd > 2) close(slave_fd);
        close(master_fd);

        close_inherited_fds(-1);

        if (chdir(c_cwd) != 0) {
            dprintf(2, "chdir(\"%s\"): %s\n", c_cwd, strerror(errno));
        }

        execve(c_shell_path, argv, env_arr);
        dprintf(2, "exec(\"%s\"): %s\n", c_shell_path, strerror(errno));
        _exit(1);
    }

    if (pid_out != NULL && env->GetArrayLength(pid_out) > 0) {
        jint jpid = (jint) pid;
        env->SetIntArrayRegion(pid_out, 0, 1, &jpid);
    }

    env->ReleaseStringUTFChars(shell_path, c_shell_path);
    env->ReleaseStringUTFChars(cwd, c_cwd);

    for (int i = 0; i < args_count; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(args, i);
        env->ReleaseStringUTFChars(arg, argv[i + 1]);
        env->DeleteLocalRef(arg);
    }
    free(argv);

    for (int i = 0; i < env_count; i++) {
        jstring e = (jstring) env->GetObjectArrayElement(envp, i);
        env->ReleaseStringUTFChars(e, env_arr[i]);
        env->DeleteLocalRef(e);
    }
    free(env_arr);

    return master_fd;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sg_linuxgo_TerminalBridge_waitProcess(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    if (waitpid(pid, &status, 0) < 0) return 1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sg_linuxgo_TerminalBridge_setWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows,
                                                 jint cols, jint xpixel, jint ypixel) {
    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) xpixel;
    ws.ws_ypixel = (unsigned short) ypixel;
    ioctl(fd, TIOCSWINSZ, &ws);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sg_linuxgo_TerminalBridge_setPtyUtf8Mode(JNIEnv *env, jobject thiz, jint fd) {
    struct termios tios;
    if (tcgetattr(fd, &tios) != 0) return;
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(fd, TCSANOW, &tios);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_sg_linuxgo_TerminalBridge_killProcess(JNIEnv *env, jobject thiz, jint pid, jint signal) {
    if (pid <= 0) return -1;
    return kill((pid_t) pid, signal);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sg_linuxgo_TerminalBridge_closeFd(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) close(fd);
}
