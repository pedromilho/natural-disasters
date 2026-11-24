package br.pedro.hytale.naturaldisasters;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3dc;

final class LoadedTerrain {
    private LoadedTerrain() { }

    static boolean isOutside(ChunkStore chunks, Vector3dc position) {
        int x = (int) Math.floor(position.x());
        int z = (int) Math.floor(position.z());
        HeightmapColumn heightmap = heightmap(chunks, x, z);
        return heightmap != null && heightmap.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK)
            < position.y() + 1.6;
    }

    static int surface(ChunkStore chunks, int x, int z) {
        HeightmapColumn column = heightmap(chunks, x, z);
        return column == null ? HeightmapColumn.NO_HEIGHT
            : column.getHeight(x & ChunkUtil.SIZE_MASK, z & ChunkUtil.SIZE_MASK);
    }

    private static HeightmapColumn heightmap(ChunkStore chunks, int x, int z) {
        Ref<ChunkStore> column = chunks.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (column == null || !column.isValid()) return null;
        HeightmapColumn heightmap = chunks.getStore().getComponent(column, HeightmapColumn.getComponentType());
        return heightmap != null && heightmap.getHeightmap() != null ? heightmap : null;
    }
}
