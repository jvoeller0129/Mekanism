package mekanism.common.tile.machine;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IConfigurable;
import mekanism.api.IContentsListener;
import mekanism.api.NBTConstants;
import mekanism.api.RelativeSide;
import mekanism.api.Upgrade;
import mekanism.api.math.FloatingLong;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.capabilities.resolver.BasicCapabilityResolver;
import mekanism.common.config.MekanismConfig;
import mekanism.common.integration.computer.ComputerException;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerFluidTankWrapper;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableFluidStack;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.FluidInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.registries.MekanismFluids;
import mekanism.common.tile.base.SubstanceType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.FluidUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.UpgradeUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.IFluidBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TileEntityElectricPump extends TileEntityMekanism implements IConfigurable {

    /**
     * How many ticks it takes to run an operation.
     */
    private static final int BASE_TICKS_REQUIRED = 19;
    /**
     * This pump's tank
     */
    @WrappingComputerMethod(wrapper = ComputerFluidTankWrapper.class, methodNames = {"getFluid", "getFluidCapacity", "getFluidNeeded", "getFluidFilledPercentage"}, docPlaceholder = "buffer tank")
    public BasicFluidTank fluidTank;
    /**
     * The type of fluid this pump is pumping
     */
    @NotNull
    private FluidStack activeType = FluidStack.EMPTY;
    public int ticksRequired = BASE_TICKS_REQUIRED;
    /**
     * How many ticks this machine has been operating for.
     */
    public int operatingTicks;
    private boolean usedEnergy = false;
    /**
     * The nodes that have full sources near them or in them
     */
    private final Set<BlockPos> recurringNodes = new ObjectOpenHashSet<>();

    private MachineEnergyContainer<TileEntityElectricPump> energyContainer;
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getInputItem", docPlaceholder = "input slot")
    FluidInventorySlot inputSlot;
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getOutputItem", docPlaceholder = "output slot")
    OutputInventorySlot outputSlot;
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy slot")
    EnergyInventorySlot energySlot;

    public TileEntityElectricPump(BlockPos pos, BlockState state) {
        super(MekanismBlocks.ELECTRIC_PUMP, pos, state);
        addCapabilityResolver(BasicCapabilityResolver.constant(Capabilities.CONFIGURABLE, this));
        addCapabilityResolver(BasicCapabilityResolver.constant(Capabilities.CONFIG_CARD, this));
    }

    @NotNull
    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        FluidTankHelper builder = FluidTankHelper.forSide(this::getDirection);
        builder.addTank(fluidTank = BasicFluidTank.output(10_000, listener), RelativeSide.TOP);
        return builder.build();
    }

    @NotNull
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSide(this::getDirection);
        builder.addContainer(energyContainer = MachineEnergyContainer.input(this, listener), RelativeSide.BACK);
        return builder.build();
    }

    @NotNull
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = InventorySlotHelper.forSide(this::getDirection);
        builder.addSlot(inputSlot = FluidInventorySlot.drain(fluidTank, listener, 28, 20), RelativeSide.TOP);
        builder.addSlot(outputSlot = OutputInventorySlot.at(listener, 28, 51), RelativeSide.BOTTOM);
        builder.addSlot(energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, 143, 35), RelativeSide.BACK);
        return builder.build();
    }

    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        energySlot.fillContainerOrConvert();
        inputSlot.drainTank(outputSlot);
        FloatingLong clientEnergyUsed = FloatingLong.ZERO;
        if (MekanismUtils.canFunction(this) && (fluidTank.isEmpty() || estimateIncrementAmount() <= fluidTank.getNeeded())) {
            FloatingLong energyPerTick = energyContainer.getEnergyPerTick();
            if (energyContainer.extract(energyPerTick, Action.SIMULATE, AutomationType.INTERNAL).equals(energyPerTick)) {
                if (!activeType.isEmpty()) {
                    //If we have an active type of fluid, use energy. This can cause there to be ticks where there isn't actually
                    // anything to suck that use energy, but those will balance out with the first set of ticks where it doesn't
                    // use any energy until it actually picks up the first block
                    clientEnergyUsed = energyContainer.extract(energyPerTick, Action.EXECUTE, AutomationType.INTERNAL);
                }
                operatingTicks++;
                if (operatingTicks >= ticksRequired) {
                    operatingTicks = 0;
                    if (suck()) {
                        if (clientEnergyUsed.isZero()) {
                            //If it didn't already have an active type (hasn't used energy this tick), then extract energy
                            clientEnergyUsed = energyContainer.extract(energyPerTick, Action.EXECUTE, AutomationType.INTERNAL);
                        }
                    } else {
                        reset();
                    }
                }
            }
        }
        usedEnergy = !clientEnergyUsed.isZero();
        if (!fluidTank.isEmpty()) {
            FluidUtils.emit(Collections.singleton(Direction.UP), fluidTank, this, 256 * (1 + upgradeComponent.getUpgrades(Upgrade.SPEED)));
        }
    }

    public int estimateIncrementAmount() {
        return fluidTank.getFluid().getFluid() == MekanismFluids.HEAVY_WATER.getFluid() ? MekanismConfig.general.pumpHeavyWaterAmount.get() : FluidType.BUCKET_VOLUME;
    }

    private boolean suck() {
        boolean hasFilter = upgradeComponent.isUpgradeInstalled(Upgrade.FILTER);
        //First see if there are any fluid blocks under the pump - if so, suck and adds the location to the recurring list
        if (suck(worldPosition.relative(Direction.DOWN), hasFilter, true)) {
            return true;
        }
        //Even though we can add to recurring in the above for loop, we always then exit and don't get to here if we did so
        List<BlockPos> tempPumpList = new ArrayList<>(recurringNodes);
        Collections.shuffle(tempPumpList);
        //Finally, go over the recurring list of nodes and see if there is a fluid block available to suck - if not, will iterate around the recurring block, attempt to suck,
        //and then add the adjacent block to the recurring list
        for (BlockPos tempPumpPos : tempPumpList) {
            if (suck(tempPumpPos, hasFilter, false)) {
                return true;
            }
            //Add all the blocks surrounding this recurring node to the recurring node list
            for (Direction orientation : EnumUtils.DIRECTIONS) {
                BlockPos side = tempPumpPos.relative(orientation);
                if (WorldUtils.distanceBetween(worldPosition, side) <= MekanismConfig.general.maxPumpRange.get()) {
                    if (suck(side, hasFilter, true)) {
                        return true;
                    }
                }
            }
            recurringNodes.remove(tempPumpPos);
        }
        return false;
    }

    private boolean suck(BlockPos pos, boolean hasFilter, boolean addRecurring) {
        //Note: we get the block state from the world so that we can get the proper block in case it is fluid logged
        Optional<BlockState> state = WorldUtils.getBlockState(level, pos);
        if (state.isPresent()) {
            BlockState blockState = state.get();
            FluidState fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty() && fluidState.isSource()) {
                //Just in case someone does weird things and has a fluid state that is empty and a source
                // only allow collecting from non-empty sources
                Block block = blockState.getBlock();
                if (block instanceof IFluidBlock fluidBlock) {
                    if (validFluid(fluidBlock.drain(level, pos, FluidAction.SIMULATE))) {
                        //Actually drain it
                        suck(fluidBlock.drain(level, pos, FluidAction.EXECUTE), pos, addRecurring);
                        return true;
                    }
                } else if (block instanceof BucketPickup bucketPickup) {
                    Fluid sourceFluid = fluidState.getType();
                    FluidStack fluidStack = getOutput(sourceFluid, hasFilter);
                    if (validFluid(fluidStack)) {
                        //If it can be picked up by a bucket, and we actually want to pick it up, do so to update the fluid type we are doing
                        if (sourceFluid != Fluids.WATER || MekanismConfig.general.pumpWaterSources.get()) {
                            //Note we only attempt taking if it is not water, or we want to pump water sources
                            // otherwise we assume the type from the fluid state is correct
                            ItemStack pickedUpStack = bucketPickup.pickupBlock(level, pos, blockState);
                            if (pickedUpStack.isEmpty()) {
                                //Couldn't actually pick it up, exit
                                return false;
                            } else if (pickedUpStack.getItem() instanceof BucketItem bucket) {
                                //This isn't the best validation check given it may not return a bucket, but it is good enough for now
                                sourceFluid = bucket.getFluid();
                                //Update the fluid stack in case something somehow changed about the type
                                // making sure that we replace to heavy water if we got heavy water
                                fluidStack = getOutput(sourceFluid, hasFilter);
                                if (!validFluid(fluidStack)) {
                                    Mekanism.logger.warn("Fluid removed without successfully picking up. Fluid {} at {} in {} was valid, but after picking up was {}.",
                                          fluidState.getType(), pos, level, sourceFluid);
                                    return false;
                                }
                            }
                        }
                        suck(fluidStack, pos, addRecurring);
                        return true;
                    }
                }
                //Otherwise, we do not know how to drain from the block, or it is not valid, and we shouldn't take it so don't handle it
            }
        }
        return false;
    }

    private FluidStack getOutput(Fluid sourceFluid, boolean hasFilter) {
        if (hasFilter && sourceFluid == Fluids.WATER) {
            return MekanismFluids.HEAVY_WATER.getFluidStack(MekanismConfig.general.pumpHeavyWaterAmount.get());
        }
        return new FluidStack(sourceFluid, FluidType.BUCKET_VOLUME);
    }

    private void suck(@NotNull FluidStack fluidStack, BlockPos pos, boolean addRecurring) {
        //Size doesn't matter, but we do want to take the NBT into account
        activeType = new FluidStack(fluidStack, 1);
        if (addRecurring) {
            recurringNodes.add(pos);
        }
        fluidTank.insert(fluidStack, Action.EXECUTE, AutomationType.INTERNAL);
        level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
    }

    private boolean validFluid(@NotNull FluidStack fluidStack) {
        if (!fluidStack.isEmpty() && (activeType.isEmpty() || activeType.isFluidEqual(fluidStack))) {
            if (fluidTank.isEmpty()) {
                return true;
            } else if (fluidTank.isFluidEqual(fluidStack)) {
                return fluidStack.getAmount() <= fluidTank.getNeeded();
            }
        }
        return false;
    }

    public void reset() {
        activeType = FluidStack.EMPTY;
        recurringNodes.clear();
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag nbtTags) {
        super.saveAdditional(nbtTags);
        nbtTags.putInt(NBTConstants.PROGRESS, operatingTicks);
        if (!activeType.isEmpty()) {
            nbtTags.put(NBTConstants.FLUID_STORED, activeType.writeToNBT(new CompoundTag()));
        }
        if (!recurringNodes.isEmpty()) {
            ListTag recurringList = new ListTag();
            for (BlockPos nodePos : recurringNodes) {
                recurringList.add(NbtUtils.writeBlockPos(nodePos));
            }
            nbtTags.put(NBTConstants.RECURRING_NODES, recurringList);
        }
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);
        operatingTicks = nbt.getInt(NBTConstants.PROGRESS);
        NBTUtils.setFluidStackIfPresent(nbt, NBTConstants.FLUID_STORED, fluid -> activeType = fluid);
        if (nbt.contains(NBTConstants.RECURRING_NODES, Tag.TAG_LIST)) {
            ListTag tagList = nbt.getList(NBTConstants.RECURRING_NODES, Tag.TAG_COMPOUND);
            for (int i = 0; i < tagList.size(); i++) {
                recurringNodes.add(NbtUtils.readBlockPos(tagList.getCompound(i)));
            }
        }
    }

    @Override
    public InteractionResult onSneakRightClick(Player player) {
        reset();
        player.displayClientMessage(MekanismLang.PUMP_RESET.translate(), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onRightClick(Player player) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean canPulse() {
        return true;
    }

    @Override
    public void recalculateUpgrades(Upgrade upgrade) {
        super.recalculateUpgrades(upgrade);
        if (upgrade == Upgrade.SPEED) {
            ticksRequired = MekanismUtils.getTicks(this, BASE_TICKS_REQUIRED);
        }
    }

    @Override
    public int getRedstoneLevel() {
        return MekanismUtils.redstoneLevelFromContents(fluidTank.getFluidAmount(), fluidTank.getCapacity());
    }

    @Override
    protected boolean makesComparatorDirty(@Nullable SubstanceType type) {
        return type == SubstanceType.FLUID;
    }

    @NotNull
    @Override
    public List<Component> getInfo(@NotNull Upgrade upgrade) {
        return UpgradeUtils.getMultScaledInfo(this, upgrade);
    }

    public MachineEnergyContainer<TileEntityElectricPump> getEnergyContainer() {
        return energyContainer;
    }

    public boolean usedEnergy() {
        return usedEnergy;
    }

    @NotNull
    public FluidStack getActiveType() {
        return this.activeType;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        container.track(SyncableBoolean.create(this::usedEnergy, value -> usedEnergy = value));
        container.track(SyncableFluidStack.create(this::getActiveType, value -> activeType = value));
    }

    //Methods relating to IComputerTile
    @ComputerMethod(nameOverride = "reset", requiresPublicSecurity = true)
    void resetPump() throws ComputerException {
        validateSecurityIsPublic();
        reset();
    }
    //End methods IComputerTile
}
