#include "obd_dtc.h"

#include <stdio.h>

void obd_dtc_format(uint16_t raw, char out[OBD_DTC_TEXT_LEN])
{
    static const char system[4] = { 'P', 'C', 'B', 'U' };
    snprintf(out, OBD_DTC_TEXT_LEN, "%c%u%03X", system[raw >> 14], (unsigned)((raw >> 12) & 3),
             (unsigned)(raw & 0x0FFF));
}

int obd_dtc_parse(const uint8_t *reply, size_t len, uint8_t mode, uint16_t *codes, int max)
{
    if (len < 1 || reply[0] != (uint8_t)(mode + 0x40)) return -1;
    size_t at = (len % 2 == 0) ? 2 : 1; /* skip the CAN count byte */
    int n = 0;
    for (; at + 1 < len; at += 2) {
        uint16_t raw = (uint16_t)((reply[at] << 8) | reply[at + 1]);
        if (raw == 0) continue;
        if (n < max && codes != NULL) codes[n] = raw;
        ++n;
    }
    return n;
}
