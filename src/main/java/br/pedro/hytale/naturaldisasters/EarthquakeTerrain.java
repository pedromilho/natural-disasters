package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Set;
import org.joml.Vector3i;

final class EarthquakeTerrain implements EarthquakeFissures.Terrain {
    private static final Set<String> NATURAL_BLOCKS = Set.of("Rock_Aqua", "Rock_Basalt", "Rock_Calcite", "Rock_Chalk",
        "Rock_Dawnstone", "Rock_Ice", "Rock_Kenolite", "Rock_Lime", "Rock_Marble", "Rock_Quartzite", "Rock_Salt",
        "Rock_Sandstone", "Rock_Sandstone_Red", "Rock_Sandstone_White", "Rock_Shale", "Rock_Slate", "Rock_Stone",
        "Rock_Stone_Aqua", "Rock_Volcanic", "Soil_Dirt", "Soil_Dirt_Burnt", "Soil_Dirt_Cold", "Soil_Dirt_Crystal",
        "Soil_Dirt_Dry", "Soil_Dirt_Lush", "Soil_Dirt_Poisoned", "Soil_Dirt_Wet", "Soil_Grass", "Soil_Grass_Full",
        "Soil_Grass_Burnt", "Soil_Grass_Burnt_Full", "Soil_Grass_Cold", "Soil_Grass_Cold_Full", "Soil_Grass_Deep",
        "Soil_Grass_Deep_Full", "Soil_Grass_Dry", "Soil_Grass_Dry_Full", "Soil_Grass_Sunny", "Soil_Grass_Sunny_Full",
        "Soil_Grass_Wet", "Soil_Grass_Wet_Full", "Soil_Gravel", "Soil_Sand", "Soil_Sand_Ashen", "Soil_Sand_Red",
        "Soil_Sand_White", "Soil_Snow", "Soil_Mud", "Soil_Mud_Dry");
    private static final int FISSURE_FLAGS = SetBlockSettings.NO_DROP_ITEMS | SetBlockSettings.NO_SEND_PARTICLES
        | SetBlockSettings.NO_SEND_AUDIO | SetBlockSettings.NO_BREAK_FILLER | SetBlockSettings.NO_FIRE_ON_BREAK;
    private final ChunkStore chunks;
    private final Store<ChunkStore> sections;
    private final Set<Integer> naturalIds = new HashSet<>();

    EarthquakeTerrain(ChunkStore chunks) {
        this.chunks = chunks;
        this.sections = chunks.getStore();
        for (String id : NATURAL_BLOCKS) {
            int index = BlockType.getAssetMap().getIndex(id);
            if (index > BlockType.EMPTY_ID) naturalIds.add(index);
        }
    }

    @Override
    public int surface(int x, int z) {
        return LoadedTerrain.surface(chunks, x, z);
    }

    @Override
    public EarthquakeFissures.Material at(int x, int y, int z) {
        Ref<ChunkStore> section = sectionAt(x, y, z);
        if (section == null) return EarthquakeFissures.Material.BLOCKED;
        BlockSection blocks = sections.getComponent(section, BlockSection.getComponentType());
        if (blocks == null) return EarthquakeFissures.Material.BLOCKED;

        int id = blocks.get(x, y, z);
        boolean natural = naturalIds.contains(id);
        boolean grass = false;
        if (id != BlockType.EMPTY_ID && !natural) {
            BlockType type = BlockType.getAssetMap().getAsset(id);
            grass = type != null && type.getId().startsWith("Plant_Grass_");
            if (!grass) return EarthquakeFissures.Material.BLOCKED;
        }
        FluidSection fluids = sections.getComponent(section, FluidSection.getComponentType());
        if (fluids != null && fluids.getFluidLevel(x, y, z) > 0) return EarthquakeFissures.Material.BLOCKED;
        if (id == BlockType.EMPTY_ID) return EarthquakeFissures.Material.AIR;

        BlockComponentSection components = sections.getComponent(section, BlockComponentSection.getComponentType());
        int index = ChunkUtil.indexBlock(x, y, z);
        if (blocks.getFiller(x, y, z) != 0 || (components != null && components.hasBlockComponents(index))) {
            return EarthquakeFissures.Material.BLOCKED;
        }
        return grass ? EarthquakeFissures.Material.GRASS : EarthquakeFissures.Material.NATURAL;
    }

