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
    .displacement_cc = 124.8f, .stoich_afr = 14.7f, .fuel_density_g_per_ml = 0.745f,
    .lph_per_unit = 0.18f, .cylinders = 1, .strokes_per_cycle = 4,
};

/* Injection value an engine with VE 90 % sends at this rpm/MAP/IAT, if the
 * value is fuel at 0.18 L/h per unit and the mixture is stoichiometric. */
static float true_value(float rpm, float map_kpa, float iat_c)
{
    float x = map_kpa / (iat_c + 273.15f);
    float air_mg = x * 1000.0f / 287.05f * 124.8f * 0.90f;
    float fuel_ul = air_mg / 14.7f / 0.745f;
    float lph = fuel_ul * (rpm / 120.0f) * 3600.0f / 1e6f;
    return lph / 0.18f;
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
    s.inj_value = true_value(rpm, map, 25.0f);
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

    /* Sanity of the synthetic engine: ~13 units (2.4 L/h) at 8200 rpm. */
    float v80 = true_value(8200.0f, 80.0f, 25.0f);
    assert(v80 > 11.0f && v80 < 15.0f);

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

    /* The band's mean value is a mix of three loads, but the VE it implies
     * is still the engine's 90 % (value and MAP/T are both linear in load). */
    float ve;
    assert(steady_cal_band_ve(&band, &ENGINE, &ve));
    printf("ve %.3f\n", ve);
    assert(fabsf(ve - 0.90f) < 0.005f);

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
    s.inj_value = 0.0f;
    assert(steady_cal_add(&cut, &s, DT) < 0);       /* 8 s < 10 s: dropped */
    assert(cut.totals.bins[5].windows == 0);

    /* Reset clears the totals. */
    steady_cal_reset(&cal);
    assert(!steady_cal_band(&cal.totals, 8, &band));
    assert(cal.totals.version == STEADY_CAL_VERSION);

    puts("steady_cal: all tests passed");
    return 0;
}
