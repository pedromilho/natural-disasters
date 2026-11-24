package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3dc;

final class BlizzardSystem extends TickingSystem<EntityStore> {
    private static final String WEATHER_ID = "NaturalDisasters_Blizzard";
    private static final String COLD_DAMAGE_ID = "Ice";
    private static final float WEATHER_TRANSITION_SECONDS = 2.0f;
    private static final int CAMPFIRE_RADIUS = 3;

    // Each world thread owns its storm and participants. Different worlds can tick concurrently.
    private final Map<World, ActiveBlizzard> storms = new ConcurrentHashMap<>();

    Blizzard get(World world) {
        ActiveBlizzard active = storms.get(world);
        return active == null ? null : active.storm;
    }

    void start(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef initiator) {
        World world = store.getExternalData().getWorld();
        if (storms.containsKey(world)) return;

        int weatherIndex = Weather.getAssetMap().getIndex(WEATHER_ID);
        DamageCause coldDamage = DamageCause.getAssetMap().getAsset(COLD_DAMAGE_ID);
        if (weatherIndex <= 0 || coldDamage == null) {
            notify(initiator, DisasterText.UNAVAILABLE);
            return;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3dc position = transform.getPosition();
        var active = new ActiveBlizzard(new Blizzard(position.x(), position.z()), weatherIndex, coldDamage,
            campfireState("Processing"), campfireState("ProcessCompleted"), new HashMap<>());
        storms.put(world, active);
        updatePlayers(store, active);
    }

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        ActiveBlizzard active = storms.get(store.getExternalData().getWorld());
        if (active == null || !active.storm.tick(dt)) return;
        if (active.storm.phase() == Blizzard.Phase.IDLE) {
            stop(store, DisasterText.ENDED);
        } else {
            updatePlayers(store, active);
        }
    }

