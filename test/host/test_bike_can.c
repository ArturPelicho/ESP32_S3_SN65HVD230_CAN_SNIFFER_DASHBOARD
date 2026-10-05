/* Host test for main/bike_can.c, using frames captured on the bike:
 *   gcc -std=c11 -Wall -Wextra -Werror -I main test/host/test_bike_can.c main/bike_can.c
 */
#include <assert.h>
#include <stdio.h>

#include "bike_can.h"

int main(void)
{
    const bike_can_config_t cfg = BIKE_CAN_ZS125_CONFIG();
    bike_can_data_t d = { 0 };

    /* Idle, test C: PID 0C said 0x1840/4 = 1552 rpm half a second later. */
    const uint8_t idle[8] = { 0x03, 0xE5, 0x17, 0xE2, 0x90, 0x32, 0xC8, 0xA8 };
    assert(bike_can_decode(&cfg, 0x110, false, idle, 8, &d) == BIKE_CAN_ENGINE);
    assert(d.engine_valid && d.rpm == 1528);
    assert(d.inj_raw == 13000 && d.temp_raw == 0x90 && d.status == 0xA8);
    assert(!d.battery_valid);

    /* Throttle blip, then the deceleration fuel cut. */
    const uint8_t blip[8] = { 0x03, 0xE5, 0x2C, 0xC6, 0xA1, 0x61, 0xA8, 0xA8 };
    assert(bike_can_decode(&cfg, 0x110, false, blip, 8, &d) == BIKE_CAN_ENGINE);
    assert(d.rpm == 2865 && d.inj_raw == 25000);
    const uint8_t cut[8] = { 0x03, 0xE5, 0x49, 0xB4, 0xA2, 0x00, 0x00, 0xA8 };
    bike_can_decode(&cfg, 0x110, false, cut, 8, &d);
    assert(d.rpm == 4717 && d.inj_raw == 0);

    /* Battery: 13.1 V key on, 14.3 V charging at idle. */
    const uint8_t key_on[8] = { 0, 0, 0, 0, 0, 0x7F, 0x83, 0 };
    assert(bike_can_decode(&cfg, 0x111, false, key_on, 8, &d) == BIKE_CAN_POWER);
    assert(d.battery_valid && d.battery_mv == 13100);
    const uint8_t charging[8] = { 0, 0, 0, 0, 0, 0x79, 0x8F, 0 };
    bike_can_decode(&cfg, 0x111, false, charging, 8, &d);
    assert(d.battery_mv == 14300);
    assert(d.rpm == 4717); /* power frames leave engine data alone */

    /* Other IDs, extended IDs and short frames are ignored. */
    assert(bike_can_decode(&cfg, 0x7E8, false, idle, 8, &d) == BIKE_CAN_NONE);
    assert(bike_can_decode(&cfg, 0x110, true, idle, 8, &d) == BIKE_CAN_NONE);
    assert(bike_can_decode(&cfg, 0x110, false, idle, 6, &d) == BIKE_CAN_NONE);
    assert(bike_can_decode(&cfg, 0x111, false, key_on, 6, &d) == BIKE_CAN_NONE);
    assert(d.rpm == 4717 && d.battery_mv == 14300);

    printf("bike_can: all tests passed\n");
    return 0;
}
