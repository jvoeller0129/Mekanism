package mekanism.common.item.block.machine;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.NBTConstants;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.fluid.IMekanismFluidHandler;
import mekanism.api.security.ISecurityUtils;
import mekanism.api.text.EnumColor;
import mekanism.client.render.RenderPropertiesProvider;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.basic.BlockFluidTank;
import mekanism.common.capabilities.ItemCapabilityWrapper.ItemCapability;
import mekanism.common.capabilities.fluid.item.RateLimitFluidHandler;
import mekanism.common.item.interfaces.IModeItem;
import mekanism.common.tier.FluidTankTier;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.RegistryUtils;
import mekanism.common.util.SecurityUtils;
import mekanism.common.util.StorageUtils;
import mekanism.common.util.WorldUtils;
import mekanism.common.util.text.BooleanStateDisplay.OnOff;
import mekanism.common.util.text.BooleanStateDisplay.YesNo;
import mekanism.common.util.text.TextUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.SoundActions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ItemBlockFluidTank extends ItemBlockMachine implements IModeItem {

    public ItemBlockFluidTank(BlockFluidTank block) {
        super(block);
    }

    @Override
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(RenderPropertiesProvider.fluidTank());
    }

    @NotNull
    @Override
    public FluidTankTier getTier() {
        return Attribute.getTier(getBlock(), FluidTankTier.class);
    }

    @Override
    protected void addStats(@NotNull ItemStack stack, Level world, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        FluidTankTier tier = getTier();
        FluidStack fluidStack = StorageUtils.getStoredFluidFromNBT(stack);
        if (fluidStack.isEmpty()) {
            tooltip.add(MekanismLang.EMPTY.translateColored(EnumColor.DARK_RED));
        } else if (tier == FluidTankTier.CREATIVE) {
            tooltip.add(MekanismLang.GENERIC_STORED.translateColored(EnumColor.PINK, fluidStack, EnumColor.GRAY, MekanismLang.INFINITE));
        } else {
            tooltip.add(MekanismLang.GENERIC_STORED_MB.translateColored(EnumColor.PINK, fluidStack, EnumColor.GRAY, TextUtils.format(fluidStack.getAmount())));
        }
        if (tier == FluidTankTier.CREATIVE) {
            tooltip.add(MekanismLang.CAPACITY.translateColored(EnumColor.INDIGO, EnumColor.GRAY, MekanismLang.INFINITE));
        } else {
            tooltip.add(MekanismLang.CAPACITY_MB.translateColored(EnumColor.INDIGO, EnumColor.GRAY, TextUtils.format(tier.getStorage())));
        }
    }

    @Override
    protected void addTypeDetails(@NotNull ItemStack stack, Level world, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        tooltip.add(MekanismLang.BUCKET_MODE.translateColored(EnumColor.INDIGO, YesNo.of(getBucketMode(stack))));
        super.addTypeDetails(stack, world, tooltip, flag);
    }

    @NotNull
    @Override
    public InteractionResult useOn(UseOnContext context) {
        return context.getPlayer() == null || getBucketMode(context.getItemInHand()) ? InteractionResult.PASS : super.useOn(context);
    }

    @NotNull
    @Override
    public InteractionResultHolder<ItemStack> use(@NotNull Level world, Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (getBucketMode(stack)) {
            if (SecurityUtils.get().tryClaimItem(world, player, stack)) {
                return InteractionResultHolder.sidedSuccess(stack, world.isClientSide);
            } else if (!ISecurityUtils.INSTANCE.canAccessOrDisplayError(player, stack)) {
                return InteractionResultHolder.fail(stack);
            }
            //TODO: At some point maybe try to reduce the duplicate code between this and the dispense behavior
            BlockHitResult result = getPlayerPOVHitResult(world, player, player.isShiftKeyDown() ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY);
            //It can be null if there is nothing in range
            if (result.getType() == Type.BLOCK) {
                BlockPos pos = result.getBlockPos();
                if (!world.mayInteract(player, pos)) {
                    return InteractionResultHolder.fail(stack);
                }
                IExtendedFluidTank fluidTank = getExtendedFluidTank(stack);
                if (fluidTank == null) {
                    //If something went wrong, and we don't have a fluid tank fail
                    return InteractionResultHolder.fail(stack);
                }
                if (!player.isShiftKeyDown()) {
                    if (!player.mayUseItemAt(pos, result.getDirection(), stack)) {
                        return InteractionResultHolder.fail(stack);
                    }
                    //Note: we get the block state from the world so that we can get the proper block in case it is fluid logged
                    BlockState blockState = world.getBlockState(pos);
                    FluidState fluidState = blockState.getFluidState();
                    Optional<SoundEvent> sound = Optional.empty();
                    if (!fluidState.isEmpty() && fluidState.isSource()) {
                        //Just in case someone does weird things and has a fluid state that is empty and a source
                        // only allow collecting from non-empty sources
                        Fluid fluid = fluidState.getType();
                        FluidStack fluidStack = new FluidStack(fluid, FluidType.BUCKET_VOLUME);
                        Block block = blockState.getBlock();
                        if (block instanceof IFluidBlock fluidBlock) {
                            fluidStack = fluidBlock.drain(world, pos, FluidAction.SIMULATE);
                            if (!validFluid(fluidTank, fluidStack)) {
                                //If the fluid is not valid, pass on doing anything
                                return InteractionResultHolder.pass(stack);
                            }
                            //Actually drain it
                            fluidStack = fluidBlock.drain(world, pos, FluidAction.EXECUTE);
                        } else if (block instanceof BucketPickup bucketPickup && validFluid(fluidTank, fluidStack)) {
                            //If it can be picked up by a bucket, and we actually want to pick it up, do so to update the fluid type we are doing
                            // otherwise we assume the type from the fluid state is correct
                            ItemStack pickedUpStack = bucketPickup.pickupBlock(world, pos, blockState);
                            if (pickedUpStack.isEmpty()) {
                                //If the fluid can't be picked up, pass on doing anything
                                return InteractionResultHolder.pass(stack);
                            } else if (pickedUpStack.getItem() instanceof BucketItem bucket) {
                                //This isn't the best validation check given it may not return a bucket, but it is good enough for now
                                fluid = bucket.getFluid();
                                //Update the fluid stack in case something somehow changed about the type
                                // making sure that we replace to heavy water if we got heavy water
                                fluidStack = new FluidStack(fluid, FluidType.BUCKET_VOLUME);
                                if (!validFluid(fluidTank, fluidStack)) {
                                    Mekanism.logger.warn("Fluid removed without successfully picking up. Fluid {} at {} in {} was valid, but after picking up was {}.",
                                          RegistryUtils.getName(fluidState.getType()), pos, world.dimension().location(), RegistryUtils.getName(fluid));
                                    return InteractionResultHolder.fail(stack);
                                }
                            }
                            sound = bucketPickup.getPickupSound(blockState);
                        }
                        if (validFluid(fluidTank, fluidStack)) {
                            uncheckedGrow(fluidTank, fluidStack);
                            //Play the bucket fill sound
                            WorldUtils.playFillSound(player, world, pos, fluidStack, sound.orElse(null));
                            world.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
                            return InteractionResultHolder.success(stack);
                        }
                        return InteractionResultHolder.fail(stack);
                    }
                } else {
                    if (fluidTank.extract(FluidType.BUCKET_VOLUME, Action.SIMULATE, AutomationType.MANUAL).getAmount() < FluidType.BUCKET_VOLUME
                        || !player.mayUseItemAt(pos.relative(result.getDirection()), result.getDirection(), stack)) {
                        return InteractionResultHolder.fail(stack);
                    }
                    if (WorldUtils.tryPlaceContainedLiquid(player, world, pos, fluidTank.getFluid(), result.getDirection())) {
                        if (!player.isCreative()) {
                            //Manually shrink in case bucket volume is greater than tank input/output rate limit
                            MekanismUtils.logMismatchedStackSize(fluidTank.shrinkStack(FluidType.BUCKET_VOLUME, Action.EXECUTE), FluidType.BUCKET_VOLUME);
                        }
                        world.gameEvent(player, GameEvent.FLUID_PLACE, pos);
                        return InteractionResultHolder.success(stack);
                    }
                }
            }
        }
        return InteractionResultHolder.pass(stack);
    }

    //Used after simulation to insert the stack rather than just using the insert method to properly handle cases
    // where the stack for a single bucket may be above the tank's configured rate limit
    private void uncheckedGrow(IExtendedFluidTank fluidTank, FluidStack fluidStack) {
        if (getTier() != FluidTankTier.CREATIVE) {
            //No-OP creative handling as that is how insert would be handled for items
            if (fluidTank.isEmpty()) {
                fluidTank.setStack(fluidStack);
            } else {
                //Grow the stack
                MekanismUtils.logMismatchedStackSize(fluidTank.growStack(fluidStack.getAmount(), Action.EXECUTE), fluidStack.getAmount());
            }
        }
    }

    private static boolean validFluid(@NotNull IExtendedFluidTank fluidTank, @NotNull FluidStack fluidStack) {
        return !fluidStack.isEmpty() && fluidTank.insert(fluidStack, Action.SIMULATE, AutomationType.MANUAL).isEmpty();
    }

    private static IExtendedFluidTank getExtendedFluidTank(@NotNull ItemStack stack) {
        Optional<IFluidHandlerItem> capability = FluidUtil.getFluidHandler(stack).resolve();
        if (capability.isPresent()) {
            IFluidHandlerItem fluidHandlerItem = capability.get();
            if (fluidHandlerItem instanceof IMekanismFluidHandler fluidHandler) {
                return fluidHandler.getFluidTank(0, null);
            }
        }
        return null;
    }

    public void setBucketMode(ItemStack itemStack, boolean bucketMode) {
        ItemDataUtils.setBoolean(itemStack, NBTConstants.BUCKET_MODE, bucketMode);
    }

    public boolean getBucketMode(ItemStack itemStack) {
        return ItemDataUtils.getBoolean(itemStack, NBTConstants.BUCKET_MODE);
    }

    @Override
    protected void gatherCapabilities(List<ItemCapability> capabilities, ItemStack stack, CompoundTag nbt) {
        super.gatherCapabilities(capabilities, stack, nbt);
        capabilities.add(RateLimitFluidHandler.create(getTier()));
    }

    @Override
    public void changeMode(@NotNull Player player, @NotNull ItemStack stack, int shift, DisplayChange displayChange) {
        if (Math.abs(shift) % 2 == 1) {
            //We are changing by an odd amount, so toggle the mode
            boolean newState = !getBucketMode(stack);
            setBucketMode(stack, newState);
            displayChange.sendMessage(player, () -> MekanismLang.BUCKET_MODE.translate(OnOff.of(newState, true)));
        }
    }

    @NotNull
    @Override
    public Component getScrollTextComponent(@NotNull ItemStack stack) {
        return MekanismLang.BUCKET_MODE.translateColored(EnumColor.GRAY, OnOff.of(getBucketMode(stack), true));
    }

    public static class FluidTankItemDispenseBehavior extends DefaultDispenseItemBehavior {

        public static final FluidTankItemDispenseBehavior INSTANCE = new FluidTankItemDispenseBehavior();

        private FluidTankItemDispenseBehavior() {
        }

        @NotNull
        @Override
        public ItemStack execute(@NotNull BlockSource source, @NotNull ItemStack stack) {
            if (stack.getItem() instanceof ItemBlockFluidTank tank && tank.getBucketMode(stack)) {
                //If the fluid tank is in bucket mode allow for it to act as a bucket
                //Note: We don't use DispenseFluidContainer as we have more specific logic for determining if we want it to
                // act as a bucket that is emptying its contents or one that is picking up contents
                IExtendedFluidTank fluidTank = getExtendedFluidTank(stack);
                //Get the fluid tank for the stack
                if (fluidTank == null) {
                    //If there isn't one then there is something wrong with the stack, treat it as a normal stack and just eject it
                    return super.execute(source, stack);
                }
                Level world = source.getLevel();
                BlockPos pos = source.getPos().relative(source.getBlockState().getValue(DispenserBlock.FACING));
                //Note: we get the block state from the world so that we can get the proper block in case it is fluid logged
                BlockState blockState = world.getBlockState(pos);
                FluidState fluidState = blockState.getFluidState();
                Optional<SoundEvent> sound = Optional.empty();
                //If the fluid state in the world isn't empty and is a source try to pick it up otherwise try to dispense the stored fluid
                if (!fluidState.isEmpty() && fluidState.isSource()) {
                    //Just in case someone does weird things and has a fluid state that is empty and a source
                    // only allow collecting from non-empty sources
                    Fluid fluid = fluidState.getType();
                    FluidStack fluidStack = new FluidStack(fluid, FluidType.BUCKET_VOLUME);
                    Block block = blockState.getBlock();
                    if (block instanceof IFluidBlock fluidBlock) {
                        fluidStack = fluidBlock.drain(world, pos, FluidAction.SIMULATE);
                        if (!validFluid(fluidTank, fluidStack)) {
                            //If the fluid is not valid, then eject the stack similar to how vanilla does for buckets
                            return super.execute(source, stack);
                        }
                        //Actually drain it
                        fluidStack = fluidBlock.drain(world, pos, FluidAction.EXECUTE);
                    } else if (block instanceof BucketPickup bucketPickup && validFluid(fluidTank, fluidStack)) {
                        //If it can be picked up by a bucket, and we actually want to pick it up, do so to update the fluid type we are doing
                        // otherwise we assume the type from the fluid state is correct
                        ItemStack pickedUpStack = bucketPickup.pickupBlock(world, pos, blockState);
                        if (pickedUpStack.isEmpty()) {
                            //If the fluid cannot be picked up, then eject the stack similar to how vanilla does for buckets
                            return super.execute(source, stack);
                        } else if (pickedUpStack.getItem() instanceof BucketItem bucket) {
                            //This isn't the best validation check given it may not return a bucket, but it is good enough for now
                            fluid = bucket.getFluid();
                            //Update the fluid stack in case something somehow changed about the type
                            // making sure that we replace to heavy water if we got heavy water
                            fluidStack = new FluidStack(fluid, FluidType.BUCKET_VOLUME);
                            if (!validFluid(fluidTank, fluidStack)) {
                                Mekanism.logger.warn("Fluid removed without successfully picking up. Fluid {} at {} in {} was valid, but after picking up was {}.",
                                      RegistryUtils.getName(fluidState.getType()), pos, world.dimension().location(), RegistryUtils.getName(fluid));
                                //If we can't insert or extract it, then eject the stack similar to how vanilla does for buckets
                                return super.execute(source, stack);
                            }
                        }
                        sound = bucketPickup.getPickupSound(blockState);
                    }
                    if (validFluid(fluidTank, fluidStack)) {
                        tank.uncheckedGrow(fluidTank, fluidStack);
                        //Play the bucket fill sound
                        WorldUtils.playFillSound(null, world, pos, fluidStack, sound.orElse(null));
                        world.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
                        //Success, don't dispense anything just return our resulting stack
                        return stack;
                    }
                } else if (fluidTank.extract(FluidType.BUCKET_VOLUME, Action.SIMULATE, AutomationType.MANUAL).getAmount() >= FluidType.BUCKET_VOLUME) {
                    if (WorldUtils.tryPlaceContainedLiquid(null, world, pos, fluidTank.getFluid(), null)) {
                        //Manually shrink in case bucket volume is greater than tank input/output rate limit
                        MekanismUtils.logMismatchedStackSize(fluidTank.shrinkStack(FluidType.BUCKET_VOLUME, Action.EXECUTE), FluidType.BUCKET_VOLUME);
                        world.gameEvent(null, GameEvent.FLUID_PLACE, pos);
                        //Success, don't dispense anything just return our resulting stack
                        return stack;
                    }
                }
                //If we can't insert or extract it, then eject the stack similar to how vanilla does for buckets
            }
            //Otherwise, eject it as a normal item
            return super.execute(source, stack);
        }
    }

    public abstract static class BasicCauldronInteraction implements CauldronInteraction {

        public static final BasicCauldronInteraction EMPTY = new BasicCauldronInteraction() {
            @Nullable
            private BlockState getState(FluidStack current) {
                Fluid type = current.getFluid();
                if (type == Fluids.WATER) {
                    return Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3);
                } else if (type == Fluids.LAVA) {
                    return Blocks.LAVA_CAULDRON.defaultBlockState();
                }
                return null;
            }

            @NotNull
            @Override
            protected InteractionResult interact(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player,
                  @NotNull InteractionHand hand, @NotNull ItemStack stack, @NotNull IExtendedFluidTank fluidTank) {
                FluidStack fluidStack = fluidTank.getFluid();
                BlockState endState = getState(fluidStack);
                if (endState != null && fluidTank.extract(FluidType.BUCKET_VOLUME, Action.SIMULATE, AutomationType.MANUAL).getAmount() >= FluidType.BUCKET_VOLUME) {
                    if (!level.isClientSide) {
                        if (!player.isCreative()) {
                            //Manually shrink in case bucket volume is greater than tank input/output rate limit
                            MekanismUtils.logMismatchedStackSize(fluidTank.shrinkStack(FluidType.BUCKET_VOLUME, Action.EXECUTE), FluidType.BUCKET_VOLUME);
                        }
                        player.awardStat(Stats.FILL_CAULDRON);
                        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
                        level.setBlockAndUpdate(pos, endState);
                        SoundEvent emptySound = fluidStack.getFluid().getFluidType().getSound(player, level, pos, SoundActions.BUCKET_EMPTY);
                        if (emptySound != null) {
                            level.playSound(null, pos, emptySound, SoundSource.BLOCKS, 1.0F, 1.0F);
                        }
                        level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
                    }
                    return InteractionResult.sidedSuccess(level.isClientSide);
                }
                return InteractionResult.PASS;
            }
        };

        @NotNull
        @Override
        public final InteractionResult interact(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player,
              @NotNull InteractionHand hand, @NotNull ItemStack stack) {
            if (stack.getItem() instanceof ItemBlockFluidTank tank && tank.getBucketMode(stack)) {
                //If the fluid tank is in bucket mode allow for it to act as a bucket
                IExtendedFluidTank fluidTank = getExtendedFluidTank(stack);
                //Get the fluid tank for the stack
                if (fluidTank == null) {
                    //If there isn't one then there is something wrong with the stack, treat it as a normal stack and skip
                    return InteractionResult.PASS;
                }
                return interact(state, level, pos, player, hand, stack, fluidTank);
            }
            //Otherwise skip
            return InteractionResult.PASS;
        }

        @NotNull
        protected abstract InteractionResult interact(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player,
              @NotNull InteractionHand hand, @NotNull ItemStack stack, @NotNull IExtendedFluidTank fluidTank);
    }

    public static class BasicDrainCauldronInteraction extends BasicCauldronInteraction {

        public static final BasicDrainCauldronInteraction WATER = new BasicDrainCauldronInteraction(Fluids.WATER) {
            @NotNull
            @Override
            protected InteractionResult interact(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player,
                  @NotNull InteractionHand hand, @NotNull ItemStack stack, @NotNull IExtendedFluidTank fluidTank) {
                if (state.getValue(LayeredCauldronBlock.LEVEL) == 3) {
                    //When emptying a water cauldron make sure it is full and just ignore handling of partial transfers
                    // as while we can handle them, they come with the added complication of deciding what value to give bottles
                    return super.interact(state, level, pos, player, hand, stack, fluidTank);
                }
                return InteractionResult.PASS;
            }
        };
        public static final BasicDrainCauldronInteraction LAVA = new BasicDrainCauldronInteraction(Fluids.LAVA);

        private final Fluid type;

        private BasicDrainCauldronInteraction(Fluid type) {
            this.type = type;
        }

        @NotNull
        @Override
        protected InteractionResult interact(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player,
              @NotNull InteractionHand hand, @NotNull ItemStack stack, @NotNull IExtendedFluidTank fluidTank) {
            FluidStack fluidStack = new FluidStack(type, FluidType.BUCKET_VOLUME);
            FluidStack remainder = fluidTank.insert(fluidStack, Action.SIMULATE, AutomationType.MANUAL);
            if (remainder.isEmpty()) {
                //We can fit all the fluid we would be removing
                if (!level.isClientSide) {
                    if (!player.isCreative()) {
                        ((ItemBlockFluidTank) stack.getItem()).uncheckedGrow(fluidTank, fluidStack);
                    }
                    player.awardStat(Stats.USE_CAULDRON);
                    player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
                    level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState());
                    SoundEvent fillSound = fluidStack.getFluid().getFluidType().getSound(null, level, pos, SoundActions.BUCKET_FILL);
                    if (fillSound != null) {
                        level.playSound(null, pos, fillSound, SoundSource.BLOCKS, 1.0F, 1.0F);
                    }
                    level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            return InteractionResult.PASS;
        }
    }
}