    boolean open(EarthquakeFissures.Cell cell, Store<EntityStore> entities, Ref<EntityStore> initiator) {
        Change change = authorize(cell, entities, initiator);
        return change != null && open(change);
    }

    Change authorize(EarthquakeFissures.Cell cell, Store<EntityStore> entities, Ref<EntityStore> initiator) {
        Change ground = authorizeBlock(cell, EarthquakeFissures.Material.NATURAL, entities, initiator);
        if (ground == null || at(cell.x(), cell.y() + 1, cell.z()) != EarthquakeFissures.Material.GRASS) return ground;

        var plant = new EarthquakeFissures.Cell(cell.x(), cell.y() + 1, cell.z());
        Change grass = authorizeBlock(plant, EarthquakeFissures.Material.GRASS, entities, initiator);
        return grass == null || !matches(ground) ? null : new Change(cell, ground.originalId, ground.rotation, grass);
    }

    private Change authorizeBlock(EarthquakeFissures.Cell cell, EarthquakeFissures.Material material,
                                  Store<EntityStore> entities, Ref<EntityStore> initiator) {
        int x = cell.x();
        int y = cell.y();
        int z = cell.z();
        if (!initiator.isValid() || at(x, y, z) != material) return null;

        Ref<ChunkStore> section = sectionAt(x, y, z);
        BlockSection blocks = sections.getComponent(section, BlockSection.getComponentType());
        int original = blocks.get(x, y, z);
        int rotation = blocks.getRotationIndex(x, y, z);
        BlockType type = BlockType.getAssetMap().getAsset(original);
        var event = new BreakBlockEvent(ItemStack.EMPTY, new Vector3i(x, y, z), type);
        entities.invoke(initiator, event);
        var change = new Change(cell, original, rotation, null);
        return event.isCancelled() || !matches(change) ? null : change;
    }

    boolean matches(Change change) {
        var cell = change.cell;
        var material = at(cell.x(), cell.y(), cell.z());
        if (material != EarthquakeFissures.Material.NATURAL && material != EarthquakeFissures.Material.GRASS) return false;
        Ref<ChunkStore> section = sectionAt(cell.x(), cell.y(), cell.z());
        BlockSection blocks = sections.getComponent(section, BlockSection.getComponentType());
        return blocks.get(cell.x(), cell.y(), cell.z()) == change.originalId
            && blocks.getRotationIndex(cell.x(), cell.y(), cell.z()) == change.rotation
            && (change.grass == null || matches(change.grass));
    }

    boolean open(Change change) {
        if (!matches(change)) return false;
        if (change.grass != null && !clear(change.grass)) return false;
        if (clear(change)) return true;
        if (change.grass != null) restore(change.grass);
        return false;
    }

    private boolean clear(Change change) {
        var cell = change.cell;
        Ref<ChunkStore> section = sectionAt(cell.x(), cell.y(), cell.z());
        return BlockOperations.setBlock(chunks, section, cell.x(), cell.y(), cell.z(), BlockType.EMPTY_ID,
            BlockType.EMPTY, 0, 0, FISSURE_FLAGS);
    }

    void restore(Change change) {
        var cell = change.cell;
        if (at(cell.x(), cell.y(), cell.z()) != EarthquakeFissures.Material.AIR) return;
        Ref<ChunkStore> section = sectionAt(cell.x(), cell.y(), cell.z());
        BlockOperations.setBlock(chunks, section, cell.x(), cell.y(), cell.z(), change.originalId,
            BlockType.getAssetMap().getAsset(change.originalId), change.rotation, 0, FISSURE_FLAGS);
        if (change.grass != null) restore(change.grass);
    }

    private Ref<ChunkStore> sectionAt(int x, int y, int z) {
        Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        return section != null && section.isValid() ? section : null;
    }

    record Change(EarthquakeFissures.Cell cell, int originalId, int rotation, Change grass) { }
}
