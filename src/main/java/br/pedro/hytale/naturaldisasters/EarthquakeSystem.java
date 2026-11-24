package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.builtin.adventure.camera.asset.camerashake.CameraShake;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.AccumulationMode;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.camera.CameraShakeEffect;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.protocol.packets.world.SetAudioState;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.audiostate.config.AudioState;
import com.hypixel.hytale.server.core.asset.type.audiostate.config.AudioStateResolver;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.joml.Vector3d;
import org.joml.Vector3dc;

final class EarthquakeSystem extends TickingSystem<EntityStore> {
    private static final String SHAKE = "NaturalDisasters_Earthquake";
    private static final String AUDIO_STATE = "NaturalDisasters_Earthquake_Audio";
    private static final String WARNING_SOUND = "NaturalDisasters_Earthquake_Warning";
    private static final String RUPTURE_SOUND = "NaturalDisasters_Earthquake_Rupture";
    private static final String DUST = "NaturalDisasters_Earthquake_Dust";
    private static final float SHAKE_INTERVAL_SECONDS = 0.25f;
    private static final int RUMBLE_INTERVAL_SECONDS = 2;
    private static final int MAX_RUPTURE_PLANS = 5;
    private static final VelocityConfig JOLT_RESISTANCE = joltResistance();
    private final Map<World, ActiveQuake> earthquakes = new ConcurrentHashMap<>();

    Earthquake get(World world) {
        ActiveQuake active = earthquakes.get(world);
        return active == null ? null : active.quake;
    }

