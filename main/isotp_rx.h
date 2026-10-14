#pragma once

/* ISO 15765-2 (ISO-TP) receive side, for diagnostic replies longer than one
 * CAN frame (e.g. Mode 03 with more than two fault codes).
 *
 * Feed every frame from the responding ECU; on ISOTP_RX_NEED_FC send
 * isotp_flow_control() to that ECU's physical request ID so it sends the
 * rest. Only the receive side: requests always fit one frame.
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef enum {
    ISOTP_RX_IGNORED,   /* not an ISO-TP frame we expected */
    ISOTP_RX_PROGRESS,  /* consecutive frame stored, more to come */
    ISOTP_RX_NEED_FC,   /* first frame stored: send a flow control now */
    ISOTP_RX_DONE,      /* message complete in buf[0..len) */
    ISOTP_RX_ERROR,     /* sequence error or message too long; reset */
} isotp_rx_result_t;

typedef struct {
    uint8_t *buf;
    size_t size;
    size_t len;
    size_t expected;
    uint8_t next_sn;
    bool active;        /* between first frame and the last consecutive frame */
} isotp_rx_t;

void isotp_rx_init(isotp_rx_t *rx, uint8_t *buf, size_t size);
isotp_rx_result_t isotp_rx_feed(isotp_rx_t *rx, const uint8_t *data, uint8_t len);

/* "Continue to send", no block limit, no separation time. */
void isotp_flow_control(uint8_t out[8], uint8_t pad);

/* Physical request ID for a response ID: 0x7E8 -> 0x7E0, and for 29-bit
 * 0x18DAF1xx -> 0x18DAxxF1. */
uint32_t isotp_physical_request_id(uint32_t response_id, bool extended);
