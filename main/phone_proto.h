#pragma once

/* Binary packets between the dashboard and the phone app over BLE.
 *
 * Service  6d6f7461-0001-4b1e-9c3a-5a1e00000000 ("mota")
 *   GPS        ...0002  write / write without response, phone -> board
 *   Telemetry  ...0003  notify, board -> phone, every 100 ms
 * Text commands (PARAM, TANK, FILL, ...) go over the existing ELM327 UART
 * characteristics FFF2 (write) / FFF1 (notify).
 *
 * All fields little-endian. Version byte first so either side can evolve;
 * a receiver ignores packets of a version it doesn't know, and fields
 * appended at the end of a known version are ignored by older readers.
 * The Android app (android/) mirrors these layouts in BleProtocol.kt.
 *
 * Pure C with no ESP-IDF dependencies. */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PHONE_PROTO_VERSION 1

/* ---- GPS, phone -> board (24 bytes) ---- */
enum {
    PHONE_GPS_SPEED = 1 << 0,
    PHONE_GPS_POSITION = 1 << 1,
    PHONE_GPS_BEARING = 1 << 2,
    PHONE_GPS_SPEED_ACC = 1 << 3,
};

typedef struct {
    uint8_t flags;
    uint16_t seq;
    float speed_kmh;
    float speed_acc_kmh;   /* < 0 unknown */
    int32_t lat_e7;
    int32_t lon_e7;
    float h_acc_m;
    int16_t alt_m;
    float bearing_deg;
    uint16_t fix_age_ms;   /* how old the fix was when the phone sent it */
} phone_gps_t;

#define PHONE_GPS_LEN 24
bool phone_gps_decode(const uint8_t *buf, size_t len, phone_gps_t *out);
size_t phone_gps_encode(const phone_gps_t *in, uint8_t *buf, size_t size); /* for tests */

/* ---- Telemetry, board -> phone ---- */
enum {
    PHONE_TM_DATA = 1 << 0,        /* ECU answering (ignition on) */
    PHONE_TM_ENGINE = 1 << 1,      /* engine broadcast fresh */
    PHONE_TM_POWER = 1 << 2,       /* battery broadcast fresh */
    PHONE_TM_FUEL_VALID = 1 << 3,
    PHONE_TM_FUEL_CUT = 1 << 4,
    PHONE_TM_INST_PER_KM = 1 << 5, /* instant is L/100km (else L/h) */
    PHONE_TM_AVG_PER_KM = 1 << 6,  /* average is L/100km (else L/h) */
    PHONE_TM_CHT_CRITICAL = 1 << 7,
};
enum {
    PHONE_TM2_TANK_KNOWN = 1 << 0,
    PHONE_TM2_RANGE = 1 << 1,
    PHONE_TM2_FILL_AVG = 1 << 2,
    PHONE_TM2_GEARS_COMPLETE = 1 << 3,
    PHONE_TM2_SHIFT = 1 << 4,      /* RPM at or above the shift light */
};

#define PHONE_TM_SPEEDS 5          /* = SPEED_SRC_COUNT */
#define PHONE_TM_NA16 0xFFFF       /* unsigned 16-bit "no value" */
#define PHONE_TM_NA16S INT16_MIN   /* signed 16-bit "no value" */

typedef struct {
    uint8_t flags, flags2;
    uint16_t seq;
    uint16_t rpm;
    int16_t cht_c;
    uint16_t battery_mv;
    uint16_t throttle_x10;
    uint16_t map_kpa;
    int16_t iat_c;
    uint16_t o2_mv;
    int16_t stft_x10, ltft_x10, spark_x10;
    uint32_t inj_x10000;
    uint16_t flow_lph_x100;
    uint16_t inst_x100;            /* unit per PHONE_TM_INST_PER_KM */
    uint16_t avg_x100;             /* unit per PHONE_TM_AVG_PER_KM */
    uint32_t used_ml;              /* since start-up */
    uint32_t dist_m;               /* since start-up */
    uint16_t speed_x100[PHONE_TM_SPEEDS]; /* per source, NA16 = not fresh */
    uint16_t fuel_speed_x100;      /* NA16 = none */
    uint8_t fuel_speed_src;        /* speed_src_id_t chosen for fuel */
    uint8_t fuel_speed_origin;     /* speed_origin_t */
    uint8_t gear;                  /* 0 unknown */
    uint8_t gears_found;
    uint16_t tank_x100;            /* litres remaining */
    uint16_t range_km_x10;
    uint16_t fill_avg_x100;        /* L/100km since the fill */
    uint32_t fill_m;               /* metres since the fill */
    uint16_t learn_s;              /* gear learning seconds, capped */
    uint16_t shift_rpm;
    uint16_t range_l100_x100;      /* consumption the range uses, NA16 = none */
} phone_telemetry_t;

#define PHONE_TM_LEN 75
size_t phone_telemetry_encode(const phone_telemetry_t *in, uint8_t *buf, size_t size);
bool phone_telemetry_decode(const uint8_t *buf, size_t len, phone_telemetry_t *out); /* tests */

/* Helpers for packing floats into the scaled fields. */
uint16_t phone_u16(float value, float scale); /* NA16 if negative or NaN */
