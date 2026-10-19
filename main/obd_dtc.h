#pragma once

/* OBD-II diagnostic trouble codes (SAE J2012 / ISO 15031-6).
 *
 *   Mode 03 stored, 07 pending, 0A permanent: reply 43/47/4A, then on CAN
 *   a count byte, then two bytes per code.
 *   Mode 04 clears stored codes and freeze frames (reply 44).
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stddef.h>
#include <stdint.h>

#define OBD_DTC_TEXT_LEN 6 /* "P0123" + NUL */

/* Two raw bytes as e.g. "P0123", "C1A20", "U0100". */
void obd_dtc_format(uint16_t raw, char out[OBD_DTC_TEXT_LEN]);

/* Codes from a positive reply starting at the service byte (0x43 0x02 ...).
 * Handles the CAN count byte (even length) and the older layout without
 * it (odd length); zero padding entries are skipped. Returns the number of
 * codes in the reply, storing at most max of them, or -1 when this is not
 * a reply to mode. */
int obd_dtc_parse(const uint8_t *reply, size_t len, uint8_t mode, uint16_t *codes, int max);
