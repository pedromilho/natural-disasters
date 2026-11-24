package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.ChangeVelocityType;
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
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3d;
import org.joml.Vector3dc;

final class BlizzardSystem extends TickingSystem<EntityStore> {
    private static final String COLD_DAMAGE_ID = "Ice";
    private static final String LIGHT_SNOW_EFFECT = "Snow_Light";
    private static final float WEATHER_TRANSITION_SECONDS = 2.0f;
    private static final float WARNING_SNOW_SECONDS = 1.2f;
    private static final int CAMPFIRE_RADIUS = 3;
    private static final VelocityConfig WIND_RESISTANCE = windResistance();

    // Each world thread owns its storm and participants. Different worlds can tick concurrently.
    private final Map<World, ActiveBlizzard> storms = new ConcurrentHashMap<>();

    Blizzard get(World world) {
        ActiveBlizzard active = storms.get(world);
        return active == null ? null : active.storm;
    }

    void start(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef initiator) {
        World world = store.getExternalData().getWorld();
        if (storms.containsKey(world)) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;
        Vector3dc position = transform.getPosition();
        var storm = new Blizzard(position.x(), position.z());
        int weatherIndex = Weather.getAssetMap().getIndex(storm.wind().weatherId());
        DamageCause coldDamage = DamageCause.getAssetMap().getAsset(COLD_DAMAGE_ID);
        if (weatherIndex <= 0 || coldDamage == null) {
            notify(initiator, DisasterText.UNAVAILABLE);
            return;
        }
        var active = new ActiveBlizzard(storm, weatherIndex, coldDamage,
            campfireState("Processing"), campfireState("ProcessCompleted"), new HashMap<>());
        storms.put(world, active);
        updatePlayers(store, active);
    }

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        ActiveBlizzard active = storms.get(store.getExternalData().getWorld());
        if (active == null) return;
        boolean countdownChanged = active.storm.tick(dt);
        if (active.storm.phase() == Blizzard.Phase.IDLE) {
            stop(store, DisasterText.ENDED);
            return;
        }
        if (countdownChanged) {
            updatePlayers(store, active);
            if (BlizzardMist.refreshDue(active.storm)) BlizzardMist.refresh(store, active.storm);
        }
        double increment = active.storm.windIncrement();
        if (increment > 0) updateWind(store, active.storm, increment);
    }

    void stop(Store<EntityStore> store, DisasterText reason) {
        World world = store.getExternalData().getWorld();
        ActiveBlizzard active = storms.remove(world);
        if (active == null) return;

        for (var entry : active.participants.entrySet()) {
            Ref<EntityStore> ref = entry.getKey();
            if (!ref.isValid()) continue;
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null) continue;
            clearEffects(store, ref, player, entry.getValue());
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
                if (participant != null) clearEffects(store, ref, player, participant);
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
            switch (phase) {
                case WARNING_SNOW -> notify(player, DisasterText.ALERT, active.storm.remainingSeconds());
                case WARNING_WIND -> notify(player, DisasterText.WIND_RISING, active.storm.remainingSeconds());
                case ACTIVE -> notify(player, DisasterText.ARRIVAL);
                case IDLE -> { }
            }
            participant.lastPhase = phase;
        }
        Vector3dc position = transform.getPosition();
        boolean exposed = isOutside(world.getChunkStore(), position);
        if (phase != Blizzard.Phase.ACTIVE) {
            if (exposed) showWarningSnow(store, ref, position, active.storm);
            return;
        }
        if (exposed) {
            applyWeather(store, ref, player, participant, active.weatherIndex);
        } else {
            restoreWeather(store, ref, player, transform, participant);
        }
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null || entity.getGameMode() != GameMode.Adventure
            || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            participant.exposure.reset();
            participant.visibleFrost = 0;
            participant.visibleTint = 0;
            if (entity != null) FrostHud.setLevels(entity.getHudManager(), player, 0, 0);
            return;
        }
        if (phaseChanged) return;

        boolean warm = !exposed || nearCampfire(world.getChunkStore(), position, active);
        participant.exposure.tick(warm);
        updateFrost(store, ref, player, entity, participant, warm);
        if (warm) return;

        if (participant.exposure.warn()) notify(player, DisasterText.COLD);
        if (participant.exposure.damageDue()) {
            // Apply damage outside the ECS tick, where damage handlers may add components.
            world.execute(() -> {
                if (storms.get(world) == active && ref.isValid() && !store.isShutdown()) {
                    DamageSystems.executeDamage(ref, store, new Damage(Damage.NULL_SOURCE, active.coldDamage, Blizzard.DAMAGE));
                }
            });
        }
    }

    private static void showWarningSnow(Store<EntityStore> store, Ref<EntityStore> ref, Vector3dc position,
                                        Blizzard storm) {
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null || entity.isWaitingForClientReady()) return;

        boolean windy = storm.phase() == Blizzard.Phase.WARNING_WIND;
        String particle = windy ? storm.wind().snowEffectId() : LIGHT_SNOW_EFFECT;
        float scale = windy ? 0.75f : 1.0f;
        ParticleUtil.spawnParticleEffect(particle, position.x(), position.y() + 1, position.z(),
            0, 0, 0, scale, null, null, List.of(ref), store, WARNING_SNOW_SECONDS);
    }

    private static void updateWind(Store<EntityStore> store, Blizzard storm, double increment) {
        World world = store.getExternalData().getWorld();
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) continue;

            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity == null || entity.getGameMode() != GameMode.Adventure || entity.isWaitingForClientReady()
                || store.getComponent(ref, DeathComponent.getComponentType()) != null) continue;

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) continue;
            Vector3dc position = transform.getPosition();
            if (storm.contains(position.x(), position.z()) && isOutside(world.getChunkStore(), position)) {
                applyWind(store, ref, storm.wind(), increment);
            }
        }
    }

    private static void applyWind(Store<EntityStore> store, Ref<EntityStore> ref, WindDirection direction,
                                  double increment) {
        Velocity velocity = store.getComponent(ref, Velocity.getComponentType());
        if (velocity != null) {
            var drift = new Vector3d(direction.x() * increment, 0.0, direction.z() * increment);
            velocity.addInstruction(drift, WIND_RESISTANCE, ChangeVelocityType.Add);
        }
    }

    private static VelocityConfig windResistance() {
        var config = new VelocityConfig();
        config.setGroundResistance(0.96f);
        config.setAirResistance(0.96f);
        config.setThreshold(0.02f);
        return config;
    }

    private static void updateFrost(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Player entity,
                                    Participant participant, boolean warm) {
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        var health = stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
        float healthRemaining = health == null ? Float.POSITIVE_INFINITY : health.get() - health.getMin();
        int cracks = participant.exposure.frostLevel(healthRemaining);
        int tint = participant.exposure.tintLevel(healthRemaining);
        if (warm) {
            cracks = Math.min(cracks, Math.max(0, participant.visibleFrost - 1));
            tint = Math.min(tint, Math.max(0, participant.visibleTint - 1));
        }
        participant.visibleFrost = cracks;
        participant.visibleTint = tint;
        FrostHud.setLevels(entity.getHudManager(), player, cracks, tint);
    }

    private static void clearEffects(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player,
                                     Participant participant) {
        restoreWeather(store, ref, player, store.getComponent(ref, TransformComponent.getComponentType()), participant);
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity != null) FrostHud.setLevels(entity.getHudManager(), player, 0, 0);
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
        HeightmapColumn heightmap = loadedHeightmap(chunks, x, z);
        return heightmap != null && heightmap.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK)
            < position.y() + 1.6;
    }

    static HeightmapColumn loadedHeightmap(ChunkStore chunks, int x, int z) {
        Ref<ChunkStore> column = chunks.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (column == null || !column.isValid()) return null;
        HeightmapColumn heightmap = chunks.getStore().getComponent(column, HeightmapColumn.getComponentType());
        return heightmap != null && heightmap.getHeightmap() != null ? heightmap : null;
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
        private final Blizzard.Exposure exposure = new Blizzard.Exposure();
        private int visibleFrost;
        private int visibleTint;
        private boolean weatherClaimed;
    }
}
