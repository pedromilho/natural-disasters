package br.pedro.hytale.naturaldisasters;

import java.util.Random;

public final class EarthquakeTest {
    public static void main(String[] args) {
        var strong = new Earthquake(0.5, 59, 0.5, Earthquake.Magnitude.STRONG, 0.999);
        check(strong.phase() == Earthquake.Phase.WARNING && strong.remainingSeconds() == 3,
            "The earthquake starts with a warning");
        check(!strong.takeRuptureAttempt(), "The terrain cannot rupture during the warning");
        check(strong.tick(3) && strong.phase() == Earthquake.Phase.ACTIVE, "Shaking follows the warning");
        float beginning = strong.intensityAt(0.5, 0.5);
        checkRadius(Earthquake.Magnitude.LIGHT, 72);
        checkRadius(Earthquake.Magnitude.MODERATE, 108);
        checkRadius(Earthquake.Magnitude.STRONG, 144);
        check(strong.takeRuptureAttempt(), "The strongest earthquake always ruptures as shaking begins");
        strong.tick(6);
        check(strong.intensityAt(0.5, 0.5) > beginning, "Shaking builds to a peak");
        check(!strong.takeRuptureAttempt(), "A quake only gets one attempt to alter the map");
        strong.tick(6);
        check(strong.phase() == Earthquake.Phase.FINISHED, "The tremor finishes after its intensity-specific duration");
        check(strong.jolt(1, 1.0f / 30) == 0, "An expired quake cannot move the player");

        var joltLight = new Earthquake(0, 60, 0, Earthquake.Magnitude.LIGHT, 0.10);
        var joltModerate = new Earthquake(0, 60, 0, Earthquake.Magnitude.MODERATE, 0.10);
        var joltStrong = new Earthquake(0, 60, 0, Earthquake.Magnitude.STRONG, 0.10);
        check(joltStrong.jolt(1, 1.0f / 30) == 0, "The warning phase applies no jolt");
        joltLight.tick(Earthquake.WARNING_SECONDS + 3);
        joltModerate.tick(Earthquake.WARNING_SECONDS + 4.5f);
        joltStrong.tick(Earthquake.WARNING_SECONDS + 6);
        check(!joltStrong.tick(1.0f / 30) && joltStrong.jolt(joltStrong.intensityAt(0, 0), 1.0f / 30) > 0,
            "Jolts still apply on ticks that do not update the countdown");
        double lightPush = joltLight.jolt(joltLight.intensityAt(0, 0), 1.0f / 30);
        double moderatePush = joltModerate.jolt(joltModerate.intensityAt(0, 0), 1.0f / 30);
        double strongPush = joltStrong.jolt(joltStrong.intensityAt(0, 0), 1.0f / 30);
        check(moderatePush > lightPush * 4 && strongPush > moderatePush * 2,
            "Each intensity has a distinctly stronger shove at peak shaking");

        check(!ruptures(Earthquake.Magnitude.LIGHT, 0.2) && ruptures(Earthquake.Magnitude.MODERATE, 0.2),
            "Moderate tremors can rupture on rolls that light tremors cannot");
        check(!ruptures(Earthquake.Magnitude.MODERATE, 0.6) && ruptures(Earthquake.Magnitude.STRONG, 0.6),
            "Strong tremors can rupture on rolls that moderate tremors cannot");

        var cave = new Fixture(true);
        var light = new Earthquake(0.5, 59, 0.5, Earthquake.Magnitude.LIGHT, 0.05);
        var shallow = EarthquakeFissures.plan(light, cave, new Random(9));
        var deep = EarthquakeFissures.plan(strong, cave, new Random(9));
        check(!shallow.isEmpty() && EarthquakeFissures.isLarge(deep) && deep.size() > shallow.size(),
            "Strong tremors cut a larger fissure");
        check(deep.getFirst().x() == 6 && deep.getFirst().y() == 60
            && deep.stream().filter(cell -> cell.x() >= 6).allMatch(cell -> cell.y() >= 57),
            "A surface opening above a cave takes priority over the closer surface away from caves");
        check(!EarthquakeFissures.isLarge(deep.stream().filter(cell -> cell.x() != 6 && cell.z() != 0).toList()),
            "Disconnected cuts do not count as a large fissure");
        check(deep.stream().allMatch(cell -> cave.at(cell.x(), cell.y(), cell.z()) == EarthquakeFissures.Material.NATURAL),
            "Cuts skip obstacles and unknown blocks");

        var bareRock = new Fixture(false);
        var surfaceCut = EarthquakeFissures.plan(light, bareRock, new Random(9));
        check(!surfaceCut.isEmpty() && surfaceCut.getFirst().y() == bareRock.surface(0, 0),
            "Eligible exposed ground can split where there is no cave");
        checkGrassCover(strong);
        checkFissureVariations(Earthquake.Magnitude.LIGHT, 2, 5, 1, 3, 1, 4);
        checkFissureVariations(Earthquake.Magnitude.MODERATE, 7, 16, 3, 8, 4, 12);
        checkFissureVariations(Earthquake.Magnitude.STRONG, 20, 36, 5, 13, 10, 24);
        System.out.println("Earthquake lifecycle, probability, fissure placement, and size variation tests passed.");
    }

