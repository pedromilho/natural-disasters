package br.pedro.hytale.naturaldisasters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.random.RandomGenerator;

final class EarthquakeFissures {
    private static final int SEARCH_RADIUS = 36;
    private static final int SEARCH_STEP = 6;
    private static final int SEARCH_DEPTH = 48;
    private static final int HEADROOM = 3;
    private static final int STRONG_MIN_SPAN = 24;
    private static final int STRONG_MIN_COLUMNS = 27;
    private static final int STRONG_MIN_BLOCKS = 80;
    private static final Shape DEFAULT_STRONG_SHAPE = new Shape(24, 7, 14);

    private EarthquakeFissures() { }

    static List<Cell> plan(Earthquake quake, Terrain terrain, RandomGenerator random) {
        var plans = plans(quake, terrain, random, 1);
        return plans.isEmpty() ? List.of() : plans.getFirst();
    }

    static List<List<Cell>> plans(Earthquake quake, Terrain terrain, RandomGenerator random, int limit) {
        var sites = findSites(quake, terrain);
        Shape shape = rollShape(quake.magnitude, random);
        boolean strong = quake.magnitude == Earthquake.Magnitude.STRONG;
        var plans = plansFor(terrain, random, sites, shape, strong, limit);
        if (strong && plans.isEmpty() && !shape.equals(DEFAULT_STRONG_SHAPE)) {
            return plansFor(terrain, random, sites, DEFAULT_STRONG_SHAPE, true, limit);
        }
        return plans;
    }

    private static List<List<Cell>> plansFor(Terrain terrain, RandomGenerator random, List<Site> sites, Shape shape,
                                            boolean strong, int limit) {
        var plans = new ArrayList<List<Cell>>();
        for (Site site : sites) {
            boolean alongX = random.nextBoolean();
            for (int direction = 0; direction < 2; direction++, alongX = !alongX) {
                var cut = cutAt(terrain, random, site.surface, shape, alongX);
                if (strong ? !isLarge(cut) : cut.isEmpty()) continue;
                plans.add(cut);
                if (plans.size() == limit) return plans;
            }
        }
        return plans;
    }

