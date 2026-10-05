/* Host test for main/pid_scan.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_pid_scan.c main/pid_scan.c
 */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "pid_scan.h"

static char s_report[16384];

static void collect(const char *line, void *ctx)
{
    (void)ctx;
    assert(strlen(line) < 160);
    strcat(s_report, line);
    strcat(s_report, "\n");
}

static void reply(pid_scan_t *scan, uint32_t id, uint8_t pid, const uint8_t *value, uint8_t n,
                  uint32_t ms)
{
    uint8_t frame[8] = { (uint8_t)(n + 2), 0x41, pid, 0x55, 0x55, 0x55, 0x55, 0x55 };
    memcpy(&frame[3], value, n);
    pid_scan_on_frame(scan, id, false, frame, 8, ms);
}

static void ask(pid_scan_t *scan, uint8_t pid, bool answered)
{
    pid_scan_on_ask(scan, pid);
    pid_scan_on_done(scan, pid, answered);
}

static void expect(const char *needle)
{
    if (strstr(s_report, needle) == NULL) {
        printf("missing \"%s\" in report:\n%s", needle, s_report);
        assert(0);
    }
}

int main(void)
{
    pid_scan_config_t cfg = { .request_id = 0x7DF, .response_id_min = 0x7E8, .response_id_max = 0x7EF };
    static pid_scan_t scan;
    pid_scan_init(&scan, &cfg, 1000);

    /* 0100 claims 0C, 0D and 20 (next bitmap), 0120 claims 2F. */
    pid_scan_on_ask(&scan, 0x00);
    reply(&scan, 0x7E8, 0x00, (const uint8_t[]){ 0x00, 0x18, 0x00, 0x01 }, 4, 1010);
    pid_scan_on_done(&scan, 0x00, true);
    pid_scan_on_ask(&scan, 0x20);
    reply(&scan, 0x7E8, 0x20, (const uint8_t[]){ 0x00, 0x02, 0x00, 0x00 }, 4, 1020);
    pid_scan_on_done(&scan, 0x20, true);
    assert(pid_scan_pid_claimed(&scan, 0x0C));
    assert(pid_scan_pid_claimed(&scan, 0x0D));
    assert(pid_scan_pid_claimed(&scan, 0x20));
    assert(pid_scan_pid_claimed(&scan, 0x2F));
    assert(!pid_scan_pid_claimed(&scan, 0x0B));
    assert(!pid_scan_pid_claimed(&scan, 0x40));

    /* RPM moves, speed answers but stays 0, 2F stays silent, 22 is refused,
     * 5C answers without being claimed, and a second ECU answers 0D too. */
    for (int i = 0; i < 5; ++i) {
        pid_scan_on_ask(&scan, 0x0C);
        reply(&scan, 0x7E8, 0x0C, (const uint8_t[]){ (uint8_t)(0x0B + i), 0x40 }, 2, 1100 + i);
        pid_scan_on_done(&scan, 0x0C, true);
        pid_scan_on_ask(&scan, 0x0D);
        reply(&scan, 0x7E8, 0x0D, (const uint8_t[]){ 0x00 }, 1, 1100 + i);
        pid_scan_on_done(&scan, 0x0D, true);
    }
    reply(&scan, 0x7E9, 0x0D, (const uint8_t[]){ 0x00 }, 1, 1200);
    ask(&scan, 0x2F, false);
    ask(&scan, 0x2F, false);
    pid_scan_on_ask(&scan, 0x22);
    pid_scan_on_frame(&scan, 0x7E8, false, (const uint8_t[]){ 0x03, 0x7F, 0x01, 0x12, 0, 0, 0, 0 }, 8, 1300);
    pid_scan_on_done(&scan, 0x22, false);
    pid_scan_on_ask(&scan, 0x5C);
    reply(&scan, 0x7E8, 0x5C, (const uint8_t[]){ 0x82 }, 1, 1400);
    pid_scan_on_done(&scan, 0x5C, true);

    assert(scan.pids[0x2F].silent == 2 && scan.pids[0x2F].asks == 2);
    assert(scan.pids[0x22].negative == 1 && scan.pids[0x22].silent == 0);
    assert(scan.pids[0x22].last_nrc == 0x12);
    assert(scan.pids[0x0C].bytes.changes[0] == 4 && scan.pids[0x0C].bytes.changes[1] == 0);
    assert(scan.pids[0x0C].bytes.min[0] == 0x0B && scan.pids[0x0C].bytes.max[0] == 0x0F);
    assert(scan.pids[0x0D].responders == 0x03);

    /* Broadcast traffic: an ID with a counter byte, plus our own request
     * on 7DF which must not be tallied. */
    for (int i = 0; i < 11; ++i) {
        uint8_t data[8] = { 0x10, (uint8_t)i, 0, 0, 0, 0, 0, 0x99 };
        pid_scan_on_frame(&scan, 0x120, false, data, 8, 2000 + (uint32_t)i * 10);
    }
    pid_scan_on_frame(&scan, 0x7DF, false, (const uint8_t[]){ 2, 1, 0x0D, 0, 0, 0, 0, 0 }, 8, 2000);
    pid_scan_on_frame(&scan, 0x18FF0001, true, (const uint8_t[]){ 1, 2 }, 2, 2000);
    assert(scan.id_count == 2);
    assert(scan.ids[0].count == 11 && scan.ids[0].bytes.changes[1] == 10);

    pid_scan_report(&scan, 31000, collect, NULL);
    expect("t=30 s");
    expect("Bitmaps answered (2): 00 20");
    expect("Answered (3): 0C 0D 5C");
    expect("Claimed but never answered (1): 2F");
    expect("Answered but not claimed (1): 5C");
    expect("Negative response (1): 22(NRC 12)");
    expect("PID 0C RPM     7E8 Y 5/5 B0 0F[0B-0F x4] B1 40[fixed]  = 976 rpm");
    expect("PID 0D SPEED   7E8+7E9 Y 6/5 B0 00[fixed]  = 0 km/h");
    expect("PID 5C OILTEMP 7E8 N 1/1 B0 82[fixed]  = 90 C");
    expect("ID 120 n=11 every 10 ms B0 10[fixed] B1 0A[00-0A x10]");
    expect("    B4 00[fixed] B5 00[fixed] B6 00[fixed] B7 99[fixed]");
    expect("ID 18FF0001 n=1 every 0 ms");
    assert(strstr(s_report, "ID 7DF") == NULL);

    /* Table overflow is counted, not silently lost. */
    for (uint32_t id = 0x200; id < 0x200 + PID_SCAN_MAX_IDS; ++id) {
        pid_scan_on_frame(&scan, id, false, (const uint8_t[]){ 0 }, 1, 3000);
    }
    assert(scan.id_count == PID_SCAN_MAX_IDS && scan.ids_overflow == 2);

    printf("%s", s_report);
    printf("pid_scan: all tests passed\n");
    return 0;
}
