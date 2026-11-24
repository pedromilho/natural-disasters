package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class NaturalDisastersPlugin extends JavaPlugin {
    private final BlizzardSystem blizzards = new BlizzardSystem();

    public NaturalDisastersPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getEntityStoreRegistry().registerSystem(blizzards);
        getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> blizzards.remove(event.getWorld()));
        getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, event -> {
            var holder = event.getHolder();
            Player player = holder.getComponent(Player.getComponentType());
            PlayerRef connection = holder.getComponent(PlayerRef.getComponentType());
            if (player != null && connection != null) FrostHud.setLevels(player.getHudManager(), connection, 0, 0);
        });
        getCommandRegistry().registerCommand(new TestPanelCommand());
    }

    @Override
    protected void shutdown() {
        blizzards.shutdown();
    }

    static boolean canTest(PlayerRef player) {
        return (Constants.SINGLEPLAYER && SingleplayerModule.isOwner(player))
            || PermissionsModule.get().getGroupsForUser(player.getUuid()).contains("hytale:Admin")
            || player.hasPermission("naturaldisasters.test");
    }

    private final class TestPanelCommand extends AbstractPlayerCommand {
        TestPanelCommand() {
            super("naturaldisasters", "Open the Natural Disasters test panel");
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            if (!canTest(player)) {
                context.sendMessage(Message.raw(DisasterText.NO_PERMISSION.get(player)));
                return;
            }
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity != null) {
                entity.getPageManager().openCustomPage(ref, store, new DisasterTestPage(player, blizzards));
            }
        }
    }
}