    static boolean isLarge(List<Cell> cut) {
        if (cut.size() < STRONG_MIN_BLOCKS) return false;
        var columns = new HashSet<Long>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : cut) {
            columns.add(cell.column());
            minX = Math.min(minX, cell.x);
            maxX = Math.max(maxX, cell.x);
            minZ = Math.min(minZ, cell.z);
            maxZ = Math.max(maxZ, cell.z);
        }
        return columns.size() >= STRONG_MIN_COLUMNS && Math.max(maxX - minX, maxZ - minZ) >= STRONG_MIN_SPAN
            && isConnected(columns, cut.getFirst().column());
    }

    private static boolean isConnected(HashSet<Long> remaining, long first) {
        var visit = new ArrayDeque<Long>();
        visit.add(first);
        remaining.remove(first);
        while (!visit.isEmpty()) {
            long column = visit.removeFirst();
            int x = (int) (column >> 32);
            int z = (int) column;
            for (long next : new long[]{column(x + 1, z), column(x - 1, z), column(x, z + 1), column(x, z - 1)}) {
                if (remaining.remove(next)) visit.add(next);
            }
        }
        return remaining.isEmpty();
    }

    private static Shape rollShape(Earthquake.Magnitude magnitude, RandomGenerator random) {
        return switch (magnitude) {
            case LIGHT -> new Shape(typical(random, 2, 5), typical(random, 1, 3), typical(random, 1, 4));
            case MODERATE -> new Shape(typical(random, 7, 16), typical(random, 3, 8), typical(random, 4, 12));
            case STRONG -> new Shape(typical(random, 20, 36), typical(random, 5, 13), typical(random, 10, 24));
        };
    }

    private static int typical(RandomGenerator random, int min, int max) {
        // Averaging two uniform rolls produces a triangular distribution over the range.
        double roll = (random.nextDouble() + random.nextDouble()) / 2.0;
        return min + (int) (roll * (max - min + 1));
    }

    private static List<Cell> cutAt(Terrain terrain, RandomGenerator random, Cell site, Shape shape, boolean alongX) {
        var cut = new LinkedHashSet<Cell>();
        int halfLength = shape.halfLength;
        int widerSide = random.nextBoolean() ? 1 : -1;
        cutColumn(cut, terrain, site, shape.depth);
        cutSides(cut, terrain, site, alongX, shape.width, shape.depth, widerSide);
        for (int sign : new int[]{-1, 1}) {
            int lateral = 0;
            int elevation = site.y;
            for (int step = 1; step <= halfLength; step++) {
                int axis = step * sign;
                Cell base = surfaceAlong(terrain, site, alongX, axis, lateral, elevation);
                if (base == null) break;
                int depth = shape.depth - step * (shape.depth - 1) / halfLength;
                cutColumn(cut, terrain, base, depth);
                elevation = base.y;

                if (step % 3 == 0) {
                    int bend = Math.clamp(lateral + (random.nextBoolean() ? 1 : -1), -2, 2);
                    Cell turn = surfaceAlong(terrain, site, alongX, axis, bend, elevation);
                    if (turn != null) {
                        cutColumn(cut, terrain, turn, depth);
                        lateral = bend;
                        elevation = turn.y;
                        base = turn;
                    }
                }
                int width = step <= halfLength / 3 ? shape.width
                    : step <= halfLength * 2 / 3 ? (shape.width + 1) / 2 : 1;
                cutSides(cut, terrain, base, alongX, width, depth, widerSide);
            }
        }
        return List.copyOf(cut);
    }

    private static void cutSides(LinkedHashSet<Cell> cut, Terrain terrain, Cell center, boolean alongX,
                                 int width, int depth, int widerSide) {
        for (int side : new int[]{-1, 1}) {
            int extent = side == widerSide ? width / 2 : (width - 1) / 2;
            for (int offset = 1; offset <= extent; offset++) {
                Cell lip = surfaceAlong(terrain, center, alongX, 0, side * offset, center.y);
                if (lip == null) break;
                cutColumn(cut, terrain, lip, Math.max(1, depth - 3 * offset));
            }
        }
    }

    private static List<Site> findSites(Earthquake quake, Terrain terrain) {
        int originX = (int) Math.floor(quake.x);
        int originY = (int) Math.floor(quake.y);
        int originZ = (int) Math.floor(quake.z);
        var sites = new ArrayList<Site>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += SEARCH_STEP) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += SEARCH_STEP) {
                int distance = dx * dx + dz * dz;
                if (distance > SEARCH_RADIUS * SEARCH_RADIUS) continue;

                int x = originX + dx;
                int z = originZ + dz;
                Cell surface = surfaceAt(terrain, x, z);
                if (surface == null) continue;
                int depth = cavityDepth(terrain, surface, originY);
                boolean aboveCave = depth > 0;
                sites.add(new Site(surface, aboveCave, distance + (aboveCave ? depth * depth : 0)));
            }
        }
        sites.sort(Comparator.comparing(Site::aboveCave).reversed().thenComparingInt(Site::score));
        return sites;
    }

    private static int cavityDepth(Terrain terrain, Cell surface, int originY) {
        int depthLimit = Math.clamp(surface.y - originY + 12, SEARCH_DEPTH, 72);
        int air = 0;
        boolean naturalRoof = true;
        for (int y = surface.y - 1; y >= Math.max(2, surface.y - depthLimit); y--) {
            Material material = terrain.at(surface.x, y, surface.z);
            if (material == Material.AIR) {
                if (naturalRoof) air++;
                continue;
            }
            if (material == Material.NATURAL && air >= HEADROOM) return surface.y - (y + air);
            naturalRoof = material == Material.NATURAL;
            air = 0;
        }
        return -1;
    }

    private static Cell surfaceAt(Terrain terrain, int x, int z) {
        int y = terrain.surface(x, z);
        if (y < 4) return null;
        if (terrain.at(x, y, z) == Material.AIR) y--;
        if (terrain.at(x, y, z) == Material.GRASS) y--;
        Material above = terrain.at(x, y + 1, z);
        return terrain.at(x, y, z) == Material.NATURAL
            && (above == Material.AIR || above == Material.GRASS)
            && terrain.at(x, y + 2, z) == Material.AIR
            && terrain.at(x, y - 1, z) == Material.NATURAL ? new Cell(x, y, z) : null;
    }

    private static Cell surfaceAlong(Terrain terrain, Cell origin, boolean alongX, int axis, int lateral, int elevation) {
        int x = origin.x + (alongX ? axis : lateral);
        int z = origin.z + (alongX ? lateral : axis);
        Cell surface = surfaceAt(terrain, x, z);
        return surface != null && Math.abs(surface.y - elevation) <= 2 && Math.abs(surface.y - origin.y) <= 6
            ? surface : null;
    }

    private static void cutColumn(LinkedHashSet<Cell> cut, Terrain terrain, Cell top, int depth) {
        for (int y = top.y; y > top.y - depth && terrain.at(top.x, y, top.z) == Material.NATURAL; y--) {
            cut.add(new Cell(top.x, y, top.z));
        }
    }

    interface Terrain {
        int surface(int x, int z);

        Material at(int x, int y, int z);
    }

    enum Material { AIR, NATURAL, GRASS, BLOCKED }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    record Cell(int x, int y, int z) {
        long column() {
            return EarthquakeFissures.column(x, z);
        }
    }

    private record Shape(int halfLength, int width, int depth) { }

    private record Site(Cell surface, boolean aboveCave, int score) { }
}
