package br.pedro.hytale.naturaldisasters;

import java.util.concurrent.ThreadLocalRandom;

enum WindDirection {
    NORTH(0, -1),
    NORTHEAST(1, -1),
    EAST(1, 0),
    SOUTHEAST(1, 1),
    SOUTH(0, 1),
    SOUTHWEST(-1, 1),
    WEST(-1, 0),
    NORTHWEST(-1, -1);

    private static final WindDirection[] DIRECTIONS = values();
    private final double x;
    private final double z;

    WindDirection(int x, int z) {
        double length = Math.hypot(x, z);
        this.x = x / length;
        this.z = z / length;
    }

    static WindDirection random() {
        return DIRECTIONS[ThreadLocalRandom.current().nextInt(DIRECTIONS.length)];
    }

    double x() { return x; }

    double z() { return z; }

    String weatherId() {
        return "NaturalDisasters_Blizzard_" + name();
    }

    String snowEffectId() {
        return "NaturalDisasters_Snow_" + name();
    }
}
