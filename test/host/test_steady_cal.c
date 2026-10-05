/* Host test for main/steady_cal.c:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_steady_cal.c main/steady_cal.c -lm
 */
#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "steady_cal.h"

#define DT 0.1f
#define PI 3.14159265f

static const steady_cal_engine_t ENGINE = {
    .displacement_cc = 124.8f, .volumetric_efficiency = 0.80f, .stoich_afr = 14.7f,
    .fuel_density_g_per_ml = 0.745f, .dead_time_ms = 0.6f,
};

/* Pulse an injector of 100 cc/min with 0.6 ms dead time needs at this
 * MAP/IAT, if VE really is 80 %. */
static float true_pulse(float map_kpa, float iat_c)
{
    float x = map_kpa / (iat_c + 273.15f);
    float air_mg = x * 1000.0f / 287.05f * 124.8f * 0.80f;
    float fuel_ul = air_mg / 14.7f / 0.745f;
    return 0.6f + fuel_ul / (100.0f / 60.0f);
}

static steady_cal_sample_t cruise(float rpm, float map, float t)
{
    steady_cal_sample_t s = {
        .rpm = rpm, .map_kpa = map, .intake_temp_c = 25.0f, .throttle_pct = 27.0f,
        .o2_volts = 0.45f + 0.35f * sinf(2.0f * PI * t), .stft_pct = 1.0f, .ltft_pct = 4.0f,
        .engine_temp_c = 130.0f, .battery_v = 14.2f,
        .rpm_valid = true, .map_valid = true, .intake_temp_valid = true, .throttle_valid = true,
        .o2_valid = true, .trims_valid = true, .engine_temp_valid = true, .battery_valid = true,
    };
    s.pulse_ms = true_pulse(map, 25.0f);
    return s;
}

static int feed(steady_cal_t *cal, float rpm, float map, float seconds, float *t)
{
    int kept = 0;
    for (float end = *t + seconds; *t < end; *t += DT) {
        steady_cal_sample_t s = cruise(rpm, map, *t);
        if (steady_cal_add(cal, &s, DT) >= 0) ++kept;
    }
    return kept;
}

int main(void)
{
    const steady_cal_config_t cfg = STEADY_CAL_DEFAULT_CONFIG();
    static steady_cal_t cal;
    steady_cal_init(&cal, &cfg);
    float t = 0.0f;

    /* Sanity of the synthetic engine: ~8.6 ul per injection at 80 kPa. */
    float p80 = true_pulse(80.0f, 25.0f);
    assert(p80 > 5.0f && p80 < 6.0f);

    /* Highway: 8200 rpm, three loads (flat, uphill, downhill), 2 min each.
     * Each change of MAP closes a window; long stretches split every 30 s. */
    int kept = feed(&cal, 8200.0f, 80.0f, 120.0f, &t);
    kept += feed(&cal, 8200.0f, 92.0f, 120.0f, &t);
    kept += feed(&cal, 8200.0f, 66.0f, 120.0f, &t);
    assert(kept >= 10);
    steady_cal_band_t band;
    assert(steady_cal_band(&cal.totals, 8, &band));
    assert(!steady_cal_band(&cal.totals, 3, &band) || cal.totals.bins[3].windows == 0);
    steady_cal_band(&cal.totals, 8, &band);
    assert(fabsf(band.rpm - 8200.0f) < 1.0f);
    assert(band.seconds > 330.0f);
    assert(fabsf(band.ltft_pct - 4.0f) < 0.01f);

    float dead, basis;
    assert(steady_cal_dead_time(&cal.totals, &dead, &basis));
    printf("dead %.3f ms (basis %.2f)\n", dead, basis);
    assert(fabsf(dead - 0.6f) < 0.02f);

    float flow;
    assert(steady_cal_band_flow(&band, &ENGINE, &flow));
    printf("flow %.1f cc/min\n", flow);
    assert(fabsf(flow - 100.0f) < 1.0f);

    /* City: RPM never holds +-5 % for 10 s, so nothing is kept. */
    steady_cal_t city;
    steady_cal_init(&city, &cfg);
    float tc = 0.0f;
    for (int i = 0; i < 6000; ++i, tc += DT) {
        float rpm = 3500.0f + 1500.0f * sinf(2.0f * PI * tc / 8.0f);
        steady_cal_sample_t s = cruise(rpm, 60.0f, tc);
        assert(steady_cal_add(&city, &s, DT) < 0);
    }

    /* Cold engine, fuel cut, open loop (O2 stuck rich), cold O2: none kept. */
    steady_cal_t cold;
    steady_cal_init(&cold, &cfg);
    for (int i = 0; i < 600; ++i) {
        steady_cal_sample_t s = cruise(5000.0f, 70.0f, i * DT);
        s.engine_temp_c = 50.0f;
        assert(steady_cal_add(&cold, &s, DT) < 0);
        s = cruise(5000.0f, 70.0f, i * DT);
        s.o2_volts = 0.80f;
        assert(steady_cal_add(&cold, &s, DT) < 0);
        s.o2_valid = false;
        assert(steady_cal_add(&cold, &s, DT) < 0);
    }
    steady_cal_t cut;
    steady_cal_init(&cut, &cfg);
    float tk = 0.0f;
    for (int i = 0; i < 80; ++i, tk += DT) {        /* 8 s steady, then a cut */
        steady_cal_sample_t s = cruise(5000.0f, 70.0f, tk);
        steady_cal_add(&cut, &s, DT);
    }
    steady_cal_sample_t s = cruise(5000.0f, 0.0f, tk);
    s.pulse_ms = 0.0f;
    assert(steady_cal_add(&cut, &s, DT) < 0);       /* 8 s < 10 s: dropped */
    assert(cut.totals.bins[5].windows == 0);

    /* Reset clears the totals. */
    steady_cal_reset(&cal);
    assert(!steady_cal_band(&cal.totals, 8, &band));
    assert(cal.totals.version == STEADY_CAL_VERSION);

    puts("steady_cal: all tests passed");
    return 0;
}
