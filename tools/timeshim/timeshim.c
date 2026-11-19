/*
 * libwinlator_timeshim.so - x86_64 library preloaded into Wine through
 * BOX64_LD_PRELOAD when the container has a custom date/time.
 *
 * Android does not let an app change the system clock, so the date and time
 * are shifted only for Wine: the wall-clock functions (clock_gettime with a
 * real-time clock, gettimeofday, time) return the real time plus
 * WINLATOR_TIME_OFFSET seconds. Monotonic clocks are left alone, so timers,
 * sleeps and frame pacing keep working normally.
 *
 * Freestanding: no libc headers, raw x86_64 syscalls (Box64 translates them).
 */

#define SYS_read 0
#define SYS_open 2
#define SYS_close 3
#define SYS_gettimeofday 96
#define SYS_clock_gettime 228

#define CLOCK_REALTIME 0
#define CLOCK_REALTIME_COARSE 5
#define CLOCK_REALTIME_ALARM 8
#define CLOCK_TAI 11

struct timespec { long tv_sec; long tv_nsec; };
struct timeval { long tv_sec; long tv_usec; };

static long sys3(long n, long a, long b, long c) {
    long ret;
    __asm__ volatile ("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return ret;
}

static volatile int initialized;
static long offset_sec;

static int starts_with(const char *s, const char *p) {
    while (*p) if (*s++ != *p++) return 0;
    return 1;
}

static void init(void) {
    static char buf[32768];
    long fd, n, total = 0, i;
    if (initialized) return;
    fd = sys3(SYS_open, (long)"/proc/self/environ", 0, 0);
    if (fd >= 0) {
        while (total < (long)sizeof(buf) - 1 && (n = sys3(SYS_read, fd, (long)(buf + total), sizeof(buf) - 1 - total)) > 0) total += n;
        sys3(SYS_close, fd, 0, 0);
        buf[total] = 0;
        for (i = 0; i < total; i++) {
            if ((i == 0 || buf[i - 1] == 0) && starts_with(buf + i, "WINLATOR_TIME_OFFSET=")) {
                const char *p = buf + i + 21;
                long v = 0, neg = 0;
                if (*p == '-') { neg = 1; p++; }
                else if (*p == '+') p++;
                while (*p >= '0' && *p <= '9') v = v * 10 + (*p++ - '0');
                offset_sec = neg ? -v : v;
                break;
            }
        }
    }
    initialized = 1;
}

static int is_wall_clock(int clk) {
    return clk == CLOCK_REALTIME || clk == CLOCK_REALTIME_COARSE || clk == CLOCK_REALTIME_ALARM || clk == CLOCK_TAI;
}

int clock_gettime(int clk, struct timespec *ts) {
    long r = sys3(SYS_clock_gettime, clk, (long)ts, 0);
    if (r < 0) return -1;
    init();
    if (ts && offset_sec && is_wall_clock(clk)) ts->tv_sec += offset_sec;
    return 0;
}

int __clock_gettime(int clk, struct timespec *ts) {
    return clock_gettime(clk, ts);
}

int gettimeofday(struct timeval *tv, void *tz) {
    long r = sys3(SYS_gettimeofday, (long)tv, (long)tz, 0);
    if (r < 0) return -1;
    init();
    if (tv && offset_sec) tv->tv_sec += offset_sec;
    return 0;
}

long time(long *t) {
    struct timespec ts;
    long now;
    if (sys3(SYS_clock_gettime, CLOCK_REALTIME, (long)&ts, 0) < 0) return -1;
    init();
    now = ts.tv_sec + offset_sec;
    if (t) *t = now;
    return now;
}
