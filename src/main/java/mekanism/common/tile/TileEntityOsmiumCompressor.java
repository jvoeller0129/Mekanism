package mekanism.common.tile;

import javax.annotation.Nonnull;
import mekanism.api.gas.Gas;
import mekanism.api.recipes.ItemStackGasToItemStackRecipe;
import mekanism.common.MekanismBlock;
import mekanism.common.recipe.RecipeHandler.Recipe;
import mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine;
import net.minecraft.util.Direction;

public class TileEntityOsmiumCompressor extends TileEntityAdvancedElectricMachine {

    public TileEntityOsmiumCompressor() {
        super(MekanismBlock.OSMIUM_COMPRESSOR, BASE_TICKS_REQUIRED, BASE_GAS_PER_TICK);
    }

    @Nonnull
    @Override
    public Recipe<ItemStackGasToItemStackRecipe> getRecipes() {
        return Recipe.OSMIUM_COMPRESSOR;
    }

    @Override
    public boolean canReceiveGas(Direction side, @Nonnull Gas type) {
        return gasTank.canReceive(type) && isValidGas(type);
    }
}