    void stop(Store<EntityStore> store, DisasterText reason) {
        World world = store.getExternalData().getWorld();
        ActiveBlizzard active = storms.remove(world);
        if (active == null) return;

        for (var entry : active.participants.entrySet()) {
            Ref<EntityStore> ref = entry.getKey();
            if (!ref.isValid()) continue;
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (player == null) continue;
            restoreWeather(store, ref, player, transform, entry.getValue());
            notify(player, reason);
        }
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref != null && ref.isValid() && ref.getStore() == store) refreshPanel(world, store, ref);
        }
    }

    void remove(World world) {
        storms.remove(world);
    }

    void shutdown() {
        for (World world : storms.keySet()) {
            if (world.isAlive()) {
                world.execute(() -> {
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    if (!store.isShutdown()) stop(store, DisasterText.CANCELLED);
                });
            }
        }
    }

    private void updatePlayers(Store<EntityStore> store, ActiveBlizzard active) {
        World world = store.getExternalData().getWorld();
        active.participants.keySet().removeIf(ref -> !ref.isValid() || ref.getStore() != store);
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) continue;

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform != null && active.storm.contains(transform.getPosition().x, transform.getPosition().z)) {
                updateParticipant(world, store, ref, player, transform, active);
            } else {
                Participant participant = active.participants.remove(ref);
                if (participant != null) restoreWeather(store, ref, player, transform, participant);
            }
            refreshPanel(world, store, ref);
        }
    }

    private void updateParticipant(World world, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player,
                                   TransformComponent transform, ActiveBlizzard active) {
        Participant participant = active.participants.computeIfAbsent(ref, ignored -> new Participant());
        Blizzard.Phase phase = active.storm.phase();
        boolean phaseChanged = participant.lastPhase != phase;
        if (phaseChanged) {
            if (phase == Blizzard.Phase.WARNING) {
                notify(player, DisasterText.ALERT, active.storm.remainingSeconds());
            } else {
                notify(player, DisasterText.ARRIVAL);
            }
            participant.lastPhase = phase;
        }
        if (phase != Blizzard.Phase.ACTIVE) return;

        Vector3dc position = transform.getPosition();
        boolean exposed = isOutside(world.getChunkStore(), position);
        if (exposed) {
            applyWeather(store, ref, player, participant, active.weatherIndex);
        } else {
            restoreWeather(store, ref, player, transform, participant);
        }
        if (phaseChanged) return;

        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null || entity.getGameMode() != GameMode.Adventure
            || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            participant.exposure = new Blizzard.Exposure();
            return;
        }
        boolean warm = !exposed || nearCampfire(world.getChunkStore(), position, active);
        participant.exposure.tick(warm);
        if (warm) return;

        if (participant.exposure.warn()) notify(player, DisasterText.COLD);
        if (participant.exposure.damageDue()) {
            // Apply damage outside the ECS tick, where damage handlers may add components.
            world.execute(() -> {
                if (storms.get(world) == active && ref.isValid() && !store.isShutdown()) {
                    DamageSystems.executeDamage(ref, store, new Damage(Damage.NULL_SOURCE, active.coldDamage, 1.0f));
                }
            });
        }
    }

    private static void applyWeather(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player,
                                     Participant participant, int weatherIndex) {
        WeatherTracker tracker = store.getComponent(ref, WeatherTracker.getComponentType());
        participant.weatherClaimed = tracker != null && tracker.tryClaimOverride(ref, weatherIndex);
        if (participant.weatherClaimed && tracker.getWeatherIndex() != weatherIndex) {
            tracker.sendWeatherIndex(player, weatherIndex, WEATHER_TRANSITION_SECONDS);
        }
    }

    private static void restoreWeather(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player,
                                       TransformComponent transform, Participant participant) {
        if (!participant.weatherClaimed) return;
        participant.weatherClaimed = false;
        WeatherTracker tracker = store.getComponent(ref, WeatherTracker.getComponentType());
        if (tracker != null && tracker.releaseOverride(ref) && transform != null) {
            tracker.updateWeather(player, store.getResource(WeatherResource.getResourceType()), transform,
                WEATHER_TRANSITION_SECONDS, store);
        }
    }

    private static boolean isOutside(ChunkStore chunks, Vector3dc position) {
        int x = (int) Math.floor(position.x());
        int z = (int) Math.floor(position.z());
        Ref<ChunkStore> column = chunks.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (column == null || !column.isValid()) return false;
        HeightmapColumn heightmap = chunks.getStore().getComponent(column, HeightmapColumn.getComponentType());
        return heightmap != null && heightmap.getHeightmap() != null
            && heightmap.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK) < position.y() + 1.6;
    }

    private static int campfireState(String state) {
        BlockType campfire = BlockType.getAssetMap().getAsset("Bench_Campfire");
        BlockType lit = campfire == null ? null : campfire.getBlockForState(state);
        return lit == null ? -1 : BlockType.getAssetMap().getIndex(lit.getId());
    }

    private static boolean nearCampfire(ChunkStore chunks, Vector3dc position, ActiveBlizzard active) {
        if (active.cookingCampfire <= 0 && active.finishedCampfire <= 0) return false;
        int x = (int) Math.floor(position.x());
        int feet = (int) Math.floor(position.y());
        int z = (int) Math.floor(position.z());
        for (int dx = -CAMPFIRE_RADIUS; dx <= CAMPFIRE_RADIUS; dx++) {
            for (int dz = -CAMPFIRE_RADIUS; dz <= CAMPFIRE_RADIUS; dz++) {
                if (dx * dx + dz * dz > CAMPFIRE_RADIUS * CAMPFIRE_RADIUS) continue;
                for (int y = feet - 1; y <= feet + 1; y++) {
                    Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x + dx, y, z + dz);
                    if (section == null || !section.isValid()) continue;
                    BlockSection blocks = chunks.getStore().getComponent(section, BlockSection.getComponentType());
                    if (blocks == null) continue;
                    int block = blocks.get(x + dx, y, z + dz);
                    if (block > 0 && (block == active.cookingCampfire || block == active.finishedCampfire)) return true;
                }
            }
        }
        return false;
    }

    private static void refreshPanel(World world, Store<EntityStore> store, Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getPageManager().getCustomPage() instanceof DisasterTestPage page) {
            page.refresh(world);
        }
    }

    private static void notify(PlayerRef player, DisasterText text, Object... values) {
        player.sendMessage(Message.raw("[Natural Disasters] " + text.get(player, values)));
    }

    private record ActiveBlizzard(Blizzard storm, int weatherIndex, DamageCause coldDamage, int cookingCampfire,
                                  int finishedCampfire, Map<Ref<EntityStore>, Participant> participants) { }

    private static final class Participant {
        private Blizzard.Phase lastPhase;
        private Blizzard.Exposure exposure = new Blizzard.Exposure();
        private boolean weatherClaimed;
    }
}
