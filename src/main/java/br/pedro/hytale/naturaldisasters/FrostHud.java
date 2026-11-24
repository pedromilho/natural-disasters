package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

final class FrostHud extends CustomUIHud {
    private static final String KEY = "Pedro:NaturalDisasters.Frost";
    private int cracks;
    private int tint;

    private FrostHud(PlayerRef player, int cracks, int tint) {
        super(player, KEY);
        this.cracks = cracks;
        this.tint = tint;
    }

    static void setLevels(HudManager manager, PlayerRef player, int cracks, int tint) {
        CustomUIHud current = manager.getCustomHud(KEY);
        if (cracks == 0 && tint == 0) {
            if (current != null) manager.removeCustomHud(player, KEY);
        } else if (current instanceof FrostHud frost) {
            frost.updateLevels(cracks, tint);
        } else {
            manager.addCustomHud(player, new FrostHud(player, cracks, tint));
        }
    }

    @Override
    protected void build(UICommandBuilder ui) {
        ui.append("Hud/NaturalDisastersFrost.ui");
        changeFrame(ui, "#IceTint", 0, tint);
        changeFrame(ui, "#Frost", 0, cracks);
    }

    private void updateLevels(int nextCracks, int nextTint) {
        if (cracks == nextCracks && tint == nextTint) return;
        var ui = new UICommandBuilder();
        changeFrame(ui, "#IceTint", tint, nextTint);
        changeFrame(ui, "#Frost", cracks, nextCracks);
        tint = nextTint;
        cracks = nextCracks;
        update(false, ui);
    }

    private static void changeFrame(UICommandBuilder ui, String prefix, int before, int after) {
        if (before == after) return;
        if (before > 0) ui.set(prefix + before + ".Visible", false);
        if (after > 0) ui.set(prefix + after + ".Visible", true);
    }
}
