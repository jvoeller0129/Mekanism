package mekanism.common.block;

import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.registries.MekanismBlockTypes;
import mekanism.common.tile.TileEntityPersonalBarrel;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;

public class BlockPersonalBarrel extends BlockPersonalStorage<TileEntityPersonalBarrel, BlockTypeTile<TileEntityPersonalBarrel>> {

    public BlockPersonalBarrel() {
        super(MekanismBlockTypes.PERSONAL_BARREL, properties -> properties.mapColor(MapColor.COLOR_GRAY));
    }

    @Override
    @Deprecated
    public void tick(@NotNull BlockState state, @NotNull ServerLevel level, @NotNull BlockPos pos, @NotNull RandomSource random) {
        super.tick(state, level, pos, random);
        TileEntityPersonalBarrel barrel = WorldUtils.getTileEntity(TileEntityPersonalBarrel.class, level, pos);
        if (barrel != null) {
            barrel.recheckOpen();
        }
    }
}
