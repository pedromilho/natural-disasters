package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class DisasterTestPage extends InteractiveCustomUIPage<DisasterTestPage.Action> {
    private final BlizzardSystem blizzards;
    private final EarthquakeSystem earthquakes;

    DisasterTestPage(PlayerRef player, BlizzardSystem blizzards, EarthquakeSystem earthquakes) {
        super(player, CustomPageLifetime.CanDismiss, Action.CODEC);
        this.blizzards = blizzards;
        this.earthquakes = earthquakes;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        ui.append("Pages/NaturalDisasters.ui");
        ui.set("#Subtitle.Text", DisasterText.SUBTITLE.get(playerRef));
        ui.set("#Details.Text", DisasterText.DETAILS.get(playerRef, Blizzard.RADIUS, Blizzard.WARNING_SECONDS,
            Blizzard.ACTIVE_SECONDS));
        ui.set("#StartBlizzard.Text", DisasterText.START.get(playerRef));
        ui.set("#StopBlizzard.Text", DisasterText.STOP.get(playerRef));
        ui.set("#EarthquakeDetails.Text", DisasterText.QUAKE_DETAILS.get(playerRef));
        ui.set("#QuakeLight.Text", DisasterText.QUAKE_LIGHT.get(playerRef));
        ui.set("#QuakeModerate.Text", DisasterText.QUAKE_MODERATE.get(playerRef));
        ui.set("#QuakeStrong.Text", DisasterText.QUAKE_STRONG.get(playerRef));
        ui.set("#StopEarthquake.Text", DisasterText.STOP.get(playerRef));
        ui.set("#Close.Text", DisasterText.CLOSE.get(playerRef));
        writeStatus(ui, store.getExternalData().getWorld());
        bind(events, "#StartBlizzard", "start-blizzard");
        bind(events, "#StopBlizzard", "stop-blizzard");
        bind(events, "#QuakeLight", "quake-light");
        bind(events, "#QuakeModerate", "quake-moderate");
        bind(events, "#QuakeStrong", "quake-strong");
        bind(events, "#StopEarthquake", "stop-earthquake");
        bind(events, "#Close", "close");
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Action action) {
        if (!NaturalDisastersPlugin.canTest(playerRef)) {
            playerRef.sendMessage(Message.raw(DisasterText.NO_PERMISSION.get(playerRef)));
            close();
            return;
        }
        World world = store.getExternalData().getWorld();
        switch (action.name == null ? "" : action.name) {
            case "start-blizzard" -> blizzards.start(store, ref, playerRef);
            case "stop-blizzard" -> blizzards.stop(store, DisasterText.CANCELLED);
            case "quake-light" -> earthquakes.start(store, ref, playerRef, Earthquake.Magnitude.LIGHT);
            case "quake-moderate" -> earthquakes.start(store, ref, playerRef, Earthquake.Magnitude.MODERATE);
            case "quake-strong" -> earthquakes.start(store, ref, playerRef, Earthquake.Magnitude.STRONG);
            case "stop-earthquake" -> earthquakes.stop(store, DisasterText.QUAKE_CANCELLED);
            case "close" -> {
                close();
                return;
            }
            default -> { }
        }
        refresh(world);
    }

    static void refreshPlayer(World world, Store<EntityStore> store, Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getPageManager().getCustomPage() instanceof DisasterTestPage page) {
            page.refresh(world);
        }
    }

    private void refresh(World world) {
        var ui = new UICommandBuilder();
        writeStatus(ui, world);
        sendUpdate(ui);
    }

    private void writeStatus(UICommandBuilder ui, World world) {
        Blizzard storm = blizzards.get(world);
        String status = storm == null ? DisasterText.IDLE.get(playerRef) : switch (storm.phase()) {
            case IDLE -> DisasterText.IDLE.get(playerRef);
            case WARNING_SNOW -> DisasterText.WARNING_SNOW.get(playerRef, storm.remainingSeconds());
            case WARNING_WIND -> DisasterText.WARNING_WIND.get(playerRef, storm.remainingSeconds());
            case ACTIVE -> DisasterText.ACTIVE.get(playerRef, storm.remainingSeconds());
        };
        ui.set("#Status.Text", status);
        ui.set("#StartBlizzard.Disabled", storm != null);
        ui.set("#StopBlizzard.Disabled", storm == null);

        Earthquake quake = earthquakes.get(world);
        String quakeStatus = DisasterText.IDLE.get(playerRef);
        if (quake != null) {
            String magnitude = EarthquakeSystem.magnitudeText(quake.magnitude).get(playerRef);
            quakeStatus = switch (quake.phase()) {
                case WARNING -> DisasterText.QUAKE_WARNING.get(playerRef, magnitude, quake.remainingSeconds());
                case ACTIVE -> DisasterText.QUAKE_ACTIVE.get(playerRef, magnitude, quake.remainingSeconds());
                case FINISHED -> quakeStatus;
            };
        }
        ui.set("#EarthquakeStatus.Text", quakeStatus);
        ui.set("#QuakeLight.Disabled", quake != null);
        ui.set("#QuakeModerate.Disabled", quake != null);
        ui.set("#QuakeStrong.Disabled", quake != null);
        ui.set("#StopEarthquake.Disabled", quake == null);
    }

    private static void bind(UIEventBuilder events, String selector, String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action), false);
    }

    static final class Action {
        static final BuilderCodec<Action> CODEC = BuilderCodec.builder(Action.class, Action::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (action, name) -> action.name = name,
                action -> action.name).add().build();

        private String name;
    }
}
