#include "phone_proto.h"

#include <math.h>
#include <string.h>

static void put8(uint8_t **p, uint8_t v) { *(*p)++ = v; }
static void put16(uint8_t **p, uint16_t v)
{
    *(*p)++ = (uint8_t)v;
    *(*p)++ = (uint8_t)(v >> 8);
}
static void put32(uint8_t **p, uint32_t v)
{
    put16(p, (uint16_t)v);
    put16(p, (uint16_t)(v >> 16));
}
static uint8_t get8(const uint8_t **p) { return *(*p)++; }
static uint16_t get16(const uint8_t **p)
{
    uint16_t v = (uint16_t)((*p)[0] | ((*p)[1] << 8));
    *p += 2;
    return v;
}
static uint32_t get32(const uint8_t **p)
{
    uint32_t lo = get16(p);
    return lo | ((uint32_t)get16(p) << 16);
}

uint16_t phone_u16(float value, float scale)
{
    if (!(value >= 0.0f)) return PHONE_TM_NA16;
    float v = value * scale + 0.5f;
    if (v >= (float)(PHONE_TM_NA16 - 1)) return PHONE_TM_NA16 - 1;
    return (uint16_t)v;
}

bool phone_gps_decode(const uint8_t *buf, size_t len, phone_gps_t *out)
{
    if (len < PHONE_GPS_LEN || buf[0] != PHONE_PROTO_VERSION) return false;
    const uint8_t *p = buf + 1;
    memset(out, 0, sizeof(*out));
    out->flags = get8(&p);
    out->seq = get16(&p);
    out->speed_kmh = get16(&p) * 0.036f; /* cm/s */
    uint16_t acc = get16(&p);
    out->speed_acc_kmh = (out->flags & PHONE_GPS_SPEED_ACC) ? acc * 0.036f : -1.0f;
    out->lat_e7 = (int32_t)get32(&p);
    out->lon_e7 = (int32_t)get32(&p);
    out->h_acc_m = get16(&p) / 10.0f;
    out->alt_m = (int16_t)get16(&p);
    out->bearing_deg = get16(&p) / 100.0f;
    out->fix_age_ms = get16(&p);
    return true;
}

size_t phone_gps_encode(const phone_gps_t *in, uint8_t *buf, size_t size)
{
    if (size < PHONE_GPS_LEN) return 0;
    uint8_t *p = buf;
    put8(&p, PHONE_PROTO_VERSION);
    put8(&p, in->flags);
    put16(&p, in->seq);
    put16(&p, phone_u16(in->speed_kmh, 1.0f / 0.036f));
    put16(&p, in->speed_acc_kmh < 0.0f ? 0 : phone_u16(in->speed_acc_kmh, 1.0f / 0.036f));
    put32(&p, (uint32_t)in->lat_e7);
    put32(&p, (uint32_t)in->lon_e7);
    put16(&p, phone_u16(in->h_acc_m, 10.0f));
    put16(&p, (uint16_t)in->alt_m);
    put16(&p, phone_u16(in->bearing_deg, 100.0f));
    put16(&p, in->fix_age_ms);
    return (size_t)(p - buf);
}

size_t phone_telemetry_encode(const phone_telemetry_t *t, uint8_t *buf, size_t size)
{
    if (size < PHONE_TM_LEN) return 0;
    uint8_t *p = buf;
    put8(&p, PHONE_PROTO_VERSION);
    put8(&p, t->flags);
    put8(&p, t->flags2);
    put16(&p, t->seq);
    put16(&p, t->rpm);
    put16(&p, (uint16_t)t->cht_c);
    put16(&p, t->battery_mv);
    put16(&p, t->throttle_x10);
    put16(&p, t->map_kpa);
    put16(&p, (uint16_t)t->iat_c);
    put16(&p, t->o2_mv);
    put16(&p, (uint16_t)t->stft_x10);
    put16(&p, (uint16_t)t->ltft_x10);
    put16(&p, (uint16_t)t->spark_x10);
    put32(&p, t->inj_x10000);
    put16(&p, t->flow_lph_x100);
    put16(&p, t->inst_x100);
    put16(&p, t->avg_x100);
    put32(&p, t->used_ml);
    put32(&p, t->dist_m);
    for (int i = 0; i < PHONE_TM_SPEEDS; ++i) put16(&p, t->speed_x100[i]);
    put16(&p, t->fuel_speed_x100);
    put8(&p, t->fuel_speed_src);
    put8(&p, t->fuel_speed_origin);
    put8(&p, t->gear);
    put8(&p, t->gears_found);
    put16(&p, t->tank_x100);
    put16(&p, t->range_km_x10);
    put16(&p, t->fill_avg_x100);
    put32(&p, t->fill_m);
    put16(&p, t->learn_s);
    put16(&p, t->shift_rpm);
    return (size_t)(p - buf);
}

bool phone_telemetry_decode(const uint8_t *buf, size_t len, phone_telemetry_t *t)
{
    if (len < PHONE_TM_LEN || buf[0] != PHONE_PROTO_VERSION) return false;
    const uint8_t *p = buf + 1;
    t->flags = get8(&p);
    t->flags2 = get8(&p);
    t->seq = get16(&p);
    t->rpm = get16(&p);
    t->cht_c = (int16_t)get16(&p);
    t->battery_mv = get16(&p);
    t->throttle_x10 = get16(&p);
    t->map_kpa = get16(&p);
    t->iat_c = (int16_t)get16(&p);
    t->o2_mv = get16(&p);
    t->stft_x10 = (int16_t)get16(&p);
    t->ltft_x10 = (int16_t)get16(&p);
    t->spark_x10 = (int16_t)get16(&p);
    t->inj_x10000 = get32(&p);
    t->flow_lph_x100 = get16(&p);
    t->inst_x100 = get16(&p);
    t->avg_x100 = get16(&p);
    t->used_ml = get32(&p);
    t->dist_m = get32(&p);
    for (int i = 0; i < PHONE_TM_SPEEDS; ++i) t->speed_x100[i] = get16(&p);
    t->fuel_speed_x100 = get16(&p);
    t->fuel_speed_src = get8(&p);
    t->fuel_speed_origin = get8(&p);
    t->gear = get8(&p);
    t->gears_found = get8(&p);
    t->tank_x100 = get16(&p);
    t->range_km_x10 = get16(&p);
    t->fill_avg_x100 = get16(&p);
    t->fill_m = get32(&p);
    t->learn_s = get16(&p);
    t->shift_rpm = get16(&p);
    return true;
}