    void start(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, Earthquake.Magnitude magnitude) {
        World world = store.getExternalData().getWorld();
        if (earthquakes.containsKey(world) || ref == null || !ref.isValid() || ref.getStore() != store) return;
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        int shakeIndex = CameraShake.getAssetMap().getIndex(SHAKE);
        int soundIndex = SoundEvent.getAssetMap().getIndex(rumbleAsset(magnitude));
        int warningIndex = SoundEvent.getAssetMap().getIndex(WARNING_SOUND);
        int ruptureIndex = SoundEvent.getAssetMap().getIndex(RUPTURE_SOUND);
        AudioControl audio = AudioControl.load();
        if (shakeIndex < 0 || soundIndex <= 0 || warningIndex <= 0 || ruptureIndex <= 0 || audio == null) {
            notify(player, DisasterText.QUAKE_UNAVAILABLE);
            return;
        }

        Vector3dc position = transform.getPosition();
        var quake = new Earthquake(position.x(), position.y(), position.z(), magnitude,
            ThreadLocalRandom.current().nextDouble());
        List<List<EarthquakeFissures.Cell>> rupturePlans = isStrong(quake)
            ? EarthquakeFissures.plans(quake, new EarthquakeTerrain(world.getChunkStore()),
                ThreadLocalRandom.current(), MAX_RUPTURE_PLANS)
            : List.of();
        if (isStrong(quake) && rupturePlans.isEmpty()) {
            notify(player, DisasterText.QUAKE_NO_SURFACE);
            return;
        }
        var active = new ActiveQuake(quake, ref, shakeIndex, soundIndex, ruptureIndex, rupturePlans, audio);
        earthquakes.put(world, active);
        float warningVolume = switch (magnitude) {
            case LIGHT -> 0.42f;
            case MODERATE -> 0.75f;
            case STRONG -> 1.0f;
        };
        for (Ref<EntityStore> nearby : nearbyPlayers(store, quake)) {
            PlayerRef recipient = store.getComponent(nearby, PlayerRef.getComponentType());
            notify(recipient, DisasterText.QUAKE_ALERT, magnitudeText(magnitude).get(recipient), Earthquake.WARNING_SECONDS);
            if (magnitude != Earthquake.Magnitude.LIGHT) {
                title(active, recipient, DisasterText.QUAKE_WARNING_TITLE,
                    isStrong(quake) ? DisasterText.QUAKE_WARNING_SPLIT : DisasterText.QUAKE_WARNING_MOVE, 2.55f);
            }
            setAudio(active, recipient, audio.warning);
            SoundUtil.playSoundEvent2dToPlayer(recipient, warningIndex, SoundCategory.SFX, warningVolume, 1.0f);
        }
        refreshPanels(world, store);
    }

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        ActiveQuake active = earthquakes.get(world);
        if (active == null) return;
        active.titleSeconds -= dt;
        boolean countdownChanged = active.quake.tick(dt);
        if (active.quake.phase() == Earthquake.Phase.FINISHED) {
            stop(store, DisasterText.QUAKE_ENDED);
            return;
        }
        if (active.quake.phase() == Earthquake.Phase.ACTIVE) {
            if (isStrong(active.quake) && !active.strongOpened) {
                if (active.quake.takeRuptureAttempt()) world.execute(() -> openFissure(world, store, active));
                return;
            }
            List<Ref<EntityStore>> nearby = nearbyPlayers(store, active.quake);
            active.shakeCooldown -= dt;
            if (active.shakeCooldown <= 0) shakePlayers(store, active, nearby, 1.0f);
            joltPlayers(store, active, nearby, dt);
            if (countdownChanged) {
                active.headings.keySet().retainAll(nearby);
                pulse(store, active, nearby);
            }
            if (active.quake.takeRuptureAttempt()) {
                // Block edits and cancellable break events run outside the entity store tick.
                world.execute(() -> openFissure(world, store, active));
            }
        }
        if (countdownChanged) refreshPanels(world, store);
    }

    void stop(Store<EntityStore> store, DisasterText message) {
        World world = store.getExternalData().getWorld();
        ActiveQuake active = earthquakes.remove(world);
        if (active == null) return;
        active.audioListeners.forEach((player, state) -> clearEffects(active, player, state));
        for (Ref<EntityStore> ref : nearbyPlayers(store, active.quake)) {
            notify(store.getComponent(ref, PlayerRef.getComponentType()), message);
        }
        refreshPanels(world, store);
    }

    void remove(World world) {
        earthquakes.remove(world);
    }

    void removeListener(World world, PlayerRef player) {
        ActiveQuake active = earthquakes.get(world);
        if (active == null) return;
        Integer state = active.audioListeners.remove(player);
        if (state != null) clearEffects(active, player, state);
    }

    void shutdown() {
        earthquakes.clear();
    }

    static DisasterText magnitudeText(Earthquake.Magnitude magnitude) {
        return switch (magnitude) {
            case LIGHT -> DisasterText.QUAKE_LIGHT;
            case MODERATE -> DisasterText.QUAKE_MODERATE;
            case STRONG -> DisasterText.QUAKE_STRONG;
        };
    }

    private static String rumbleAsset(Earthquake.Magnitude magnitude) {
        return switch (magnitude) {
            case LIGHT -> "NaturalDisasters_Earthquake_Light";
            case MODERATE -> "NaturalDisasters_Earthquake_Moderate";
            case STRONG -> "NaturalDisasters_Earthquake_Strong";
        };
    }

    private static boolean isStrong(Earthquake quake) {
        return quake.magnitude == Earthquake.Magnitude.STRONG;
    }

    private static void shakePlayers(Store<EntityStore> store, ActiveQuake active, List<Ref<EntityStore>> nearby,
                                     float multiplier) {
        active.shakeCooldown = SHAKE_INTERVAL_SECONDS;
        active.audioListeners.entrySet().removeIf(listener -> {
            PlayerRef player = listener.getKey();
            if (nearby.contains(player.getReference())) return false;
            clearEffects(active, player, listener.getValue());
            return true;
        });
        for (Ref<EntityStore> ref : nearby) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3dc position = store.getComponent(ref, TransformComponent.getComponentType()).getPosition();
            float intensity = Math.min(1.0f, active.quake.intensityAt(position.x(), position.z()) * multiplier);
            setAudio(active, player, active.audio.rumble);
            player.getPacketHandler().writeNoCache(new CameraShakeEffect(active.shakeIndex, intensity, AccumulationMode.Set));
        }
    }

    private static void setAudio(ActiveQuake active, PlayerRef player, int state) {
        Integer previous = active.audioListeners.put(player, state);
        if (previous == null || previous != state) {
            player.getPacketHandler().writeNoCache(new SetAudioState(active.audio.index, state, null));
        }
    }

    private static void clearEffects(ActiveQuake active, PlayerRef player, int previousAudioState) {
        player.getPacketHandler().writeNoCache(new SetAudioState(active.audio.index, active.audio.quiet, null));
        if (previousAudioState == active.audio.rumble) {
            player.getPacketHandler().writeNoCache(new CameraShakeEffect(active.shakeIndex, 0, AccumulationMode.Set));
        }
        if (active.titleViewers.remove(player) && active.titleSeconds > 0) {
            EventTitleUtil.hideEventTitleFromPlayer(player, 0);
        }
    }

    private static void pulse(Store<EntityStore> store, ActiveQuake active, List<Ref<EntityStore>> nearby) {
        int second = active.quake.magnitude.duration - active.quake.remainingSeconds();
        boolean playRumble = second >= active.nextRumbleSecond;
        if (playRumble) active.nextRumbleSecond = second + RUMBLE_INTERVAL_SECONDS;
        for (Ref<EntityStore> ref : nearby) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3dc position = store.getComponent(ref, TransformComponent.getComponentType()).getPosition();
            float intensity = active.quake.intensityAt(position.x(), position.z());
            if (playRumble) {
                setAudio(active, player, active.audio.rumble);
                SoundUtil.playSoundEvent2dToPlayer(player, active.soundIndex, SoundCategory.SFX,
                    0.64f + 0.25f * intensity, 1.0f);
            }
            MovementStatesComponent movement = store.getComponent(ref, MovementStatesComponent.getComponentType());
            var states = movement == null ? null : movement.getMovementStates();
            if (second % 2 == 0 && states != null && states.onGround) {
                dust(store, List.of(ref), position.x(), position.y() + 0.06, position.z(), 0.8f + intensity);
            }
        }
    }

    private static void joltPlayers(Store<EntityStore> store, ActiveQuake active, List<Ref<EntityStore>> nearby, float dt) {
        var random = ThreadLocalRandom.current();
        for (Ref<EntityStore> ref : nearby) {
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) continue;
            Velocity velocity = store.getComponent(ref, Velocity.getComponentType());
            if (velocity == null) continue;

            Vector3dc position = store.getComponent(ref, TransformComponent.getComponentType()).getPosition();
            MovementStatesComponent movement = store.getComponent(ref, MovementStatesComponent.getComponentType());
            var states = movement == null ? null : movement.getMovementStates();
            double groundContact = states != null && !states.onGround ? 0.45 : 1.0;
            double amount = active.quake.jolt(active.quake.intensityAt(position.x(), position.z()), dt) * groundContact;
            if (amount <= 0) continue;
            Double lastDirection = active.headings.get(ref);
            // Related directions on consecutive ticks give the player a sustained shove that still changes randomly.
            double direction = lastDirection == null ? random.nextDouble(Math.TAU)
                : lastDirection + random.nextDouble(-1.0, 1.0);
            active.headings.put(ref, direction);
            applyJolt(velocity, amount, direction);
        }
    }

    private static void applyJolt(Velocity velocity, double amount, double direction) {
        var push = new Vector3d(Math.cos(direction) * amount, 0.0, Math.sin(direction) * amount);
        velocity.addInstruction(push, JOLT_RESISTANCE, ChangeVelocityType.Add);
    }

    private static VelocityConfig joltResistance() {
        var config = new VelocityConfig();
        config.setGroundResistance(0.80f);
        config.setAirResistance(0.83f);
        config.setThreshold(0.015f);
        return config;
    }

    private void openFissure(World world, Store<EntityStore> store, ActiveQuake active) {
        if (earthquakes.get(world) != active || store.isShutdown()) return;
        if (!active.initiator.isValid()) {
            if (isStrong(active.quake)) stop(store, DisasterText.QUAKE_CANCELLED);
            return;
        }
        var terrain = new EarthquakeTerrain(world.getChunkStore());
        boolean strong = isStrong(active.quake);
        List<EarthquakeFissures.Cell> carved = strong ? carveLarge(store, active, terrain)
            : carveOrdinary(store, active, terrain);
        if (carved.isEmpty()) {
            if (strong && earthquakes.get(world) == active) stop(store, DisasterText.QUAKE_NO_SURFACE);
            return;
        }
        List<Ref<EntityStore>> viewers = nearbyPlayers(store, active.quake);
        if (strong) {
            active.strongOpened = true;
            active.titleViewers.clear();
            shakePlayers(store, active, viewers, 1.4f);
            pulse(store, active, viewers);
            refreshPanels(world, store);
        }
        EarthquakeFissures.Cell center = carved.getFirst();
        for (int index = 0; index < carved.size(); index += Math.max(4, carved.size() / (strong ? 10 : 5))) {
            EarthquakeFissures.Cell cell = carved.get(index);
            dust(store, viewers, cell.x() + 0.5, cell.y() + 1.0, cell.z() + 0.5, strong ? 2.7f : 1.6f);
        }
        for (Ref<EntityStore> ref : viewers) {
            PlayerRef viewer = store.getComponent(ref, PlayerRef.getComponentType());
            setAudio(active, viewer, active.audio.rumble);
            SoundUtil.playSoundEvent2dToPlayer(viewer, active.ruptureIndex, SoundCategory.SFX, strong ? 1.0f : 0.52f, 1.0f);
            if (strong) {
                title(active, viewer, DisasterText.QUAKE_RUPTURE_TITLE, DisasterText.QUAKE_RUPTURE_DETAIL, 1.9f);
            }
            notify(viewer, DisasterText.QUAKE_FISSURE, center.x(), center.z());
        }
    }

    private static List<EarthquakeFissures.Cell> carveOrdinary(Store<EntityStore> store, ActiveQuake active,
                                                             EarthquakeTerrain terrain) {
        var carved = new ArrayList<EarthquakeFissures.Cell>();
        var blockedColumns = new HashSet<Long>();
        for (EarthquakeFissures.Cell cell : EarthquakeFissures.plan(active.quake, terrain, ThreadLocalRandom.current())) {
            if (!active.initiator.isValid()) break;
            if (blockedColumns.contains(cell.column())) continue;
            if (terrain.open(cell, store, active.initiator)) carved.add(cell);
            else blockedColumns.add(cell.column());
        }
        return carved;
    }

    private static List<EarthquakeFissures.Cell> carveLarge(Store<EntityStore> store, ActiveQuake active,
                                                          EarthquakeTerrain terrain) {
        for (List<EarthquakeFissures.Cell> plan : active.rupturePlans) {
            var changes = new ArrayList<EarthquakeTerrain.Change>(plan.size());
            var blockedColumns = new HashSet<Long>();
            for (EarthquakeFissures.Cell cell : plan) {
                if (blockedColumns.contains(cell.column())) continue;
                EarthquakeTerrain.Change change = terrain.authorize(cell, store, active.initiator);
                if (change != null) changes.add(change);
                else blockedColumns.add(cell.column());
            }
            List<EarthquakeFissures.Cell> approved = changes.stream().map(EarthquakeTerrain.Change::cell).toList();
            if (!EarthquakeFissures.isLarge(approved) || !changes.stream().allMatch(terrain::matches)) continue;

            var applied = new ArrayList<EarthquakeTerrain.Change>();
            for (EarthquakeTerrain.Change change : changes) {
                if (!terrain.open(change)) break;
                applied.add(change);
            }
            if (applied.size() == changes.size()) return approved;
            for (EarthquakeTerrain.Change change : applied.reversed()) terrain.restore(change);
        }
        return List.of();
    }

    private static void dust(Store<EntityStore> store, List<Ref<EntityStore>> viewers, double x, double y, double z,
                             float scale) {
        ParticleUtil.spawnParticleEffect(DUST, x, y, z, 0, 0, 0, scale, null, null, viewers, store, 1.4f);
    }

    private static List<Ref<EntityStore>> nearbyPlayers(Store<EntityStore> store, Earthquake quake) {
        var nearby = new ArrayList<Ref<EntityStore>>();
        for (PlayerRef player : store.getExternalData().getWorld().getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) continue;

            Player entity = store.getComponent(ref, Player.getComponentType());
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (entity == null || entity.isWaitingForClientReady() || transform == null) continue;

            Vector3dc position = transform.getPosition();
            if (quake.intensityAt(position.x(), position.z()) > 0) nearby.add(ref);
        }
        return nearby;
    }

    private static void refreshPanels(World world, Store<EntityStore> store) {
        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            if (ref != null && ref.isValid() && ref.getStore() == store) DisasterTestPage.refreshPlayer(world, store, ref);
        }
    }

    private static void notify(PlayerRef player, DisasterText text, Object... values) {
        if (player != null) player.sendMessage(Message.raw("[Natural Disasters] " + text.get(player, values)));
    }

    private static void title(ActiveQuake active, PlayerRef player, DisasterText headline, DisasterText subtitle,
                              float hold) {
        float fadeIn = 0.08f, fadeOut = 0.25f;
        active.titleViewers.add(player);
        active.titleSeconds = hold + fadeIn + fadeOut;
        EventTitleUtil.showEventTitleToPlayer(player, Message.raw(headline.get(player)), Message.raw(subtitle.get(player)),
            EventTitleStyle.Major, "", hold, fadeIn, fadeOut);
    }

    private record AudioControl(int index, int quiet, int warning, int rumble) {
        static AudioControl load() {
            var assets = AudioState.getAssetMap();
            AudioState state = assets.getAsset(AUDIO_STATE);
            if (state == null) return null;
            String[] values = state.getValues();
            int quiet = AudioStateResolver.resolveValueIndex("Quiet", values);
            int warning = AudioStateResolver.resolveValueIndex("Warning", values);
            int rumble = AudioStateResolver.resolveValueIndex("Rumble", values);
            if (quiet < 0 || warning < 0 || rumble < 0) return null;
            return new AudioControl(assets.getIndex(AUDIO_STATE), quiet, warning, rumble);
        }
    }

    private static final class ActiveQuake {
        final Earthquake quake;
        final Ref<EntityStore> initiator;
        final int shakeIndex;
        final int soundIndex;
        final int ruptureIndex;
        final List<List<EarthquakeFissures.Cell>> rupturePlans;
        final AudioControl audio;
        final Map<Ref<EntityStore>, Double> headings = new HashMap<>();
        final Map<PlayerRef, Integer> audioListeners = new HashMap<>();
        final HashSet<PlayerRef> titleViewers = new HashSet<>();
        float shakeCooldown;
        float titleSeconds;
        int nextRumbleSecond;
        boolean strongOpened;

        ActiveQuake(Earthquake quake, Ref<EntityStore> initiator, int shakeIndex, int soundIndex, int ruptureIndex,
                    List<List<EarthquakeFissures.Cell>> rupturePlans, AudioControl audio) {
            this.quake = quake;
            this.initiator = initiator;
            this.shakeIndex = shakeIndex;
            this.soundIndex = soundIndex;
            this.ruptureIndex = ruptureIndex;
            this.rupturePlans = rupturePlans;
            this.audio = audio;
        }
    }
}
