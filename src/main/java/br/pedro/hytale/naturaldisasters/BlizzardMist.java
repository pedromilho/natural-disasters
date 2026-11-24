package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3dc;

final class BlizzardMist {
    private static final String EFFECT = "NaturalDisasters_BlizzardMist";
    private static final int REFRESH_SECONDS = 2;
    private static final int VIEW_DISTANCE = 176;
    private static final float MAX_EFFECT_SECONDS = 3.6f;
    private static final int RINGS = 4;
    private static final float MIST_SCALE = 1.5f;
    private static final double ABOVE_SURFACE = 6.0 * MIST_SCALE;
    private static final List<Offset> OFFSETS = mistOffsets();

    private BlizzardMist() { }

    static boolean refreshDue(Blizzard storm) {
        return storm.phase() == Blizzard.Phase.ACTIVE
            && (Blizzard.ACTIVE_SECONDS - storm.remainingSeconds()) % REFRESH_SECONDS == 0;
    }

    static void refresh(Store<EntityStore> store, Blizzard storm) {
        World world = store.getExternalData().getWorld();
        List<Ref<EntityStore>> viewers = viewers(store, world, storm);
        if (viewers.isEmpty()) return;

        ChunkStore chunks = world.getChunkStore();
        for (Offset offset : OFFSETS) {
            int x = (int) Math.floor(storm.centerX() + offset.x);
            int z = (int) Math.floor(storm.centerZ() + offset.z);
            int surfaceY = LoadedTerrain.surface(chunks, x, z);
            if (surfaceY == HeightmapColumn.NO_HEIGHT) continue;
            ParticleUtil.spawnParticleEffect(EFFECT, x + 0.5, surfaceY + ABOVE_SURFACE, z + 0.5,
                0, 0, 0, MIST_SCALE, null, null, viewers, store, MAX_EFFECT_SECONDS);
        }
    }

    private static List<Ref<EntityStore>> viewers(Store<EntityStore> store, World world, Blizzard storm) {
        var viewers = new ArrayList<Ref<EntityStore>>();
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) continue;

            Player entity = store.getComponent(ref, Player.getComponentType());
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (entity == null || entity.isWaitingForClientReady() || transform == null) continue;

            Vector3dc position = transform.getPosition();
            if (storm.contains(position.x(), position.z(), Blizzard.RADIUS + VIEW_DISTANCE)) viewers.add(ref);
        }
        return viewers;
    }

    private static List<Offset> mistOffsets() {
        var offsets = new ArrayList<Offset>();
        offsets.add(new Offset(0, 0));
        // Stagger the rings so distant fog reads as a bank instead of a row of emitters.
        for (int ring = 1; ring <= RINGS; ring++) {
            int count = ring * 6;
            double radius = ring * (Blizzard.RADIUS - 9.0 * MIST_SCALE) / RINGS;
            for (int index = 0; index < count; index++) {
                double angle = Math.TAU * (index + (ring % 2 == 0 ? 0.5 : 0.0)) / count;
                offsets.add(new Offset(radius * Math.cos(angle), radius * Math.sin(angle)));
            }
        }
        return List.copyOf(offsets);
    }

    private record Offset(double x, double z) { }
}
