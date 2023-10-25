package mekanism.common.tile.multiblock;

import java.util.Collections;
import java.util.Set;
import mekanism.api.Action;
import mekanism.api.IContentsListener;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasTank;
import mekanism.api.text.EnumColor;
import mekanism.common.MekanismLang;
import mekanism.common.block.attribute.AttributeStateBoilerValveMode;
import mekanism.common.block.attribute.AttributeStateBoilerValveMode.BoilerValveMode;
import mekanism.common.capabilities.holder.chemical.IChemicalTankHolder;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.content.boiler.BoilerMultiblockData;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.lib.multiblock.IMultiblockEjector;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.base.SubstanceType;
import mekanism.common.util.ChemicalUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TileEntityBoilerValve extends TileEntityBoilerCasing implements IMultiblockEjector {

    private Set<Direction> outputDirections = Collections.emptySet();

    public TileEntityBoilerValve(BlockPos pos, BlockState state) {
        super(MekanismBlocks.BOILER_VALVE, pos, state);
    }

    @NotNull
    @Override
    public IChemicalTankHolder<Gas, GasStack, IGasTank> getInitialGasTanks(IContentsListener listener) {
        return side -> getMultiblock().getGasTanks(side);
    }

    @NotNull
    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        return side -> getMultiblock().getFluidTanks(side);
    }

    @Override
    protected boolean onUpdateServer(BoilerMultiblockData multiblock) {
        boolean needsPacket = super.onUpdateServer(multiblock);
        if (multiblock.isFormed()) {
            BoilerValveMode mode = getMode();
            if (mode == BoilerValveMode.OUTPUT_STEAM) {
                ChemicalUtil.emit(outputDirections, multiblock.steamTank, this);
            } else if (mode == BoilerValveMode.OUTPUT_COOLANT) {
                ChemicalUtil.emit(outputDirections, multiblock.cooledCoolantTank, this);
            }
        }
        return needsPacket;
    }

    @Override
    public boolean persists(SubstanceType type) {
        //Do not handle fluid or gas when it comes to syncing it/saving this tile to disk
        if (type == SubstanceType.FLUID || type == SubstanceType.GAS) {
            return false;
        }
        return super.persists(type);
    }

    @Override
    public void setEjectSides(Set<Direction> sides) {
        outputDirections = sides;
    }

    @Override
    public int getRedstoneLevel() {
        return getMultiblock().getCurrentRedstoneLevel();
    }

    @ComputerMethod(methodDescription = "Get the current configuration of this valve")
    BoilerValveMode getMode() {
        return getBlockState().getValue(AttributeStateBoilerValveMode.modeProperty);
    }

    @ComputerMethod(methodDescription = "Change the configuration of this valve")
    void setMode(BoilerValveMode mode) {
        if (mode != getMode()) {
            level.setBlockAndUpdate(worldPosition, getBlockState().setValue(AttributeStateBoilerValveMode.modeProperty, mode));
        }
    }

    @Override
    public InteractionResult onSneakRightClick(Player player) {
        if (!isRemote()) {
            BoilerValveMode mode = getMode().getNext();
            setMode(mode);
            player.displayClientMessage(MekanismLang.BOILER_VALVE_MODE_CHANGE.translateColored(EnumColor.GRAY, mode), true);
        }
        return InteractionResult.SUCCESS;
    }

    @NotNull
    @Override
    public FluidStack insertFluid(@NotNull FluidStack stack, Direction side, @NotNull Action action) {
        FluidStack ret = super.insertFluid(stack, side, action);
        if (ret.getAmount() < stack.getAmount() && action.execute()) {
            getMultiblock().triggerValveTransfer(this);
        }
        return ret;
    }

    @Override
    public boolean insertGasCheck(int tank, @Nullable Direction side) {
        if (getMode() != BoilerValveMode.INPUT) {
            //Don't allow inserting into the fuel tanks, if we are on output mode
            return false;
        }
        return super.insertGasCheck(tank, side);
    }

    @Override
    public boolean extractGasCheck(int tank, @Nullable Direction side) {
        //TODO: Do this better so there is no magic numbers
        BoilerValveMode mode = getMode();
        if (mode == BoilerValveMode.INPUT || (tank == 2 && mode == BoilerValveMode.OUTPUT_STEAM) || (tank == 0 && mode == BoilerValveMode.OUTPUT_COOLANT)) {
            // don't allow extraction from tanks based on mode
            return false;
        }
        return super.extractGasCheck(tank, side);
    }

    //Methods relating to IComputerTile
    @ComputerMethod(methodDescription = "Toggle the current valve configuration to the next option in the list")
    void incrementMode() {
        setMode(getMode().getNext());
    }

    @ComputerMethod(methodDescription = "Toggle the current valve configuration to the previous option in the list")
    void decrementMode() {
        setMode(getMode().getPrevious());
    }
    //End methods IComputerTile
}
