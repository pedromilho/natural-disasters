package br.pedro.hytale.naturaldisasters;

public final class BlizzardTest {
    public static void main(String[] args) {
        var blizzard = new Blizzard(-20, 30, WindDirection.SOUTH);
        check(blizzard.wind().x() == 0 && blizzard.wind().z() == 1, "South wind points along positive Z");
        check(WindDirection.NORTH.z() == -1, "North wind points along negative Z");
        check(Math.abs(Math.hypot(WindDirection.NORTHWEST.x(), WindDirection.NORTHWEST.z()) - 1) < 0.001,
            "Diagonal wind moves at the same speed as cardinal wind");
        check(blizzard.wind().weatherId().endsWith("_SOUTH"), "Weather uses the same heading as the wind");
        check(blizzard.contains(108, 30), "The edge of the 128-block radius is included");
        check(!blizzard.contains(108, 31), "The affected region is circular");
        check(!blizzard.contains(109, 30) && blizzard.contains(109, 30, 304),
            "A viewer can be outside the affected region and still be in visual range");
        check(blizzard.phase() == Blizzard.Phase.WARNING_SNOW, "The warning begins with light snow");
        check(!BlizzardMist.refreshDue(blizzard), "World mist waits for the storm to start");
        check(!blizzard.tick(0.5f), "A partial second does not refresh the countdown");
        check(blizzard.tick(0.5f) && blizzard.remainingSeconds() == 9, "Warning counts down each second");
        check(blizzard.windIncrement() == 0, "Light snow has no wind");
        check(blizzard.tick(4) && blizzard.phase() == Blizzard.Phase.WARNING_WIND,
            "Wind starts halfway through the warning");
        blizzard.tick(0.125f);
        double earlyWind = blizzard.windIncrement();
        check(earlyWind > 0 && !BlizzardMist.refreshDue(blizzard), "Wind begins before the mist");
        blizzard.tick(4.75f);
        check(blizzard.phase() == Blizzard.Phase.WARNING_WIND && !BlizzardMist.refreshDue(blizzard),
            "Mist remains off until the full ten-second warning elapses");
        blizzard.windIncrement();
        check(blizzard.tick(0.125f) && blizzard.phase() == Blizzard.Phase.ACTIVE, "Storm starts after ten seconds");
        check(blizzard.windIncrement() > earlyWind, "The warning wind grows to full strength at the start");
        check(blizzard.remainingSeconds() == 60, "The full sixty seconds start after the warning");
        check(BlizzardMist.refreshDue(blizzard), "Mist appears at the beginning of the storm");
        blizzard.tick(1);
        check(!BlizzardMist.refreshDue(blizzard), "The fog effect is not resent every second");
        blizzard.tick(1);
        check(BlizzardMist.refreshDue(blizzard), "Mist refreshes before existing particles fade out");
        check(blizzard.tick(60) && blizzard.phase() == Blizzard.Phase.IDLE, "Storm expires at its deadline");
        check(!BlizzardMist.refreshDue(blizzard), "Expired storms stop sending world mist");
        check(blizzard.windIncrement() == 0, "Expired Blizzard has no wind");

        var drift = new Blizzard(0, 0, WindDirection.EAST);
        drift.tick(Blizzard.WARNING_SECONDS);
        drift.windIncrement();
        for (int i = 0; i < 3; i++) {
            drift.tick(1.0f / 30);
            check(drift.windIncrement() == 0, "Wind does not send a movement correction each tick");
        }
        drift.tick(1.0f / 30);
        double increment = drift.windIncrement();
        check(increment > 0 && increment < 1, "Wind introduces a small horizontal correction");
        check(drift.windIncrement() == 0, "Reading wind again cannot send a second correction");
        drift.tick(2);
        check(drift.windIncrement() < 1, "A delayed tick cannot turn drift into a large impulse");

        var exposure = new Blizzard.Exposure();
        check(exposure.frostLevel(100) == 0 && exposure.tintLevel(100) == 0, "A warm player starts without frost");
        for (int i = 0; i < 12; i++) exposure.tick(false);
        check(exposure.warn() && !exposure.damageDue(), "Cold warns before it hurts");
        int warningFrost = exposure.frostLevel(100);
        check(warningFrost > 0, "Early exposure adds frost before the first hit");
        check(exposure.tintLevel(100) > 0 && exposure.tintLevel(100) < Blizzard.FIRST_DAMAGE_TINT_LEVEL,
            "The ice tint starts closing before the first hit");
        for (int i = 0; i < 6; i++) exposure.tick(true);
        check(exposure.frostLevel(100) == 0 && exposure.tintLevel(100) == 0, "Recovered warmth clears both layers");
        for (int i = 0; i < 23; i++) {
            exposure.tick(false);
            check(!exposure.damageDue() && exposure.tintLevel(100) < Blizzard.FIRST_DAMAGE_TINT_LEVEL,
                "Before the twenty-fourth second, the tint has not reached the damage threshold");
        }
        exposure.tick(false);
        check(exposure.damageDue() && exposure.tintLevel(100) == Blizzard.FIRST_DAMAGE_TINT_LEVEL,
            "First damage coincides with the tint approaching the center after twenty-four seconds");
        check(exposure.frostLevel(100) > warningFrost, "Frost grows when the first hit is due");
        check(exposure.frostLevel(Blizzard.DAMAGE) == Blizzard.FROST_LEVELS,
            "The maximum frost warns of an imminent potentially lethal hit");
        check(exposure.tintLevel(Blizzard.DAMAGE) == Blizzard.TINT_LEVELS,
            "The color closes in further when another hit could be lethal");
        for (int i = 0; i < 3; i++) {
            exposure.tick(false);
            check(!exposure.damageDue(), "Cold damage is spaced out");
        }
        exposure.tick(false);
        check(exposure.damageDue(), "Damage repeats after four more seconds");
        System.out.println("Blizzard lifecycle, wind, and frost tests passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
