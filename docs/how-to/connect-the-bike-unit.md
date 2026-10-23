# Connect the bike unit

The NavExpress Brain is the small unit on the bike that reads its CAN bus. With it, the phone
becomes a full bike dashboard. Building and fitting it is described in the
[bike unit guide](../brain.md).

## Pair once

1. Turn the ignition on with NavExpress open.
2. The app finds the unit by itself (it shows up as `OBDII`), remembers it and connects.
3. From then on it connects every time the bike is switched on. **Settings → Bike** shows the
   link, and **Find another** forgets the unit to pick a different one.

## What you get

- **Dashboard**: RPM and shift lights, gear, head temperature with a flashing STOP ENGINE
  warning, fuel flow now and on average, tank and range, battery, intake air, throttle.
- **Settings → Diagnostics**: every value the bike sends, the speed sources side by side and
  the gears learnt.
- **Fault codes**: read by themselves at connection and cleared with a button (ignition on,
  engine stopped). When faults are stored, the Settings tab shows their count in orange.

## Fuel and range

Dashboard ⚙ → bike settings:

- At a fill-up, enter the litres from the pump with **Filled up (full)** on. After two fills to full the
  unit suggests a corrected fuel constant, applied with one tap.
- Range blends the consumption you set with your real average since the last fill, and learns
  from each fill-up over 30 km.

## Speed and gears

Until a wheel sensor is fitted, the phone's GPS is sent to the bike as its speed source. On a
normal ride the unit learns the ratio of each gear and then shows the gear you are in, even
without GPS. After changing a tyre or the sprockets, **Relearn**.
