/* Host test for main/phone_proto.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_phone_proto.c main/phone_proto.c -lm
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "phone_proto.h"

static int near(double a, double b, double tol) { return fabs(a - b) <= tol; }

int main(void)
{
    /* GPS round trip, and the byte layout the Android app writes. */
    phone_gps_t g = {
        .flags = PHONE_GPS_SPEED | PHONE_GPS_POSITION | PHONE_GPS_SPEED_ACC, .seq = 513,
        .speed_kmh = 87.3f, .speed_acc_kmh = 1.8f, .lat_e7 = 387223000, .lon_e7 = -91393000,
        .h_acc_m = 4.2f, .alt_m = -12, .bearing_deg = 271.5f, .fix_age_ms = 120,
    };
    uint8_t buf[80];
    assert(phone_gps_encode(&g, buf, sizeof(buf)) == PHONE_GPS_LEN);
    assert(buf[0] == 1 && buf[2] == 0x01 && buf[3] == 0x02); /* seq little-endian */
    assert(buf[4] == (2425 & 0xFF) && buf[5] == (2425 >> 8)); /* 87.3 km/h = 2425 cm/s */
    phone_gps_t d;
    assert(phone_gps_decode(buf, PHONE_GPS_LEN, &d));
    assert(d.seq == 513 && near(d.speed_kmh, 87.3, 0.04) && near(d.speed_acc_kmh, 1.8, 0.04));
    assert(d.lat_e7 == 387223000 && d.lon_e7 == -91393000 && d.alt_m == -12);
    assert(near(d.h_acc_m, 4.2, 0.05) && near(d.bearing_deg, 271.5, 0.01) && d.fix_age_ms == 120);
    /* No accuracy flag -> unknown. */
    buf[1] &= (uint8_t)~PHONE_GPS_SPEED_ACC;
    assert(phone_gps_decode(buf, PHONE_GPS_LEN, &d) && d.speed_acc_kmh < 0.0f);
    /* Short or unknown version: rejected. */
    assert(!phone_gps_decode(buf, PHONE_GPS_LEN - 1, &d));
    buf[0] = 2;
    assert(!phone_gps_decode(buf, PHONE_GPS_LEN, &d));

    /* Telemetry round trip. */
    phone_telemetry_t t;
    memset(&t, 0, sizeof(t));
    t.flags = PHONE_TM_DATA | PHONE_TM_ENGINE;
    t.flags2 = PHONE_TM2_RANGE;
    t.seq = 7;
    t.rpm = 8200;
    t.cht_c = 131;
    t.iat_c = -5;
    t.stft_x10 = -32;
    t.inj_x10000 = 132000;
    t.used_ml = 1234567;
    for (int i = 0; i < PHONE_TM_SPEEDS; ++i) t.speed_x100[i] = PHONE_TM_NA16;
    t.speed_x100[0] = 8530;
    t.gear = 5;
    t.fill_m = 98765;
    t.shift_rpm = 10000;
    t.range_l100_x100 = 255;
    size_t n = phone_telemetry_encode(&t, buf, sizeof(buf));
    assert(n == PHONE_TM_LEN);
    assert(phone_telemetry_encode(&t, buf, PHONE_TM_LEN - 1) == 0);
    phone_telemetry_t u;
    assert(phone_telemetry_decode(buf, n, &u));
    assert(!memcmp(&t, &u, sizeof(t)));

    assert(phone_u16(-1.0f, 100.0f) == PHONE_TM_NA16);
    assert(phone_u16(NAN, 100.0f) == PHONE_TM_NA16);
    assert(phone_u16(12.345f, 100.0f) == 1235);
    assert(phone_u16(1e9f, 1.0f) == PHONE_TM_NA16 - 1);

    printf("phone_proto: all tests passed\n");
    return 0;
}
