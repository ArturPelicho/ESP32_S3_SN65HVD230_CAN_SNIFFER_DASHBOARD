#include "isotp_rx.h"

#include <string.h>

void isotp_rx_init(isotp_rx_t *rx, uint8_t *buf, size_t size)
{
    rx->buf = buf;
    rx->size = size;
    rx->len = 0;
    rx->expected = 0;
    rx->next_sn = 0;
    rx->active = false;
}

static void store(isotp_rx_t *rx, const uint8_t *data, size_t n)
{
    size_t room = rx->expected - rx->len;
    if (n > room) n = room;
    memcpy(rx->buf + rx->len, data, n);
    rx->len += n;
}

isotp_rx_result_t isotp_rx_feed(isotp_rx_t *rx, const uint8_t *data, uint8_t len)
{
    if (len < 2) return ISOTP_RX_IGNORED;
    switch (data[0] >> 4) {
    case 0: { /* single frame */
        size_t n = data[0] & 0x0F;
        if (n == 0 || n > (size_t)len - 1u || n > rx->size) return ISOTP_RX_IGNORED;
        rx->active = false;
        rx->expected = n;
        rx->len = 0;
        store(rx, data + 1, n);
        return ISOTP_RX_DONE;
    }
    case 1: { /* first frame */
        size_t n = ((size_t)(data[0] & 0x0F) << 8) | data[1];
        if (n <= 7 || len < 8) return ISOTP_RX_IGNORED;
        if (n > rx->size) {
            rx->active = false;
            return ISOTP_RX_ERROR;
        }
        rx->active = true;
        rx->expected = n;
        rx->len = 0;
        rx->next_sn = 1;
        store(rx, data + 2, 6);
        return ISOTP_RX_NEED_FC;
    }
    case 2: { /* consecutive frame */
        if (!rx->active) return ISOTP_RX_IGNORED;
        if ((data[0] & 0x0F) != rx->next_sn) {
            rx->active = false;
            return ISOTP_RX_ERROR;
        }
        rx->next_sn = (uint8_t)((rx->next_sn + 1) & 0x0F);
        store(rx, data + 1, (size_t)len - 1u);
        if (rx->len < rx->expected) return ISOTP_RX_PROGRESS;
        rx->active = false;
        return ISOTP_RX_DONE;
    }
    default:
        return ISOTP_RX_IGNORED; /* flow control from someone else */
    }
}

void isotp_flow_control(uint8_t out[8], uint8_t pad)
{
    memset(out, pad, 8);
    out[0] = 0x30;
    out[1] = 0x00;
    out[2] = 0x00;
}

uint32_t isotp_physical_request_id(uint32_t response_id, bool extended)
{
    if (!extended) return response_id - 8u;
    return (response_id & 0xFFFF0000u) | ((response_id & 0xFFu) << 8) | ((response_id >> 8) & 0xFFu);
}
