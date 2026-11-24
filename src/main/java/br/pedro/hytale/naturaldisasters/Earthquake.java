package br.pedro.hytale.naturaldisasters;

final class Earthquake {
    static final int WARNING_SECONDS = 3;

    enum Magnitude {
        LIGHT(72, 6, 0.36f, 24.0, 0.12),
        MODERATE(108, 9, 0.74f, 67.0, 0.40),
        STRONG(144, 12, 1.0f, 112.0, 1.0);

        final int radius;
        final int duration;
        final float shake;
        final double acceleration;
        final double fissureChance;

        Magnitude(int radius, int duration, float shake, double acceleration, double fissureChance) {
            this.radius = radius;
            this.duration = duration;
            this.shake = shake;
            this.acceleration = acceleration;
            this.fissureChance = fissureChance;
        }
    }

    enum Phase { WARNING, ACTIVE, FINISHED }

    final double x;
    final double y;
    final double z;
    final Magnitude magnitude;
    private final double ruptureRoll;
    private double elapsed;
    private boolean ruptureDecided;

    Earthquake(double x, double y, double z, Magnitude magnitude, double ruptureRoll) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.magnitude = magnitude;
        this.ruptureRoll = ruptureRoll;
    }

    boolean tick(float dt) {
        Phase previousPhase = phase();
        int previousRemaining = remainingSeconds();
        elapsed += dt;
        return phase() != previousPhase || remainingSeconds() != previousRemaining;
    }

    Phase phase() {
        if (elapsed < WARNING_SECONDS) return Phase.WARNING;
        if (elapsed < WARNING_SECONDS + magnitude.duration) return Phase.ACTIVE;
        return Phase.FINISHED;
    }

    int remainingSeconds() {
        return switch (phase()) {
            case WARNING -> (int) Math.ceil(WARNING_SECONDS - elapsed);
            case ACTIVE -> (int) Math.ceil(WARNING_SECONDS + magnitude.duration - elapsed);
            case FINISHED -> 0;
        };
    }

    boolean takeRuptureAttempt() {
        double delay = magnitude == Magnitude.STRONG ? 0 : magnitude.duration / 2.0;
        if (ruptureDecided || phase() != Phase.ACTIVE || elapsed < WARNING_SECONDS + delay) {
            return false;
        }
        ruptureDecided = true;
        return ruptureRoll < magnitude.fissureChance;
    }

    double jolt(float intensity, float dt) {
        if (phase() != Phase.ACTIVE || dt <= 0) return 0;
        return intensity * magnitude.acceleration * Math.min(dt, 0.05f);
    }

    float intensityAt(double px, double pz) {
        double distance = Math.hypot(px - x, pz - z);
        if (distance > magnitude.radius) return 0;
        double pulse = 1.0;
        if (phase() == Phase.ACTIVE) {
            double progress = (elapsed - WARNING_SECONDS) / magnitude.duration;
            double baseline = magnitude == Magnitude.STRONG ? 0.72 : 0.55;
            pulse = baseline + (1.0 - baseline) * Math.sin(Math.PI * progress);
        }
        return (float) (magnitude.shake * pulse * (1.0 - 0.75 * distance / magnitude.radius));
    }
}
