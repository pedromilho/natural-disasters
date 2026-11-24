package br.pedro.hytale.naturaldisasters;

final class Blizzard {
    static final int RADIUS = 64;
    static final int WARNING_SECONDS = 10;
    static final int ACTIVE_SECONDS = 60;
    static final int FREEZING_THRESHOLD = 12;
    private static final int DAMAGE_INTERVAL = 5;

    private final double centerX;
    private final double centerZ;
    private double elapsed;

    Blizzard(double centerX, double centerZ) {
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    boolean contains(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    boolean tick(float seconds) {
        int previousRemaining = remainingSeconds();
        Phase previousPhase = phase();
        elapsed += seconds;
        return previousRemaining != remainingSeconds() || previousPhase != phase();
    }

    Phase phase() {
        if (elapsed < WARNING_SECONDS) return Phase.WARNING;
        if (elapsed < WARNING_SECONDS + ACTIVE_SECONDS) return Phase.ACTIVE;
        return Phase.IDLE;
    }

    int remainingSeconds() {
        return switch (phase()) {
            case IDLE -> 0;
            case WARNING -> (int) Math.ceil(WARNING_SECONDS - elapsed);
            case ACTIVE -> (int) Math.ceil(WARNING_SECONDS + ACTIVE_SECONDS - elapsed);
        };
    }

    enum Phase { IDLE, WARNING, ACTIVE }

    static final class Exposure {
        private int cold;

        void tick(boolean warm) {
            cold = warm ? Math.max(0, cold - 2) : cold + 1;
        }

        boolean warn() {
            return cold == FREEZING_THRESHOLD / 2;
        }

        boolean damageDue() {
            return cold >= FREEZING_THRESHOLD && (cold - FREEZING_THRESHOLD) % DAMAGE_INTERVAL == 0;
        }
    }
}