    private static void checkRadius(Earthquake.Magnitude magnitude, int radius) {
        var quake = new Earthquake(0, 60, 0, magnitude, 0);
        quake.tick(Earthquake.WARNING_SECONDS);
        float edge = quake.intensityAt(radius, 0);
        check(edge > 0 && edge < quake.intensityAt(radius / 2.0, 0) && quake.intensityAt(radius + 1, 0) == 0,
            magnitude + " shaking weakens toward its expected radius and stops beyond it");
    }

    private static void checkGrassCover(Earthquake strong) {
        // A heightmap can report the ground, cover, or the first air block.
        for (int height : new int[]{60, 61, 62}) {
            var terrain = new EarthquakeFissures.Terrain() {
                @Override public int surface(int x, int z) { return height; }

                @Override public EarthquakeFissures.Material at(int x, int y, int z) {
                    if (y == 61) return EarthquakeFissures.Material.GRASS;
                    return y > 61 ? EarthquakeFissures.Material.AIR : EarthquakeFissures.Material.NATURAL;
                }
            };
            var cut = EarthquakeFissures.plan(strong, terrain, new Random(9));
            check(EarthquakeFissures.isLarge(cut) && cut.getFirst().y() == 60,
                "Short grass must not block a strong fissure, and cannot count as ground");
        }
    }

    private static void checkFissureVariations(Earthquake.Magnitude magnitude, int minHalfLength, int maxHalfLength,
                                               int minWidth, int maxWidth, int minDepth, int maxDepth) {
        var lengthCounts = new int[maxHalfLength - minHalfLength + 1];
        var widthCounts = new int[maxWidth - minWidth + 1];
        var depthCounts = new int[maxDepth - minDepth + 1];
        var quake = new Earthquake(0.5, 61, 0.5, magnitude, 0.0);
        var terrain = new Fixture(false);
        var random = new Random(12345);
        for (int sample = 0; sample < 768; sample++) {
            var cut = EarthquakeFissures.plan(quake, terrain, random);
            check(!cut.isEmpty(), "Flat natural terrain can always split");
            if (magnitude == Earthquake.Magnitude.STRONG) {
                check(EarthquakeFissures.isLarge(cut), "Even the smallest strong variation is a large fissure");
            }
            var center = cut.getFirst();
            int xSpan = 0, zSpan = 0;
            for (var cell : cut) {
                xSpan = Math.max(xSpan, Math.abs(cell.x() - center.x()));
                zSpan = Math.max(zSpan, Math.abs(cell.z() - center.z()));
            }
            boolean alongX = xSpan > zSpan;
            int halfLength = Math.max(xSpan, zSpan);
            int width = 0, depth = 0;
            for (var cell : cut) {
                if (cell.x() == center.x() && cell.z() == center.z()) depth++;
                if (cell.y() == center.y() && (alongX ? cell.x() == center.x() : cell.z() == center.z())) width++;
            }
            lengthCounts[halfLength - minHalfLength]++;
            widthCounts[width - minWidth]++;
            depthCounts[depth - minDepth]++;
        }
        checkRarerExtremes(lengthCounts, magnitude + " length");
        checkRarerExtremes(widthCounts, magnitude + " width");
        checkRarerExtremes(depthCounts, magnitude + " depth");
    }

    private static void checkRarerExtremes(int[] counts, String dimension) {
        int middle = counts[counts.length / 2];
        check(counts[0] > 0 && counts[counts.length - 1] > 0
            && counts[0] < middle && counts[counts.length - 1] < middle,
            dimension + " reaches both extremes, but intermediate sizes occur more often");
    }

    private static boolean ruptures(Earthquake.Magnitude magnitude, double roll) {
        var quake = new Earthquake(0, 60, 0, magnitude, roll);
        quake.tick(Earthquake.WARNING_SECONDS + magnitude.duration / 2.0f);
        return quake.takeRuptureAttempt();
    }

    private static void check(boolean result, String claim) {
        if (!result) throw new AssertionError(claim);
    }

    private record Fixture(boolean cave) implements EarthquakeFissures.Terrain {
        @Override
        public int surface(int x, int z) {
            return 60;
        }

        @Override
        public EarthquakeFissures.Material at(int x, int y, int z) {
            if (y <= 1 || (x == 3 && z == 0 && y == 41)) return EarthquakeFissures.Material.BLOCKED;
            if (y > 60 || (cave && x >= 6 && y >= 52 && y <= 56)) return EarthquakeFissures.Material.AIR;
            return EarthquakeFissures.Material.NATURAL;
        }
    }
}
