/* Host test for main/obd_dtc.c and main/isotp_rx.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_obd_dtc.c main/obd_dtc.c main/isotp_rx.c
 */
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "isotp_rx.h"
#include "obd_dtc.h"

int main(void)
{
    char text[OBD_DTC_TEXT_LEN];
    obd_dtc_format(0x0133, text);
    assert(!strcmp(text, "P0133"));
    obd_dtc_format(0x5A20, text);
    assert(!strcmp(text, "C1A20"));
    obd_dtc_format(0xC100, text);
    assert(!strcmp(text, "U0100"));
    obd_dtc_format(0x9234, text);
    assert(!strcmp(text, "B1234"));

    uint16_t codes[8];
    /* CAN layout: count byte. */
    const uint8_t none[] = { 0x43, 0x00 };
    assert(obd_dtc_parse(none, sizeof(none), 0x03, codes, 8) == 0);
    const uint8_t two[] = { 0x43, 0x02, 0x01, 0x33, 0x03, 0x00 };
    assert(obd_dtc_parse(two, sizeof(two), 0x03, codes, 8) == 2);
    assert(codes[0] == 0x0133 && codes[1] == 0x0300);
    /* Older layout without count, zero padded. */
    const uint8_t legacy[] = { 0x47, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00 };
    assert(obd_dtc_parse(legacy, sizeof(legacy), 0x07, codes, 8) == 1 && codes[0] == 0x0133);
    /* Wrong mode. */
    assert(obd_dtc_parse(two, sizeof(two), 0x07, codes, 8) == -1);
    /* More codes than room: counted, not stored. */
    assert(obd_dtc_parse(two, sizeof(two), 0x03, codes, 1) == 2 && codes[0] == 0x0133);

    /* ISO-TP: single frame. */
    uint8_t buf[64];
    isotp_rx_t rx;
    isotp_rx_init(&rx, buf, sizeof(buf));
    const uint8_t sf[8] = { 0x04, 0x43, 0x01, 0x01, 0x33, 0x55, 0x55, 0x55 };
    assert(isotp_rx_feed(&rx, sf, 8) == ISOTP_RX_DONE && rx.len == 4 && buf[3] == 0x33);

    /* Five codes = 12 bytes: first frame + one consecutive frame. */
    const uint8_t ff[8] = { 0x10, 0x0C, 0x43, 0x05, 0x01, 0x33, 0x01, 0x34 };
    const uint8_t cf[8] = { 0x21, 0x01, 0x35, 0x01, 0x36, 0x01, 0x37, 0xAA };
    assert(isotp_rx_feed(&rx, ff, 8) == ISOTP_RX_NEED_FC);
    assert(isotp_rx_feed(&rx, cf, 8) == ISOTP_RX_DONE && rx.len == 12);
    assert(obd_dtc_parse(buf, rx.len, 0x03, codes, 8) == 5 && codes[4] == 0x0137);

    /* Longer: 20 bytes, two consecutive frames; out of sequence is an error. */
    const uint8_t ff2[8] = { 0x10, 0x14, 0x43, 0x09, 0, 1, 0, 2 };
    const uint8_t cf1[8] = { 0x21, 0, 3, 0, 4, 0, 5, 0 };
    const uint8_t cf2[8] = { 0x22, 6, 0, 7, 0, 8, 0, 9 };
    assert(isotp_rx_feed(&rx, ff2, 8) == ISOTP_RX_NEED_FC);
    assert(isotp_rx_feed(&rx, cf2, 8) == ISOTP_RX_ERROR);
    assert(isotp_rx_feed(&rx, cf1, 8) == ISOTP_RX_IGNORED);
    assert(isotp_rx_feed(&rx, ff2, 8) == ISOTP_RX_NEED_FC);
    assert(isotp_rx_feed(&rx, cf1, 8) == ISOTP_RX_PROGRESS);
    assert(isotp_rx_feed(&rx, cf2, 8) == ISOTP_RX_DONE && rx.len == 20);
    assert(obd_dtc_parse(buf, rx.len, 0x03, codes, 8) == 9);

    /* Too long for the buffer. */
    isotp_rx_t small;
    uint8_t sbuf[10];
    isotp_rx_init(&small, sbuf, sizeof(sbuf));
    assert(isotp_rx_feed(&small, ff2, 8) == ISOTP_RX_ERROR);

    uint8_t fc[8];
    isotp_flow_control(fc, 0x55);
    assert(fc[0] == 0x30 && fc[1] == 0 && fc[2] == 0 && fc[7] == 0x55);
    assert(isotp_physical_request_id(0x7E8, false) == 0x7E0);
    assert(isotp_physical_request_id(0x18DAF110, true) == 0x18DA10F1);

    printf("obd_dtc + isotp_rx OK\n");
    return 0;
}
