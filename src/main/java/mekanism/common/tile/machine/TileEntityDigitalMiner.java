package mekanism.common.tile.machine;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.NBTConstants;
import mekanism.api.RelativeSide;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.math.FloatingLong;
import mekanism.common.CommonWorldTickHandler;
import mekanism.common.base.MekFakePlayer;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.energy.MinerEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.capabilities.resolver.BasicCapabilityResolver;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.filter.SortableFilterManager;
import mekanism.common.content.miner.MinerFilter;
import mekanism.common.content.miner.ThreadMinerSearch;
import mekanism.common.content.miner.ThreadMinerSearch.State;
import mekanism.common.integration.computer.ComputerException;
import mekanism.common.integration.computer.SpecialComputerMethodWrapper.ComputerIInventorySlotWrapper;
import mekanism.common.integration.computer.annotation.ComputerMethod;
import mekanism.common.integration.computer.annotation.WrappingComputerMethod;
import mekanism.common.integration.computer.computercraft.ComputerConstants;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableEnum;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.container.sync.SyncableItemStack;
import mekanism.common.inventory.container.sync.SyncableRegistryEntry;
import mekanism.common.inventory.container.tile.DigitalMinerConfigContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.item.gear.ItemAtomicDisassembler;
import mekanism.common.lib.chunkloading.IChunkLoader;
import mekanism.common.lib.inventory.Finder;
import mekanism.common.lib.inventory.HashedItem;
import mekanism.common.lib.inventory.TransitRequest;
import mekanism.common.lib.inventory.TransitRequest.TransitResponse;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tags.MekanismTags;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentChunkLoader;
import mekanism.common.tile.interfaces.IBoundingBlock;
import mekanism.common.tile.interfaces.IHasVisualization;
import mekanism.common.tile.interfaces.ISustainedData;
import mekanism.common.tile.interfaces.ITileFilterHolder;
import mekanism.common.tile.transmitter.TileEntityLogisticalTransporterBase;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.StackUtils;
import mekanism.common.util.UpgradeUtils;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TileEntityDigitalMiner extends TileEntityMekanism implements ISustainedData, IChunkLoader, IBoundingBlock, ITileFilterHolder<MinerFilter<?>>,
      IHasVisualization {

    public static final int DEFAULT_HEIGHT_RANGE = 60;
    public static final int DEFAULT_RADIUS = 10;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final SortableFilterManager<MinerFilter<?>> filterManager = new SortableFilterManager<MinerFilter<?>>((Class) MinerFilter.class, this::markForSave);
    private Long2ObjectMap<BitSet> oresToMine = Long2ObjectMaps.emptyMap();
    public ThreadMinerSearch searcher = new ThreadMinerSearch(this);

    private int radius;
    private boolean inverse;
    private boolean inverseRequiresReplacement;
    private Item inverseReplaceTarget = Items.AIR;
    private int minY;
    private int maxY = minY + DEFAULT_HEIGHT_RANGE;
    private boolean doEject = false;
    private boolean doPull = false;
    public ItemStack missingStack = ItemStack.EMPTY;

    private final Predicate<ItemStack> overflowCollector = this::trackOverflow;
    //Note: Linked map to ensure each call to save is in the same order so that there is more uniformity
    private final Object2IntMap<HashedItem> overflow = new Object2IntLinkedOpenHashMap<>();
    private boolean hasOverflow;
    private boolean recheckOverflow;

    private int delay;
    private int delayLength = MekanismConfig.general.minerTicksPerMine.get();
    private int cachedToMine;
    private boolean silkTouch;
    private boolean running;
    private int delayTicks;
    private boolean initCalc = false;
    private int numPowering;
    private boolean clientRendering;

    private final TileComponentChunkLoader<TileEntityDigitalMiner> chunkLoaderComponent = new TileComponentChunkLoader<>(this);
    @Nullable
    private ChunkPos targetChunk;

    private MinerEnergyContainer energyContainer;
    private List<IInventorySlot> mainSlots;
    @WrappingComputerMethod(wrapper = ComputerIInventorySlotWrapper.class, methodNames = "getEnergyItem", docPlaceholder = "energy slot")
    EnergyInventorySlot energySlot;

    public TileEntityDigitalMiner(BlockPos pos, BlockState state) {
        super(MekanismBlocks.DIGITAL_MINER, pos, state);
        radius = DEFAULT_RADIUS;
        addCapabilityResolver(BasicCapabilityResolver.constant(Capabilities.CONFIG_CARD, this));
        //Return some capabilities as disabled, and handle them with offset capabilities instead
        addDisabledCapabilities(ForgeCapabilities.ITEM_HANDLER);
    }

    @NotNull
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSide(this::getDirection);
        builder.addContainer(energyContainer = MinerEnergyContainer.input(this, listener), RelativeSide.LEFT, RelativeSide.RIGHT, RelativeSide.BOTTOM);
        return builder.build();
    }

    @NotNull
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        mainSlots = new ArrayList<>();
        IContentsListener mainSlotListener = () -> {
            listener.onContentsChanged();
            //Ensure we recheck if our overflow can fit anywhere
            recheckOverflow = true;
        };
        InventorySlotHelper builder = InventorySlotHelper.forSide(this::getDirection, side -> side == RelativeSide.TOP, side -> side == RelativeSide.BACK);
        //Allow insertion manually or internally, or if it is a replace stack
        BiPredicate<@NotNull ItemStack, @NotNull AutomationType> canInsert = (stack, automationType) -> automationType != AutomationType.EXTERNAL || isReplaceTarget(stack.getItem());
        //Allow extraction if it is manual or for internal usage, or if it is not a replace stack
        //Note: We don't currently use internal for extraction anywhere here as we just shrink replace stacks directly
        BiPredicate<@NotNull ItemStack, @NotNull AutomationType> canExtract = (stack, automationType) -> automationType != AutomationType.EXTERNAL || !isReplaceTarget(stack.getItem());
        for (int slotY = 0; slotY < 3; slotY++) {
            for (int slotX = 0; slotX < 9; slotX++) {
                BasicInventorySlot slot = BasicInventorySlot.at(canExtract, canInsert, mainSlotListener, 8 + slotX * 18, 92 + slotY * 18);
                builder.addSlot(slot, RelativeSide.BACK, RelativeSide.TOP);
                mainSlots.add(slot);
            }
        }
        builder.addSlot(energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel, listener, 152, 20));
        return builder.build();
    }

    private void closeInvalidScreens() {
        if (getActive() && !playersUsing.isEmpty()) {
            for (Player player : new ObjectOpenHashSet<>(playersUsing)) {
                if (player.containerMenu instanceof DigitalMinerConfigContainer) {
                    player.closeContainer();
                }
            }
        }
    }

    @Override
    protected void onUpdateClient() {
        super.onUpdateClient();
        closeInvalidScreens();
    }

    @Override
    protected void onUpdateServer() {
        super.onUpdateServer();
        closeInvalidScreens();
        if (!initCalc) {
            //If it had finished searching, and we didn't initialize things yet,
            // reset it and start running again if needed. This happens after saving the miner to disk
            if (searcher.state == State.FINISHED) {
                boolean prevRunning = running;
                reset();
                start();
                running = prevRunning;
            }
            initCalc = true;
        }

        energySlot.fillContainerOrConvert();

        if (recheckOverflow) {
            //Try adding any overflow stacks we have before we actually try to process as if we have some overflow we can't add
            // then we will skip functioning and avoid draining energy.
            // Note: We may not have any overflow stacks, in which case this will effectively NO-OP
            // We also mark needing to recheck if the overflow can fit as false as we will know if we can or can't currently add it all
            tryAddOverflow();
        }

        //Note: If we have any overflow don't function or use any energy until the overflow has been dealt with
        if (!hasOverflow && MekanismUtils.canFunction(this) && running && searcher.state == State.FINISHED && !oresToMine.isEmpty()) {
            FloatingLong energyPerTick = energyContainer.getEnergyPerTick();
            if (energyContainer.extract(energyPerTick, Action.SIMULATE, AutomationType.INTERNAL).equals(energyPerTick)) {
                setActive(true);
                if (delay > 0) {
                    delay--;
                }
                //TODO: Eventually we may want to avoid draining energy if we can't function due to a missing replace stack or the normal drops
                // being too much to fit
                energyContainer.extract(energyPerTick, Action.EXECUTE, AutomationType.INTERNAL);
                if (delay == 0) {
                    tryMineBlock();
                    delay = getDelay();
                }
            } else {
                setActive(false);
            }
        } else {
            setActive(false);
        }

        if (doEject && delayTicks == 0) {
            Direction oppositeDirection = getOppositeDirection();
            BlockEntity ejectInv = WorldUtils.getTileEntity(level, getBlockPos().above().relative(oppositeDirection, 2));
            BlockEntity ejectTile = WorldUtils.getTileEntity(getLevel(), getBlockPos().above().relative(oppositeDirection));
            if (ejectInv != null && ejectTile != null) {
                TransitRequest ejectMap = InventoryUtils.getEjectItemMap(ejectTile, oppositeDirection, mainSlots);
                if (!ejectMap.isEmpty()) {
                    TransitResponse response;
                    if (ejectInv instanceof TileEntityLogisticalTransporterBase transporter) {
                        response = transporter.getTransmitter().insert(ejectTile, ejectMap, transporter.getTransmitter().getColor(), true, 0);
                    } else {
                        response = ejectMap.addToInventory(ejectInv, oppositeDirection, 0, false);
                    }
                    if (!response.isEmpty()) {
                        response.useAll();
                    }
                }
                delayTicks = 10;
            }
        } else if (delayTicks > 0) {
            delayTicks--;
        }
    }

    public void updateFromSearch(Long2ObjectMap<BitSet> oresToMine, int found) {
        this.oresToMine = oresToMine;
        cachedToMine = found;
        updateTargetChunk(null);
        markForSave();
    }

    public int getDelay() {
        return delayLength;
    }

    @ComputerMethod(methodDescription = "Whether Silk Touch mode is enabled or not")
    public boolean getSilkTouch() {
        return silkTouch;
    }

    @ComputerMethod(methodDescription = "Get the current radius configured (blocks)")
    public int getRadius() {
        return radius;
    }

    @ComputerMethod(methodDescription = "Gets the configured minimum Y level for mining")
    public int getMinY() {
        return minY;
    }

    @ComputerMethod(methodDescription = "Gets the configured maximum Y level for mining")
    public int getMaxY() {
        return maxY;
    }

    @ComputerMethod(nameOverride = "getInverseMode", methodDescription = "Whether Inverse Mode is enabled or not")
    public boolean getInverse() {
        return inverse;
    }

    @ComputerMethod(nameOverride = "getInverseModeRequiresReplacement", methodDescription = "Whether Inverse Mode Require Replacement is turned on")
    public boolean getInverseRequiresReplacement() {
        return inverseRequiresReplacement;
    }

    @ComputerMethod(nameOverride = "getInverseModeReplaceTarget", methodDescription = "Get the configured Replacement target item")
    public Item getInverseReplaceTarget() {
        return inverseReplaceTarget;
    }

    private void setSilkTouch(boolean newSilkTouch) {
        if (silkTouch != newSilkTouch) {
            silkTouch = newSilkTouch;
            if (hasLevel() && !isRemote()) {
                energyContainer.updateMinerEnergyPerTick();
            }
        }
    }

    public void toggleSilkTouch() {
        setSilkTouch(!getSilkTouch());
        markForSave();
    }

    public void toggleInverse() {
        inverse = !inverse;
        markForSave();
    }

    public void toggleInverseRequiresReplacement() {
        inverseRequiresReplacement = !inverseRequiresReplacement;
        markForSave();
    }

    public void setInverseReplaceTarget(Item target) {
        if (target != inverseReplaceTarget) {
            inverseReplaceTarget = target;
            markForSave();
        }
    }

    public void toggleAutoEject() {
        doEject = !doEject;
        markForSave();
    }

    public void toggleAutoPull() {
        doPull = !doPull;
        markForSave();
    }

    public void setRadiusFromPacket(int newRadius) {
        setRadius(Mth.clamp(newRadius, 0, MekanismConfig.general.minerMaxRadius.get()));
        //Send a packet to update the visual renderer
        //TODO: Only do this if the renderer is actually active
        sendUpdatePacket();
        markForSave();
    }

    private void setRadius(int newRadius) {
        if (radius != newRadius) {
            radius = newRadius;
            if (hasLevel() && !isRemote()) {
                energyContainer.updateMinerEnergyPerTick();
                // If the radius changed, and we're on the server, go ahead and refresh the chunk set
                getChunkLoader().refreshChunkTickets();
            }
        }
    }

    public void setMinYFromPacket(int newMinY) {
        if (level != null) {
            setMinY(Mth.clamp(newMinY, level.getMinBuildHeight(), getMaxY()));
            //Send a packet to update the visual renderer
            //TODO: Only do this if the renderer is actually active
            sendUpdatePacket();
            markForSave();
        }
    }

    private void setMinY(int newMinY) {
        if (minY != newMinY) {
            minY = newMinY;
            if (hasLevel() && !isRemote()) {
                energyContainer.updateMinerEnergyPerTick();
            }
        }
    }

    public void setMaxYFromPacket(int newMaxY) {
        if (level != null) {
            setMaxY(Mth.clamp(newMaxY, getMinY(), level.getMaxBuildHeight() - 1));
            //Send a packet to update the visual renderer
            //TODO: Only do this if the renderer is actually active
            sendUpdatePacket();
            markForSave();
        }
    }

    private void setMaxY(int newMaxY) {
        if (maxY != newMaxY) {
            maxY = newMaxY;
            if (hasLevel() && !isRemote()) {
                energyContainer.updateMinerEnergyPerTick();
            }
        }
    }

    private void tryMineBlock() {
        BlockPos startingPos = getStartingPos();
        int diameter = getDiameter();
        long target = targetChunk == null ? ChunkPos.INVALID_CHUNK_POS : targetChunk.toLong();
        for (ObjectIterator<Long2ObjectMap.Entry<BitSet>> it = oresToMine.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<BitSet> entry = it.next();
            long chunk = entry.getLongKey();
            BitSet chunkToMine = entry.getValue();
            ChunkPos currentChunk = null;
            if (target == chunk) {
                //If our current chunk is the one we are already targeting, just make it reference it, so we don't need to
                // do any initialization
                currentChunk = targetChunk;
            }
            //Note: We go in reverse order instead of normal order to avoid issues where we break blocks supporting ones
            // that are affected by gravity and then are unable to break them after they have fallen. The reason we do it
            // this way instead of changing how the bits are indexed in correspondence with locations in the world is because
            // it is much more likely for there to be blocks lower down, and this allows us to avoid having to add large indices
            // to our bitset because of all the small indices having been taken up by air
            //Length returns the largest set bit + 1, so we subtract one to get the largest set bit as previousSetBit is inclusive,
            // and if none are set and this becomes -1, previousSetBit will still just return -1
            int previous = chunkToMine.length() - 1;
            while (true) {
                int index = chunkToMine.previousSetBit(previous);
                if (index == -1) {
                    //If there is no found index, remove it and continue on
                    it.remove();
                    break;
                } else if (currentChunk == null) {
                    //Lazy init the current chunk so that if it is empty, and we are just going to remove it
                    // we don't need to try and load it
                    updateTargetChunk(currentChunk = new ChunkPos(chunk));
                    target = chunk;
                }
                BlockPos pos = getOffsetForIndex(startingPos, diameter, index);
                Optional<BlockState> blockState = WorldUtils.getBlockState(level, pos);
                if (blockState.isPresent()) {
                    BlockState state = blockState.get();
                    if (!state.isAir() && !state.is(MekanismTags.Blocks.MINER_BLACKLIST)) {
                        //Make sure the block is loaded and is not air, and is not in the blacklist of blocks the miner can break
                        // then check if the block matches one of our filters
                        MinerFilter<?> matchingFilter = null;
                        for (MinerFilter<?> filter : filterManager.getEnabledFilters()) {
                            if (filter.canFilter(state)) {
                                matchingFilter = filter;
                                break;
                            }
                        }
                        //If our hasFilter state matches our inversion state, that means we should try to mine
                        // the block, so we check if we can mine it
                        if (inverse == (matchingFilter == null) && canMine(state, pos)) {
                            //If we can, then validate we can fit the drops and try to see if we can replace it properly as well
                            List<ItemStack> drops = getDrops(state, pos);
                            if (canInsert(drops)) {
                                CommonWorldTickHandler.fallbackItemCollector = overflowCollector;
                                if (setReplace(state, pos, matchingFilter)) {
                                    add(drops);
                                    //Try to add any drops that might have been caused by breaking the block but didn't show up in the loot table.
                                    // This mainly will be the case for some single block multiblocks and also for storage containers like chests
                                    tryAddOverflow();
                                    missingStack = ItemStack.EMPTY;
                                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                                    //Remove the block from our list of blocks to mine, and reduce the number of blocks we have to mine
                                    cachedToMine--;
                                    chunkToMine.clear(index);
                                    if (chunkToMine.isEmpty()) {
                                        // if we are out of stored elements then we remove this chunk and continue to check other chunks
                                        // remove it so that we don't have to check the chunk next time around
                                        it.remove();
                                        // we no longer have a chunk we are targeting, so remove it. We might get a new chunk to target
                                        // next time we try to mine but there is no reason to keep the old chunk in memory in the meantime
                                        updateTargetChunk(null);
                                    }
                                }
                                //Reset the global fallback collector to null as we are done collecting for this miner and block
                                CommonWorldTickHandler.fallbackItemCollector = null;
                            }
                            //Exit out. We either mined the block or don't have room so there is no reason to continue checking
                            return;
                        }
                    }
                }
                //If we failed to mine the block, because it isn't loaded, is air, or we shouldn't mine it
                // remove the block from our list of blocks to mine, and reduce the number of blocks we have to mine
                cachedToMine--;
                chunkToMine.clear(index);
                if (chunkToMine.isEmpty()) {
                    // if we are out of stored elements then we remove this chunk and continue to check other chunks
                    it.remove();
                    break;
                }
                // if we still have elements in this chunk that can potentially be mined, decrement our index
                // to the previous one and attempt to mine it
                previous = index - 1;
            }
        }
        //If we didn't exit early due to actually mining a block that means we don't have a target chunk anymore
        updateTargetChunk(null);
    }

    /**
     * @param filter Filter that was matched, if in inverse mode this will be null
     *
     * @return false if unsuccessful
     */
    private boolean setReplace(BlockState state, BlockPos pos, @Nullable MinerFilter<?> filter) {
        if (level == null) {
            return false;
        }
        Item replaceTarget;
        ItemStack stack;
        if (filter == null) {
            stack = getReplace(replaceTarget = inverseReplaceTarget, this::inverseReplaceTargetMatches);
        } else {
            stack = getReplace(replaceTarget = filter.replaceTarget, filter::replaceTargetMatches);
        }
        if (stack.isEmpty()) {
            if (replaceTarget == Items.AIR || (filter == null && !inverseRequiresReplacement) || (filter != null && !filter.requiresReplacement)) {
                level.removeBlock(pos, false);
                level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(null, state));
                return true;
            }
            missingStack = new ItemStack(replaceTarget);
            return false;
        }
        BlockState newState = withFakePlayer(fakePlayer -> StackUtils.getStateForPlacement(stack, pos, fakePlayer));
        if (newState == null || !newState.canSurvive(level, pos)) {
            //If the spot is not a valid position for the block, then we return that we were unsuccessful
            return false;
        }
        //TODO: We may want to evaluate at some point doing this with our fake player so that it is fired as the "cause"?
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(null, state));
        level.setBlockAndUpdate(pos, newState);
        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(null, newState));
        return true;
    }

    private boolean canMine(BlockState state, BlockPos pos) {
        return withFakePlayer(dummy -> !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, dummy)));
    }

    private <R> R withFakePlayer(Function<MekFakePlayer, R> fakePlayerConsumer) {
        return MekFakePlayer.withFakePlayer((ServerLevel) level, this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ(), dummy -> {
            dummy.setEmulatingUUID(getOwnerUUID());//pretend to be the owner
            return fakePlayerConsumer.apply(dummy);
        });
    }

    private ItemStack getReplace(Item replaceTarget, Predicate<Item> replaceStackMatches) {
        if (replaceTarget == Items.AIR) {
            return ItemStack.EMPTY;
        }
        //Start by sourcing from the miner's inventory
        for (IInventorySlot slot : mainSlots) {
            ItemStack slotStack = slot.getStack();
            if (replaceStackMatches.test(slotStack.getItem())) {
                MekanismUtils.logMismatchedStackSize(slot.shrinkStack(1, Action.EXECUTE), 1);
                return slotStack.copyWithCount(1);
            }
        }
        //Then source from the upgrade if it is installed
        if (replaceTarget == Items.COBBLESTONE || replaceTarget == Items.STONE) {
            if (upgradeComponent.isUpgradeInstalled(Upgrade.STONE_GENERATOR)) {
                return new ItemStack(replaceTarget);
            }
        }
        //And finally source from the inventory on top if auto pull is enabled
        if (doPull) {
            BlockEntity pullInv = getPullInv();
            if (pullInv != null && InventoryUtils.isItemHandler(pullInv, Direction.DOWN)) {
                TransitRequest request = TransitRequest.definedItem(pullInv, Direction.DOWN, 1, Finder.item(replaceTarget));
                if (!request.isEmpty()) {
                    TransitResponse response = request.createSimpleResponse();
                    if (response.useAll().isEmpty()) {
                        //If the request isn't empty, and we were able to successfully use it all
                        return response.getStack().copyWithCount(1);
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean canInsert(List<ItemStack> toInsert) {
        if (toInsert.isEmpty()) {
            return true;
        }
        int slots = mainSlots.size();
        Int2ObjectMap<ItemCount> cachedStacks = new Int2ObjectOpenHashMap<>(slots);
        for (int i = 0; i < slots; i++) {
            IInventorySlot slot = mainSlots.get(i);
            if (!slot.isEmpty()) {
                //Note: We skip caching the current stack of any empty slots
                cachedStacks.put(i, new ItemCount(slot.getStack(), slot.getCount()));
            }
        }
        for (ItemStack stackToInsert : toInsert) {
            ItemStack stack = simulateInsert(cachedStacks, slots, stackToInsert);
            if (!stack.isEmpty()) {
                //If our stack is not empty that means we could not fit it all inside of our inventory,
                // so we return false to being able to insert all the items.
                return false;
            }
        }
        return true;
    }

    /**
     * Prioritizes "inserting" into slots that have a matching item and then tries to insert into empty slots. This allows for more accurate simulations regarding if it
     * is possible to fit everything in the inventory.
     */
    private ItemStack simulateInsert(Int2ObjectMap<ItemCount> cachedStacks, int slots, ItemStack stackToInsert) {
        if (stackToInsert.isEmpty()) {
            //If the stack is already empty for some reason just return it (aka no remainder)
            return stackToInsert;
        }
        ItemStack stack = stackToInsert.copy();
        //Try to simulate inserting into slots that are not currently empty
        for (int i = 0; i < slots; i++) {
            ItemCount cachedItem = cachedStacks.get(i);
            if (cachedItem != null && ItemHandlerHelper.canItemStacksStack(stack, cachedItem.stack)) {
                //Ensure that our stack can stack with the item that is already in the slot
                IInventorySlot slot = mainSlots.get(i);
                int limit = slot.getLimit(stack);
                if (cachedItem.count < limit) {
                    //If we still have space left before this slot is full, try adding the stacks together
                    cachedItem.count += stack.getCount();
                    if (cachedItem.count <= limit) {
                        //If we can fit it all, return we have no remainder
                        return ItemStack.EMPTY;
                    }
                    //Otherwise, we tried to store more than can fit, update stack to represent the remainder that didn't fit
                    stack = stack.copyWithCount(cachedItem.count - limit);
                    // and update the actual amount stored to the limit of the slot
                    cachedItem.count = limit;
                }
            }
        }
        //Try to simulate inserting into slots that are currently empty
        for (int i = 0; i < slots; i++) {
            if (!cachedStacks.containsKey(i)) {
                //We have no cache of this slot, which means that it is currently empty
                IInventorySlot slot = mainSlots.get(i);
                int stackSize = stack.getCount();
                //Attempt to insert the stack into the slot, the expected outcome given our slots' restrictions is that
                // this will succeed and insert the entire stack
                stack = slot.insertItem(stack, Action.SIMULATE, AutomationType.INTERNAL);
                int remainderSize = stack.getCount();
                if (remainderSize < stackSize) {
                    //If the slot accepted at least some item we are inserting, then cache the item type that we put into that slot
                    // Given the slot is empty the expected result is that we will always end up inserting into the first empty slot
                    // and end up inserting the entire stack
                    cachedStacks.put(i, new ItemCount(stackToInsert, stackSize - remainderSize));
                    if (stack.isEmpty()) {
                        //Stack was fully accepted, return that we have no remainder
                        return ItemStack.EMPTY;
                    }
                }
            }
        }
        return stack;
    }

    private BlockEntity getPullInv() {
        return WorldUtils.getTileEntity(getLevel(), getBlockPos().above(2));
    }

    private void add(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            //Try inserting it first where it can stack and then into empty slots
            stack = InventoryUtils.insertItem(mainSlots, stack, Action.EXECUTE, AutomationType.INTERNAL);
            if (!stack.isEmpty()) {
                //Because of the simulated insertion the stack should never be able to be empty here,
                // but in case it is keep track of any excess as overflow
                trackOverflow(stack);
            }
        }
    }

    private boolean trackOverflow(ItemStack stack) {
        //Note: We never expect the stack to be empty but in case it is just don't handle the stack
        if (!stack.isEmpty()) {
            //Note: While we probably could get away by using a raw hashed item given we are removing the item entity for the stack
            // we don't bother in case any other mods are doing weird things with it as this is just an edge case handler so shouldn't
            // be a hotspot in regard to copying stacks
            overflow.mergeInt(HashedItem.create(stack), stack.getCount(), Integer::sum);
            //If we add something to the overflow map, mark that we have overflow
            hasOverflow = true;
            //Mark that we need to recheck if we can insert the overflow as we now have some
            recheckOverflow = true;
            markForSave();
            return true;
        }
        return false;
    }

    private void tryAddOverflow() {
        if (hasOverflow) {
            //Try to add any existing overflow to our inventory
            boolean recheck = false;
            for (ObjectIterator<Object2IntMap.Entry<HashedItem>> iter = overflow.object2IntEntrySet().iterator(); iter.hasNext(); ) {
                Object2IntMap.Entry<HashedItem> entry = iter.next();
                int amount = entry.getIntValue();
                ItemStack stack = entry.getKey().createStack(amount);
                //Note: Inserting properly handles oversized stacks, so we don't have to handle the case that amount might be greater than
                // the max stack size here as the different slots will only accept up to the item's max stack size
                stack = InventoryUtils.insertItem(mainSlots, stack, Action.EXECUTE, AutomationType.INTERNAL);
                //Note: We do not need to mark the miner for saving if something gets moved from overflow to a slot as the slot will do so
                // when it accepts the item, so we can skip marking that we need to save because overflow changed
                if (stack.isEmpty()) {
                    //We were able to fully fit the stack, so we can remove it from our list of overflow
                    iter.remove();
                    recheck = true;
                } else if (stack.getCount() != amount) {
                    //Some was able to fit, update the amount that is actually still part of the overflow
                    entry.setValue(stack.getCount());
                }
            }
            if (recheck) {
                //Update if we still have an overflow as at least one stack was able to fit
                hasOverflow = !overflow.isEmpty();
            }
        }
        //Mark it as not needing to recheck the overflow as we just tried to add it, so we fit whatever we could or didn't even have any overflow
        recheckOverflow = false;
    }

    public void start() {
        if (getLevel() == null) {
            return;
        }
        if (searcher.state == State.IDLE) {
            BlockPos startingPos = getStartingPos();
            int diameter = getDiameter();
            searcher.setChunkCache(new PathNavigationRegion(getLevel(), startingPos, startingPos.offset(diameter, getMaxY() - getMinY() + 1, diameter)));
            searcher.start();
        }
        running = true;
        markForSave();
    }

    public void stop() {
        if (searcher.state == State.SEARCHING) {
            searcher.interrupt();
            reset();
        } else if (searcher.state == State.FINISHED) {
            running = false;
            markForSave();
            //Reset the target chunk, so it isn't loaded as we might don't want to let the user just have two chunks loaded
            // eternally (or until server restart) by intentionally stopping the miner
            updateTargetChunk(null);
        }
    }

    public void reset() {
        //TODO: Should the old searcher thread be terminated somehow?
        searcher = new ThreadMinerSearch(this);
        running = false;
        cachedToMine = 0;
        oresToMine = Long2ObjectMaps.emptyMap();
        missingStack = ItemStack.EMPTY;
        setActive(false);
        updateTargetChunk(null);
        markForSave();
    }

    public boolean isReplaceTarget(Item target) {
        if (inverse) {
            //If we are in inverse mode only check our replace target, and not the filter's replace targets
            // as we don't have a matching filter once we are breaking blocks so there wouldn't actually
            // be any cases where it makes sense to skip them due to them being the result of one of the
            // things we are mining
            return inverseReplaceTargetMatches(target);
        }
        return filterManager.anyEnabledMatch(filter -> filter.replaceTargetMatches(target));
    }

    /**
     * @apiNote Assumes that inverse is checked before this is called
     */
    private boolean inverseReplaceTargetMatches(Item target) {
        return inverseReplaceTarget != Items.AIR && inverseReplaceTarget == target;
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);
        running = nbt.getBoolean(NBTConstants.RUNNING);
        delay = nbt.getInt(NBTConstants.DELAY);
        numPowering = nbt.getInt(NBTConstants.NUM_POWERING);
        NBTUtils.setEnumIfPresent(nbt, NBTConstants.STATE, State::byIndexStatic, s -> {
            if (!initCalc && s == State.SEARCHING) {
                //If we loaded and haven't started yet, but we were searching when we saved
                // pretend we had finished searching so that we will start again on the first tick
                s = State.FINISHED;
            }
            searcher.state = s;
        });
        //Update energy per tick in case any of the values changed. It would be slightly cleaner to also validate the fact
        // the values changed, but it would make the code a decent bit messier, as we couldn't use NBTUtils, and it is a
        // rather quick check to update the energy per tick, and in most cases at least one of the settings will not be at
        // the default value
        energyContainer.updateMinerEnergyPerTick();
    }

    @Override
    public void setLevel(@NotNull Level world) {
        super.setLevel(world);
        //Update miner energy as the world height is likely different compared to the old pre 1.18 values
        energyContainer.updateMinerEnergyPerTick();
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag nbtTags) {
        super.saveAdditional(nbtTags);
        nbtTags.putBoolean(NBTConstants.RUNNING, running);
        nbtTags.putInt(NBTConstants.DELAY, delay);
        nbtTags.putInt(NBTConstants.NUM_POWERING, numPowering);
        NBTUtils.writeEnum(nbtTags, NBTConstants.STATE, searcher.state);
        if (!overflow.isEmpty()) {
            //Persist any items that are stored as overflow
            ListTag overflowTag = new ListTag();
            for (Object2IntMap.Entry<HashedItem> entry : overflow.object2IntEntrySet()) {
                CompoundTag overflowComponent = new CompoundTag();
                overflowComponent.put(NBTConstants.TYPE, entry.getKey().internalToNBT());
                overflowComponent.putInt(NBTConstants.COUNT, entry.getIntValue());
                overflowTag.add(overflowComponent);
            }
            nbtTags.put(NBTConstants.OVERFLOW, overflowTag);
        }
    }

    public int getTotalSize() {
        int diameter = getDiameter();
        return diameter * diameter * (getMaxY() - getMinY() + 1);
    }

    public int getDiameter() {
        return (radius * 2) + 1;
    }

    public BlockPos getStartingPos() {
        return new BlockPos(getBlockPos().getX() - radius, getMinY(), getBlockPos().getZ() - radius);
    }

    public static BlockPos getOffsetForIndex(BlockPos start, int diameter, int index) {
        return start.offset(index % diameter, index / diameter / diameter, (index / diameter) % diameter);
    }

    @Override
    public boolean isPowered() {
        return redstone || numPowering > 0;
    }

    @NotNull
    @Override
    public AABB getRenderBoundingBox() {
        if (isClientRendering() && canDisplayVisuals()) {
            return new AABB(
                  worldPosition.getX() - radius,
                  minY,
                  worldPosition.getZ() - radius,
                  worldPosition.getX() + radius + 1,
                  maxY + 1,
                  worldPosition.getZ() + radius + 1
            );
        }
        return super.getRenderBoundingBox();
    }

    @Override
    public boolean isClientRendering() {
        return clientRendering;
    }

    @Override
    public void toggleClientRendering() {
        this.clientRendering = !clientRendering;
    }

    @Override
    public boolean canDisplayVisuals() {
        return getRadius() <= 64;
    }

    @Override
    public void onBoundingBlockPowerChange(BlockPos boundingPos, int oldLevel, int newLevel) {
        if (oldLevel > 0) {
            if (newLevel == 0) {
                numPowering--;
            }
        } else if (newLevel > 0) {
            numPowering++;
        }
    }

    @Override
    public int getBoundingComparatorSignal(Vec3i offset) {
        //Return the comparator signal if it is one of the horizontal ports
        Direction facing = getDirection();
        Direction back = facing.getOpposite();
        if (offset.equals(new Vec3i(back.getStepX(), 1, back.getStepZ()))) {
            return getCurrentRedstoneLevel();
        }
        Direction left = MekanismUtils.getLeft(facing);
        if (offset.equals(new Vec3i(left.getStepX(), 0, left.getStepZ()))) {
            return getCurrentRedstoneLevel();
        }
        Direction right = left.getOpposite();
        if (offset.equals(new Vec3i(right.getStepX(), 0, right.getStepZ()))) {
            return getCurrentRedstoneLevel();
        }
        return 0;
    }

    @Override
    protected void notifyComparatorChange() {
        super.notifyComparatorChange();
        Direction facing = getDirection();
        Direction left = MekanismUtils.getLeft(facing);
        //Proxy the comparator updates to the various ports we expose comparators to
        level.updateNeighbourForOutputSignal(worldPosition.relative(left), MekanismBlocks.BOUNDING_BLOCK.getBlock());
        level.updateNeighbourForOutputSignal(worldPosition.relative(left.getOpposite()), MekanismBlocks.BOUNDING_BLOCK.getBlock());
        level.updateNeighbourForOutputSignal(worldPosition.relative(facing.getOpposite()).above(), MekanismBlocks.BOUNDING_BLOCK.getBlock());
    }

    @Override
    public void configurationDataSet() {
        super.configurationDataSet();
        if (isRunning()) {
            //If it was running when we updated the configuration data, stop it, reset it, and start it again
            // to ensure that there are no desyncs in energy cost due to things like the radius changing but
            // having the blocks to mine be calculated based on the old radius
            stop();
            reset();
            start();
        }
    }

    @Override
    public void writeSustainedData(CompoundTag dataMap) {
        dataMap.putInt(NBTConstants.RADIUS, getRadius());
        dataMap.putInt(NBTConstants.MIN, getMinY());
        dataMap.putInt(NBTConstants.MAX, getMaxY());
        dataMap.putBoolean(NBTConstants.EJECT, doEject);
        dataMap.putBoolean(NBTConstants.PULL, doPull);
        dataMap.putBoolean(NBTConstants.SILK_TOUCH, getSilkTouch());
        dataMap.putBoolean(NBTConstants.INVERSE, inverse);
        if (inverseReplaceTarget != Items.AIR) {
            NBTUtils.writeRegistryEntry(dataMap, NBTConstants.REPLACE_STACK, ForgeRegistries.ITEMS, inverseReplaceTarget);
        }
        dataMap.putBoolean(NBTConstants.INVERSE_REQUIRES_REPLACE, inverseRequiresReplacement);
        filterManager.writeToNBT(dataMap);
    }

    @Override
    public void readSustainedData(CompoundTag dataMap) {
        setRadius(Math.min(dataMap.getInt(NBTConstants.RADIUS), MekanismConfig.general.minerMaxRadius.get()));
        NBTUtils.setIntIfPresent(dataMap, NBTConstants.MIN, newMinY -> {
            if (hasLevel() && !isRemote()) {
                setMinY(Math.max(newMinY, level.getMinBuildHeight()));
            } else {
                setMinY(newMinY);
            }
        });
        NBTUtils.setIntIfPresent(dataMap, NBTConstants.MAX, newMaxY -> {
            if (hasLevel() && !isRemote()) {
                setMaxY(Math.min(newMaxY, level.getMaxBuildHeight() - 1));
            } else {
                setMaxY(newMaxY);
            }
        });
        NBTUtils.setBooleanIfPresent(dataMap, NBTConstants.EJECT, eject -> doEject = eject);
        NBTUtils.setBooleanIfPresent(dataMap, NBTConstants.PULL, pull -> doPull = pull);
        NBTUtils.setBooleanIfPresent(dataMap, NBTConstants.SILK_TOUCH, this::setSilkTouch);
        NBTUtils.setBooleanIfPresent(dataMap, NBTConstants.INVERSE, inverse -> this.inverse = inverse);
        inverseReplaceTarget = NBTUtils.readRegistryEntry(dataMap, NBTConstants.REPLACE_STACK, ForgeRegistries.ITEMS, Items.AIR);
        NBTUtils.setBooleanIfPresent(dataMap, NBTConstants.INVERSE_REQUIRES_REPLACE, requiresReplace -> inverseRequiresReplacement = requiresReplace);
        filterManager.readFromNBT(dataMap);
        //Note: We read the overflow information if it is present in sustained data in order to grab the information from the digital miner item
        // when it is placed or when the BE is loaded from NBT, but the corresponding writing of the data is done in the saveAdditional method
        // as opposed to the writeSustainedData method to ensure that configuration cards do not copy overflow data from one miner to another
        NBTUtils.setListIfPresent(dataMap, NBTConstants.OVERFLOW, Tag.TAG_COMPOUND, overflowTag -> {
            //Clear any existing overflow and read what is the actual overflow from NBT
            overflow.clear();
            for (int i = 0, size = overflowTag.size(); i < size; i++) {
                CompoundTag overflowComponent = overflowTag.getCompound(i);
                int count = overflowComponent.getInt(NBTConstants.COUNT);
                if (count > 0) {
                    //The count should always be greater than zero, but validate it just in case before trying to read the item
                    CompoundTag type = overflowComponent.getCompound(NBTConstants.TYPE);
                    ItemStack stack = ItemStack.of(type);
                    //Only add the item if the item could be read. If it can't that means the mod adding the item was probably removed
                    if (!stack.isEmpty()) {
                        //Note: We can use a raw stack as we just created a new stack from NBT
                        overflow.put(HashedItem.raw(stack), count);
                    }
                }
            }
            hasOverflow = !overflow.isEmpty();
            //Note: Marking rechecking if any of the overflow can fit probably isn't strictly necessary here as in theory it already tried
            // to insert anything before when it was saving, but it doesn't really hurt and then if the last tick had it get overflow or
            // had the inventory change which caused a save, but the next tick never happened the overflow may actually need to be updated
            recheckOverflow = hasOverflow;
        });
    }

    @Override
    public Map<String, String> getTileDataRemap() {
        Map<String, String> remap = new Object2ObjectOpenHashMap<>();
        remap.put(NBTConstants.RADIUS, NBTConstants.RADIUS);
        remap.put(NBTConstants.MIN, NBTConstants.MIN);
        remap.put(NBTConstants.MAX, NBTConstants.MAX);
        remap.put(NBTConstants.EJECT, NBTConstants.EJECT);
        remap.put(NBTConstants.PULL, NBTConstants.PULL);
        remap.put(NBTConstants.SILK_TOUCH, NBTConstants.SILK_TOUCH);
        remap.put(NBTConstants.INVERSE, NBTConstants.INVERSE);
        remap.put(NBTConstants.REPLACE_STACK, NBTConstants.REPLACE_STACK);
        remap.put(NBTConstants.INVERSE_REQUIRES_REPLACE, NBTConstants.INVERSE_REQUIRES_REPLACE);
        remap.put(NBTConstants.FILTERS, NBTConstants.FILTERS);
        remap.put(NBTConstants.OVERFLOW, NBTConstants.OVERFLOW);
        return remap;
    }

    @Override
    public void recalculateUpgrades(Upgrade upgrade) {
        super.recalculateUpgrades(upgrade);
        if (upgrade == Upgrade.SPEED) {
            delayLength = MekanismUtils.getTicks(this, MekanismConfig.general.minerTicksPerMine.get());
        }
    }

    @NotNull
    @Override
    public List<Component> getInfo(@NotNull Upgrade upgrade) {
        return UpgradeUtils.getMultScaledInfo(this, upgrade);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getOffsetCapabilityIfEnabled(@NotNull Capability<T> capability, Direction side, @NotNull Vec3i offset) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            //Get item handler cap directly from here as we disable it entirely for the main block as we only have it enabled from ports
            return itemHandlerManager.resolve(capability, side);
        }
        //Otherwise, we can just grab the capability from the tile normally
        return getCapability(capability, side);
    }

    @Override
    public boolean isOffsetCapabilityDisabled(@NotNull Capability<?> capability, Direction side, @NotNull Vec3i offset) {
        if (!capability.isRegistered()) {
            //Short circuit if a capability that is not registered is being queried
            return true;
        } else if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return notItemPort(side, offset);
        } else if (EnergyCompatUtils.isEnergyCapability(capability)) {
            return notEnergyPort(side, offset);
        } else if (canEverResolve(capability) && IBoundingBlock.super.isOffsetCapabilityDisabled(capability, side, offset)) {
            //If we are not an item handler or energy capability, and it is a capability that we can support,
            // but it is one that normally should be disabled for offset capabilities, then expose it but only do so
            // via our ports for things like computer integration capabilities, then we treat the capability as
            // disabled if it is not against one of our ports
            return notItemPort(side, offset) && notEnergyPort(side, offset);
        }
        return false;
    }

    private boolean notItemPort(Direction side, Vec3i offset) {
        if (offset.equals(new Vec3i(0, 1, 0))) {
            //If input then disable if wrong face of input
            return side != Direction.UP;
        }
        Direction back = getOppositeDirection();
        if (offset.equals(new Vec3i(back.getStepX(), 1, back.getStepZ()))) {
            //If output then disable if wrong face of output
            return side != back;
        }
        return true;
    }

    private boolean notEnergyPort(Direction side, Vec3i offset) {
        if (offset.equals(Vec3i.ZERO)) {
            //Disable if it is the bottom port but wrong side of it
            return side != Direction.DOWN;
        }
        Direction left = getLeftSide();
        if (offset.equals(new Vec3i(left.getStepX(), 0, left.getStepZ()))) {
            //Disable if left power port but wrong side of the port
            return side != left;
        }
        Direction right = left.getOpposite();
        if (offset.equals(new Vec3i(right.getStepX(), 0, right.getStepZ()))) {
            //Disable if right power port but wrong side of the port
            return side != right;
        }
        return true;
    }

    @Override
    public TileComponentChunkLoader<TileEntityDigitalMiner> getChunkLoader() {
        return chunkLoaderComponent;
    }

    /**
     * @apiNote Should only be called on the server, but probably won't cause major issues if called on the client
     */
    private void updateTargetChunk(@Nullable ChunkPos target) {
        if (!Objects.equals(targetChunk, target)) {
            //Only update the target if it has changed
            targetChunk = target;
            getChunkLoader().refreshChunkTickets();
        }
    }

    @Override
    public Set<ChunkPos> getChunkSet() {
        ChunkPos minerChunk = new ChunkPos(worldPosition);
        if (targetChunk != null) {
            //If we have a target check to make sure it is in the radius (most likely it is)
            if (SectionPos.blockToSectionCoord(worldPosition.getX() - radius) <= targetChunk.x &&
                targetChunk.x <= SectionPos.blockToSectionCoord(worldPosition.getX() + radius) &&
                SectionPos.blockToSectionCoord(worldPosition.getZ() - radius) <= targetChunk.z &&
                targetChunk.z <= SectionPos.blockToSectionCoord(worldPosition.getZ() + radius)) {
                // if it is, return the chunks we should be loading, provide the chunk the miner is in
                // and the chunk that the miner is currently mining
                //TODO: At some point we may want to change the ticket of the chunk the miner is mining to be
                // at a lower level and not cause tiles in it to actually tick
                if (minerChunk.equals(targetChunk)) {
                    return Set.of(minerChunk);
                }
                return Set.of(minerChunk, targetChunk);
            }
        }
        //Otherwise, just return the miner's chunk
        return Collections.singleton(minerChunk);
    }

    @Override
    public SortableFilterManager<MinerFilter<?>> getFilterManager() {
        return filterManager;
    }

    public MinerEnergyContainer getEnergyContainer() {
        return energyContainer;
    }

    @ComputerMethod(methodDescription = "Get the count of block found but not yet mined")
    public int getToMine() {
        return !isRemote() && searcher.state == State.SEARCHING ? searcher.found : cachedToMine;
    }

    @ComputerMethod(methodDescription = "Whether the miner is currently running")
    public boolean isRunning() {
        return running;
    }

    @ComputerMethod(nameOverride = "getAutoEject", methodDescription = "Whether Auto Eject is turned on")
    public boolean getDoEject() {
        return doEject;
    }

    @ComputerMethod(nameOverride = "getAutoPull", methodDescription = "Whether Auto Pull is turned on")
    public boolean getDoPull() {
        return doPull;
    }

    public boolean hasOverflow() {
        return hasOverflow;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        addConfigContainerTrackers(container);
        container.track(SyncableBoolean.create(this::getDoEject, value -> doEject = value));
        container.track(SyncableBoolean.create(this::getDoPull, value -> doPull = value));
        container.track(SyncableBoolean.create(this::isRunning, value -> running = value));
        container.track(SyncableBoolean.create(this::getSilkTouch, this::setSilkTouch));
        container.track(SyncableEnum.create(State::byIndexStatic, State.IDLE, () -> searcher.state, value -> searcher.state = value));
        container.track(SyncableInt.create(this::getToMine, value -> cachedToMine = value));
        container.track(SyncableItemStack.create(() -> missingStack, value -> missingStack = value));
        container.track(SyncableBoolean.create(this::hasOverflow, value -> hasOverflow = value));
    }

    public void addConfigContainerTrackers(MekanismContainer container) {
        container.track(SyncableInt.create(this::getRadius, this::setRadius));
        container.track(SyncableInt.create(this::getMinY, this::setMinY));
        container.track(SyncableInt.create(this::getMaxY, this::setMaxY));
        container.track(SyncableBoolean.create(this::getInverse, value -> inverse = value));
        container.track(SyncableBoolean.create(this::getInverseRequiresReplacement, value -> inverseRequiresReplacement = value));
        container.track(SyncableRegistryEntry.create(ForgeRegistries.ITEMS, this::getInverseReplaceTarget, value -> inverseReplaceTarget = value));
        filterManager.addContainerTrackers(container);
    }

    @NotNull
    @Override
    public CompoundTag getReducedUpdateTag() {
        CompoundTag updateTag = super.getReducedUpdateTag();
        updateTag.putInt(NBTConstants.RADIUS, getRadius());
        updateTag.putInt(NBTConstants.MIN, getMinY());
        updateTag.putInt(NBTConstants.MAX, getMaxY());
        return updateTag;
    }

    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag) {
        super.handleUpdateTag(tag);
        NBTUtils.setIntIfPresent(tag, NBTConstants.RADIUS, this::setRadius);//the client is allowed to use whatever server sends
        NBTUtils.setIntIfPresent(tag, NBTConstants.MIN, this::setMinY);
        NBTUtils.setIntIfPresent(tag, NBTConstants.MAX, this::setMaxY);
    }

    private List<ItemStack> getDrops(BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return Collections.emptyList();
        }
        ItemStack stack = ItemAtomicDisassembler.fullyChargedStack();
        if (getSilkTouch()) {
            stack.enchant(Enchantments.SILK_TOUCH, 1);
        }
        ServerLevel level = (ServerLevel) getWorldNN();
        return withFakePlayer(fakePlayer -> Block.getDrops(state, level, pos, WorldUtils.getTileEntity(level, pos), fakePlayer, stack));
    }

    //Methods relating to IComputerTile
    @ComputerMethod(methodDescription = ComputerConstants.DESCRIPTION_GET_ENERGY_USAGE)
    FloatingLong getEnergyUsage() {
        return getActive() ? energyContainer.getEnergyPerTick() : FloatingLong.ZERO;
    }

    @ComputerMethod(methodDescription = "Get the size of the Miner's internal inventory")
    int getSlotCount() {
        return mainSlots.size();
    }

    @ComputerMethod(methodDescription = "Get the contents of the internal inventory slot. 0 based.")
    ItemStack getItemInSlot(int slot) throws ComputerException {
        int slots = getSlotCount();
        if (slot < 0 || slot >= slots) {
            throw new ComputerException("Slot: '%d' is out of bounds, as this digital miner only has '%d' slots (zero indexed).", slot, slots);
        }
        return mainSlots.get(slot).getStack();
    }

    @ComputerMethod(methodDescription = "Get the state of the Miner's search")
    State getState() {
        return searcher.state;
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Update the Auto Eject setting")
    void setAutoEject(boolean eject) throws ComputerException {
        validateSecurityIsPublic();
        if (doEject != eject) {
            toggleAutoEject();
        }
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Update the Auto Pull setting")
    void setAutoPull(boolean pull) throws ComputerException {
        validateSecurityIsPublic();
        if (doPull != pull) {
            toggleAutoPull();
        }
    }

    @ComputerMethod(nameOverride = "setSilkTouch", requiresPublicSecurity = true, methodDescription = "Update the Silk Touch setting")
    void computerSetSilkTouch(boolean silk) throws ComputerException {
        validateSecurityIsPublic();
        setSilkTouch(silk);
    }

    @ComputerMethod(nameOverride = "start", requiresPublicSecurity = true, methodDescription = "Attempt to start the mining process")
    void computerStart() throws ComputerException {
        validateSecurityIsPublic();
        start();
    }

    @ComputerMethod(nameOverride = "stop", requiresPublicSecurity = true, methodDescription = "Attempt to stop the mining process")
    void computerStop() throws ComputerException {
        validateSecurityIsPublic();
        stop();
    }

    @ComputerMethod(nameOverride = "reset", requiresPublicSecurity = true, methodDescription = "Stop the mining process and reset the Miner to be able to change settings")
    void computerReset() throws ComputerException {
        validateSecurityIsPublic();
        reset();
    }

    @ComputerMethod(methodDescription = "Get the maximum allowable Radius value, determined from the mod's config")
    int getMaxRadius() {
        return MekanismConfig.general.minerMaxRadius.get();
    }

    private void validateCanChangeConfiguration() throws ComputerException {
        validateSecurityIsPublic();
        //Validate the miner is stopped and reset first
        if (searcher.state != State.IDLE) {
            throw new ComputerException("Miner must be stopped and reset before its targeting configuration is changed.");
        }
    }

    @ComputerMethod(nameOverride = "setRadius", requiresPublicSecurity = true, methodDescription = "Update the mining radius (blocks). Requires miner to be stopped/reset first")
    void computerSetRadius(int radius) throws ComputerException {
        validateCanChangeConfiguration();
        if (radius < 0 || radius > MekanismConfig.general.minerMaxRadius.get()) {
            //Validate dimensions even though we can clamp
            throw new ComputerException("Radius '%d' is out of range must be between 0 and %d. (Inclusive)", radius, MekanismConfig.general.minerMaxRadius.get());
        }
        setRadiusFromPacket(radius);
    }

    @ComputerMethod(nameOverride = "setMinY", requiresPublicSecurity = true, methodDescription = "Update the minimum Y level for mining. Requires miner to be stopped/reset first")
    void computerSetMinY(int minY) throws ComputerException {
        validateCanChangeConfiguration();
        if (level != null) {
            int min = level.getMinBuildHeight();
            if (minY < min || minY > getMaxY()) {
                //Validate dimensions even though we can clamp
                throw new ComputerException("Min Y '%d' is out of range must be between %d and %d. (Inclusive)", minY, min, getMaxY());
            }
            setMinYFromPacket(minY);
        }
    }

    @ComputerMethod(nameOverride = "setMaxY", requiresPublicSecurity = true, methodDescription = "Update the maximum Y level for mining. Requires miner to be stopped/reset first")
    void computerSetMaxY(int maxY) throws ComputerException {
        validateCanChangeConfiguration();
        if (level != null) {
            int max = level.getMaxBuildHeight() - 1;
            if (maxY < getMinY() || maxY > max) {
                //Validate dimensions even though we can clamp
                throw new ComputerException("Max Y '%d' is out of range must be between %d and %d. (Inclusive)", maxY, getMinY(), max);
            }
            setMaxYFromPacket(maxY);
        }
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Update the Inverse Mode setting. Requires miner to be stopped/reset first")
    void setInverseMode(boolean enabled) throws ComputerException {
        validateCanChangeConfiguration();
        if (inverse != enabled) {
            toggleInverse();
        }
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Update the Inverse Mode Requires Replacement setting. Requires miner to be stopped/reset first")
    void setInverseModeRequiresReplacement(boolean requiresReplacement) throws ComputerException {
        validateCanChangeConfiguration();
        if (inverseRequiresReplacement != requiresReplacement) {
            toggleInverseRequiresReplacement();
        }
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Update the target for Replacement in Inverse Mode. Requires miner to be stopped/reset first")
    void setInverseModeReplaceTarget(Item target) throws ComputerException {
        validateCanChangeConfiguration();
        setInverseReplaceTarget(target);
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Remove the target for Replacement in Inverse Mode. Requires miner to be stopped/reset first")
    void clearInverseModeReplaceTarget() throws ComputerException {
        setInverseModeReplaceTarget(Items.AIR);
    }

    @ComputerMethod(methodDescription = "Get the current list of Miner Filters")
    List<MinerFilter<?>> getFilters() {
        return filterManager.getFilters();
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Add a new filter to the miner. Requires miner to be stopped/reset first")
    boolean addFilter(MinerFilter<?> filter) throws ComputerException {
        validateCanChangeConfiguration();
        return filterManager.addFilter(filter);
    }

    @ComputerMethod(requiresPublicSecurity = true, methodDescription = "Removes the exactly matching filter from the miner. Requires miner to be stopped/reset first")
    boolean removeFilter(MinerFilter<?> filter) throws ComputerException {
        validateCanChangeConfiguration();
        return filterManager.removeFilter(filter);
    }
    //End methods IComputerTile

    private static class ItemCount {

        private final ItemStack stack;
        private int count;

        public ItemCount(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }
}
