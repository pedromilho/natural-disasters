package br.pedro.hytale.naturaldisasters;

final class Blizzard {
    static final int RADIUS = 128;
    static final int WARNING_SECONDS = 10;
    static final int WIND_START_SECONDS = 5;
    static final int ACTIVE_SECONDS = 60;
    static final int FREEZING_THRESHOLD = 24;
    static final float DAMAGE = 10.0f;
    static final int FROST_LEVELS = 8;
    static final int FIRST_DAMAGE_TINT_LEVEL = 6;
    static final int TINT_LEVELS = 8;
    private static final int DAMAGE_INTERVAL = 4;
    private static final double WIND_UPDATE_SECONDS = 0.125;
    private static final double WIND_RESPONSE_SECONDS = 0.40;
    private static final double WIND_DRIFT_SPEED = 2.1;

    private final double centerX;
    private final double centerZ;
    private final WindDirection wind;
    private double elapsed;
    private double lastWindUpdate;

    Blizzard(double centerX, double centerZ) {
        this(centerX, centerZ, WindDirection.random());
    }

    Blizzard(double centerX, double centerZ, WindDirection wind) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.wind = wind;
    }

    WindDirection wind() { return wind; }

    double centerX() { return centerX; }

    double centerZ() { return centerZ; }

    double windIncrement() {
        if (elapsed < WIND_START_SECONDS || phase() == Phase.IDLE) return 0;
        double windElapsed = elapsed - WIND_START_SECONDS;
        double interval = windElapsed - lastWindUpdate;
        if (interval < WIND_UPDATE_SECONDS) return 0;
        lastWindUpdate = windElapsed;
        double rampSeconds = WARNING_SECONDS - WIND_START_SECONDS;
        double strength = 0.3 + 0.7 * Math.min(windElapsed / rampSeconds, 1.0);
        // A small correction replenishes the drift while Hytale carries and damps it between packets.
        return -strength * WIND_DRIFT_SPEED
            * Math.expm1(-Math.min(interval, 2 * WIND_UPDATE_SECONDS) / WIND_RESPONSE_SECONDS);
    }

    boolean contains(double x, double z) {
        return contains(x, z, RADIUS);
    }

    boolean contains(double x, double z, double radius) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    boolean tick(float seconds) {
        int previousRemaining = remainingSeconds();
        Phase previousPhase = phase();
        elapsed += seconds;
        return previousRemaining != remainingSeconds() || previousPhase != phase();
    }

    Phase phase() {
        if (elapsed < WIND_START_SECONDS) return Phase.WARNING_SNOW;
        if (elapsed < WARNING_SECONDS) return Phase.WARNING_WIND;
        if (elapsed < WARNING_SECONDS + ACTIVE_SECONDS) return Phase.ACTIVE;
        return Phase.IDLE;
    }

    int remainingSeconds() {
        return switch (phase()) {
            case IDLE -> 0;
            case WARNING_SNOW, WARNING_WIND -> (int) Math.ceil(WARNING_SECONDS - elapsed);
            case ACTIVE -> (int) Math.ceil(WARNING_SECONDS + ACTIVE_SECONDS - elapsed);
        };
    }

    enum Phase { IDLE, WARNING_SNOW, WARNING_WIND, ACTIVE }

    static final class Exposure {
        private int cold;

        void tick(boolean warm) {
            cold = warm ? Math.max(0, cold - 2) : cold + 1;
        }

        void reset() {
            cold = 0;
        }

        boolean warn() {
            return cold == FREEZING_THRESHOLD / 2;
        }

        boolean damageDue() {
            return cold >= FREEZING_THRESHOLD && (cold - FREEZING_THRESHOLD) % DAMAGE_INTERVAL == 0;
        }

        int frostLevel(float health) {
            int earlyLevels = FROST_LEVELS / 2 - 1;
            if (cold < FREEZING_THRESHOLD) {
                return (int) Math.ceil((double) cold * earlyLevels / FREEZING_THRESHOLD);
            }
            int damageStep = (cold - FREEZING_THRESHOLD) % DAMAGE_INTERVAL;
            boolean damageImminent = damageStep == 0 || damageStep == DAMAGE_INTERVAL - 1;
            return earlyLevels + 1 + Math.round(earlyLevels * lethalRisk(health)) + (damageImminent ? 1 : 0);
        }

        int tintLevel(float health) {
            if (cold < FREEZING_THRESHOLD) {
                return cold * FIRST_DAMAGE_TINT_LEVEL / FREEZING_THRESHOLD;
            }
            return FIRST_DAMAGE_TINT_LEVEL + Math.round((TINT_LEVELS - FIRST_DAMAGE_TINT_LEVEL) * lethalRisk(health));
        }

        private static float lethalRisk(float health) {
            return Math.clamp((4 * DAMAGE - health) / (3 * DAMAGE), 0.0f, 1.0f);
        }
    }
}
