package mekanism.common.content.network.transmitter;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import mekanism.api.Chunk3D;
import mekanism.api.Coord4D;
import mekanism.api.NBTConstants;
import mekanism.api.text.EnumColor;
import mekanism.common.MekanismLang;
import mekanism.common.lib.transmitter.CompatibleTransmitterValidator;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.lib.transmitter.DynamicNetwork;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import mekanism.common.lib.transmitter.acceptor.AbstractAcceptorCache;
import mekanism.common.lib.transmitter.acceptor.AcceptorCache;
import mekanism.common.tile.interfaces.ITileWrapper;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.NBTUtils;
import mekanism.common.util.WorldUtils;
import mekanism.common.util.text.BooleanStateDisplay.OnOff;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class Transmitter<ACCEPTOR, NETWORK extends DynamicNetwork<ACCEPTOR, NETWORK, TRANSMITTER>,
      TRANSMITTER extends Transmitter<ACCEPTOR, NETWORK, TRANSMITTER>> implements ITileWrapper {

    public static boolean connectionMapContainsSide(byte connections, Direction side) {
        return connectionMapContainsSide(connections, side.ordinal());
    }

    private static boolean connectionMapContainsSide(byte connections, int sideOrdinal) {
        byte tester = (byte) (1 << sideOrdinal);
        return (connections & tester) > 0;
    }

    private static byte setConnectionBit(byte connections, boolean toSet, Direction side) {
        return (byte) ((connections & ~(byte) (1 << side.ordinal())) | (byte) ((toSet ? 1 : 0) << side.ordinal()));
    }

    private static ConnectionType getConnectionType(Direction side, byte allConnections, byte transmitterConnections, ConnectionType[] types) {
        int sideOrdinal = side.ordinal();
        if (!connectionMapContainsSide(allConnections, sideOrdinal)) {
            return ConnectionType.NONE;
        } else if (connectionMapContainsSide(transmitterConnections, sideOrdinal)) {
            return ConnectionType.NORMAL;
        }
        return types[sideOrdinal];
    }

    private ConnectionType[] connectionTypes = {ConnectionType.NORMAL, ConnectionType.NORMAL, ConnectionType.NORMAL, ConnectionType.NORMAL, ConnectionType.NORMAL,
                                                ConnectionType.NORMAL};
    private final AbstractAcceptorCache<ACCEPTOR, ?> acceptorCache;
    public byte currentTransmitterConnections = 0x00;

    private final TileEntityTransmitter transmitterTile;
    private final Set<TransmissionType> supportedTransmissionTypes;
    protected boolean redstoneReactive;
    private boolean redstonePowered;
    private boolean redstoneSet;
    private NETWORK theNetwork = null;
    private boolean orphaned = true;
    private boolean isUpgrading;

    public Transmitter(TileEntityTransmitter transmitterTile, TransmissionType... transmissionTypes) {
        this.transmitterTile = transmitterTile;
        acceptorCache = createAcceptorCache();
        supportedTransmissionTypes = EnumSet.noneOf(TransmissionType.class);
        Collections.addAll(supportedTransmissionTypes, transmissionTypes);
    }

    protected AbstractAcceptorCache<ACCEPTOR, ?> createAcceptorCache() {
        return new AcceptorCache<>(this, getTransmitterTile());
    }

    public AbstractAcceptorCache<ACCEPTOR, ?> getAcceptorCache() {
        return acceptorCache;
    }

    public TileEntityTransmitter getTransmitterTile() {
        return transmitterTile;
    }

    public boolean isUpgrading() {
        return isUpgrading;
    }

    /**
     * @apiNote Don't use this to directly modify the backing array, use the helper set methods.
     */
    public ConnectionType[] getConnectionTypesRaw() {
        return connectionTypes;
    }

    public void setConnectionTypesRaw(@NotNull ConnectionType[] connectionTypes) {
        if (this.connectionTypes.length != connectionTypes.length) {
            throw new IllegalArgumentException("Mismatched connection types length");
        }
        this.connectionTypes = connectionTypes;
    }

    public ConnectionType getConnectionTypeRaw(@NotNull Direction side) {
        return connectionTypes[side.ordinal()];
    }

    public void setConnectionTypeRaw(@NotNull Direction side, @NotNull ConnectionType type) {
        int index = side.ordinal();
        ConnectionType old = connectionTypes[index];
        if (old != type) {
            connectionTypes[index] = type;
            getTransmitterTile().sideChanged(side, old, type);
        }
    }

    @Override
    public BlockPos getTilePos() {
        return transmitterTile.getTilePos();
    }

    @Override
    public Level getTileWorld() {
        return transmitterTile.getTileWorld();
    }

    @Override
    public Coord4D getTileCoord() {
        return transmitterTile.getTileCoord();
    }

    @Override
    public Chunk3D getTileChunk() {
        return transmitterTile.getTileChunk();
    }

    public boolean isRemote() {
        return transmitterTile.isRemote();
    }

    protected TRANSMITTER getTransmitter() {
        return (TRANSMITTER) this;
    }

    /**
     * Gets the network currently in use by this transmitter segment.
     *
     * @return network this transmitter is using
     */
    public NETWORK getTransmitterNetwork() {
        return theNetwork;
    }

    /**
     * Sets this transmitter segment's network to a new value.
     *
     * @param network - network to set to
     */
    public void setTransmitterNetwork(NETWORK network) {
        setTransmitterNetwork(network, true);
    }

    /**
     * Sets this transmitter segment's network to a new value.
     *
     * @param network    - network to set to
     * @param requestNow - Force a request now if not the return value will be if a request is needed
     */
    public boolean setTransmitterNetwork(NETWORK network, boolean requestNow) {
        if (theNetwork == network) {
            return false;
        }
        if (isRemote() && theNetwork != null) {
            theNetwork.removeTransmitter(getTransmitter());
        }
        theNetwork = network;
        orphaned = theNetwork == null;
        if (isRemote()) {
            if (theNetwork != null) {
                theNetwork.addTransmitter(getTransmitter());
            }
        } else if (requestNow) {
            //If we are requesting now request the update
            requestsUpdate();
        } else {
            //Otherwise, return that we need to update it
            return true;
        }
        return false;
    }

    public boolean hasTransmitterNetwork() {
        return !isOrphan() && getTransmitterNetwork() != null;
    }

    public abstract NETWORK createEmptyNetworkWithID(UUID networkID);

    public abstract NETWORK createNetworkByMerging(Collection<NETWORK> toMerge);

    public boolean isValid() {
        return !getTransmitterTile().isRemoved() && getTransmitterTile().isLoaded();
    }

    public CompatibleTransmitterValidator<ACCEPTOR, NETWORK, TRANSMITTER> getNewOrphanValidator() {
        return new CompatibleTransmitterValidator<>();
    }

    public boolean isOrphan() {
        return orphaned;
    }

    public void setOrphan(boolean nowOrphaned) {
        orphaned = nowOrphaned;
    }

    /**
     * Get the transmitter's transmission types
     *
     * @return TransmissionType this transmitter uses
     */
    public Set<TransmissionType> getSupportedTransmissionTypes() {
        return supportedTransmissionTypes;
    }

    public boolean supportsTransmissionType(Transmitter<?, ?, ?> transmitter) {
        return transmitter.getSupportedTransmissionTypes().stream().anyMatch(supportedTransmissionTypes::contains);
    }

    public boolean supportsTransmissionType(TileEntityTransmitter transmitter) {
        return supportsTransmissionType(transmitter.getTransmitter());
    }

    @NotNull
    public LazyOptional<ACCEPTOR> getAcceptor(Direction side) {
        return acceptorCache.getCachedAcceptor(side);
    }

    public boolean handlesRedstone() {
        return true;
    }

    public final boolean isRedstoneActivated() {
        if (handlesRedstone() && redstoneReactive) {
            if (!redstoneSet) {
                setRedstoneState();
            }
            return redstonePowered;
        }
        return false;
    }

    /**
     * @apiNote Only call this from the server side
     */
    public byte getPossibleTransmitterConnections() {
        byte connections = 0x00;
        if (isRedstoneActivated()) {
            return connections;
        }
        for (Direction side : EnumUtils.DIRECTIONS) {
            TileEntityTransmitter tile = WorldUtils.getTileEntity(TileEntityTransmitter.class, getTileWorld(), getTilePos().relative(side));
            if (tile != null && isValidTransmitter(tile, side)) {
                connections |= 1 << side.ordinal();
            }
        }
        return connections;
    }

    /**
     * @apiNote Only call this from the server side
     */
    private boolean getPossibleAcceptorConnection(Direction side) {
        if (isRedstoneActivated()) {
            return false;
        }
        BlockEntity tile = WorldUtils.getTileEntity(getTileWorld(), getTilePos().relative(side));
        if (canConnectMutual(side, tile) && isValidAcceptor(tile, side)) {
            return true;
        }
        acceptorCache.invalidateCachedAcceptor(side);
        return false;
    }

    /**
     * @apiNote Only call this from the server side
     */
    private boolean getPossibleTransmitterConnection(Direction side) {
        if (isRedstoneActivated()) {
            return false;
        }
        TileEntityTransmitter tile = WorldUtils.getTileEntity(TileEntityTransmitter.class, getTileWorld(), getTilePos().relative(side));
        return tile != null && isValidTransmitter(tile, side);
    }

    /**
     * @apiNote Only call this from the server side
     */
    public byte getPossibleAcceptorConnections() {
        byte connections = 0x00;
        if (isRedstoneActivated()) {
            return connections;
        }
        for (Direction side : EnumUtils.DIRECTIONS) {
            BlockPos offset = getTilePos().relative(side);
            BlockEntity tile = WorldUtils.getTileEntity(getTileWorld(), offset);
            if (canConnectMutual(side, tile)) {
                if (!isRemote() && !WorldUtils.isBlockLoaded(getTileWorld(), offset)) {
                    getTransmitterTile().setForceUpdate();
                    continue;
                }
                if (isValidAcceptor(tile, side)) {
                    connections |= 1 << side.ordinal();
                    continue;
                }
            }
            acceptorCache.invalidateCachedAcceptor(side);
        }
        return connections;
    }

    public byte getAllCurrentConnections() {
        return (byte) (currentTransmitterConnections | acceptorCache.currentAcceptorConnections);
    }

    public boolean isValidTransmitter(TileEntityTransmitter transmitter, Direction side) {
        return isValidTransmitterBasic(transmitter, side);
    }

    public boolean isValidTransmitterBasic(TileEntityTransmitter transmitter, Direction side) {
        return supportsTransmissionType(transmitter) && canConnectMutual(side, transmitter);
    }

    public boolean canConnectToAcceptor(Direction side) {
        ConnectionType type = getConnectionTypeRaw(side);
        return type == ConnectionType.NORMAL || type == ConnectionType.PUSH;
    }

    /**
     * @apiNote Only call this from the server side
     */
    public boolean isValidAcceptor(BlockEntity tile, Direction side) {
        //TODO: Rename this method better to make it more apparent that it caches and also listens to the acceptor
        //If it isn't a transmitter or the transmission type is different than the one the transmitter has
        return !(tile instanceof TileEntityTransmitter transmitter) || !supportsTransmissionType(transmitter);
    }

    public boolean canConnectMutual(Direction side, @Nullable BlockEntity cachedTile) {
        if (!canConnect(side)) {
            return false;
        }
        if (cachedTile == null) {
            //If we don't already have the tile that is on the side calculated, do so
            cachedTile = WorldUtils.getTileEntity(getTileWorld(), getTilePos().relative(side));
        }
        return !(cachedTile instanceof TileEntityTransmitter transmitter) || transmitter.getTransmitter().canConnect(side.getOpposite());
    }

    public boolean canConnectMutual(Direction side, @Nullable TRANSMITTER cachedTransmitter) {
        if (!canConnect(side)) {
            return false;
        }
        //Return true if the other transmitter is null (some other tile is there) or the transmitter can connect both directions
        return cachedTransmitter == null || cachedTransmitter.canConnect(side.getOpposite());
    }

    public boolean canConnect(Direction side) {
        if (getConnectionTypeRaw(side) == ConnectionType.NONE) {
            return false;
        }
        if (handlesRedstone() && redstoneReactive) {
            if (!redstoneSet) {
                setRedstoneState();
            }
            return !redstonePowered;
        }
        return true;
    }

    /**
     * Only call on the server
     */
    public void requestsUpdate() {
        getTransmitterTile().sendUpdatePacket();
    }

    @NotNull
    public CompoundTag getReducedUpdateTag(CompoundTag updateTag) {
        updateTag.putByte(NBTConstants.CURRENT_CONNECTIONS, currentTransmitterConnections);
        updateTag.putByte(NBTConstants.CURRENT_ACCEPTORS, acceptorCache.currentAcceptorConnections);
        for (Direction direction : EnumUtils.DIRECTIONS) {
            NBTUtils.writeEnum(updateTag, NBTConstants.SIDE + direction.ordinal(), getConnectionTypeRaw(direction));
        }
        //Transmitter
        if (hasTransmitterNetwork()) {
            updateTag.putUUID(NBTConstants.NETWORK, getTransmitterNetwork().getUUID());
        }
        return updateTag;
    }

    public void handleUpdateTag(@NotNull CompoundTag tag) {
        NBTUtils.setByteIfPresent(tag, NBTConstants.CURRENT_CONNECTIONS, connections -> currentTransmitterConnections = connections);
        NBTUtils.setByteIfPresent(tag, NBTConstants.CURRENT_ACCEPTORS, acceptors -> acceptorCache.currentAcceptorConnections = acceptors);
        for (Direction direction : EnumUtils.DIRECTIONS) {
            NBTUtils.setEnumIfPresent(tag, NBTConstants.SIDE + direction.ordinal(), ConnectionType::byIndexStatic, type -> setConnectionTypeRaw(direction, type));
        }
        //Transmitter
        NBTUtils.setUUIDIfPresentElse(tag, NBTConstants.NETWORK, networkID -> {
            if (hasTransmitterNetwork() && getTransmitterNetwork().getUUID().equals(networkID)) {
                //Nothing needs to be done
                return;
            }
            DynamicNetwork<?, ?, ?> clientNetwork = TransmitterNetworkRegistry.getInstance().getClientNetwork(networkID);
            if (clientNetwork == null) {
                NETWORK network = createEmptyNetworkWithID(networkID);
                network.register();
                setTransmitterNetwork(network);
                handleContentsUpdateTag(network, tag);
            } else {
                //TODO: Validate network type?
                updateClientNetwork((NETWORK) clientNetwork);
            }
        }, () -> setTransmitterNetwork(null));
    }

    protected void updateClientNetwork(@NotNull NETWORK network) {
        network.register();
        setTransmitterNetwork(network);
    }

    protected void handleContentsUpdateTag(@NotNull NETWORK network, @NotNull CompoundTag tag) {
    }

    public void read(@NotNull CompoundTag nbtTags) {
        redstoneReactive = nbtTags.getBoolean(NBTConstants.REDSTONE);
        for (Direction direction : EnumUtils.DIRECTIONS) {
            NBTUtils.setEnumIfPresent(nbtTags, NBTConstants.CONNECTION + direction.ordinal(), ConnectionType::byIndexStatic, type -> setConnectionTypeRaw(direction, type));
        }
    }

    @NotNull
    public CompoundTag write(@NotNull CompoundTag nbtTags) {
        nbtTags.putBoolean(NBTConstants.REDSTONE, redstoneReactive);
        for (Direction direction : EnumUtils.DIRECTIONS) {
            NBTUtils.writeEnum(nbtTags, NBTConstants.CONNECTION + direction.ordinal(), getConnectionTypeRaw(direction));
        }
        return nbtTags;
    }

    private void recheckRedstone() {
        if (handlesRedstone()) {
            boolean previouslyPowered = redstonePowered;
            setRedstoneState();
            //If the redstone mode changed properly update the connection to other transmitters/networks
            if (previouslyPowered != redstonePowered) {
                //Has to be markDirtyTransmitters instead of notify tile change,
                // or it will not properly tell the neighboring connections that
                // it is no longer valid as well as potentially let neighbors
                // check if there are any cap changes
                markDirtyTransmitters();
                getTransmitterTile().redstoneChanged(redstonePowered);
            }
        }
    }

    /**
     * Assumes that {@link #handlesRedstone()} is {@code true}.
     */
    private void setRedstoneState() {
        redstonePowered = redstoneReactive && transmitterTile.hasLevel() && WorldUtils.isGettingPowered(getTileWorld(), getTilePos());
        redstoneSet = true;
    }

    public void refreshConnections() {
        if (!isRemote()) {
            recheckRedstone();
            byte possibleTransmitters = getPossibleTransmitterConnections();
            byte possibleAcceptors = getPossibleAcceptorConnections();
            byte newlyEnabledTransmitters = 0;
            boolean sendDesc = false;
            if ((possibleTransmitters | possibleAcceptors) != getAllCurrentConnections()) {
                sendDesc = true;
                if (possibleTransmitters != currentTransmitterConnections) {
                    //If they don't match get the difference
                    newlyEnabledTransmitters = (byte) (possibleTransmitters ^ currentTransmitterConnections);
                    //Now remove all bits that already where enabled, so we only have the
                    // ones that are newly enabled. There is no need to recheck for a
                    // network merge on two transmitters if one is no longer accessible
                    newlyEnabledTransmitters &= ~currentTransmitterConnections;
                }
            }

            currentTransmitterConnections = possibleTransmitters;
            acceptorCache.currentAcceptorConnections = possibleAcceptors;
            if (newlyEnabledTransmitters != 0) {
                //If any sides are now valid transmitters that were not before recheck the connection
                recheckConnections(newlyEnabledTransmitters);
            }
            if (sendDesc) {
                getTransmitterTile().sendUpdatePacket();
            }
        }
    }

    public void refreshConnections(Direction side) {
        if (!isRemote()) {
            boolean possibleTransmitter = getPossibleTransmitterConnection(side);
            boolean possibleAcceptor = getPossibleAcceptorConnection(side);
            boolean transmitterChanged = false;
            boolean sendDesc = false;
            if ((possibleTransmitter || possibleAcceptor) != connectionMapContainsSide(getAllCurrentConnections(), side)) {
                sendDesc = true;
                if (possibleTransmitter != connectionMapContainsSide(currentTransmitterConnections, side)) {
                    //If it doesn't match check if it is now enabled, as we don't care about it changing to disabled
                    transmitterChanged = possibleTransmitter;
                }
            }

            currentTransmitterConnections = setConnectionBit(currentTransmitterConnections, possibleTransmitter, side);
            acceptorCache.currentAcceptorConnections = setConnectionBit(acceptorCache.currentAcceptorConnections, possibleAcceptor, side);
            if (transmitterChanged) {
                //If this side is now a valid transmitter, and it wasn't before recheck the connection
                recheckConnection(side);
            }
            if (sendDesc) {
                getTransmitterTile().sendUpdatePacket();
            }
        }
    }

    /**
     * @param newlyEnabledTransmitters The transmitters that are now enabled and were not before.
     *
     * @apiNote Only call this from the server side
     */
    protected void recheckConnections(byte newlyEnabledTransmitters) {
        if (!hasTransmitterNetwork()) {
            //If we don't have a transmitter network then recheck connection status both ways if the other tile is also a transmitter
            //This fixes pipes not reconnecting cross chunk
            for (Direction side : EnumUtils.DIRECTIONS) {
                if (connectionMapContainsSide(newlyEnabledTransmitters, side)) {
                    TileEntityTransmitter tile = WorldUtils.getTileEntity(TileEntityTransmitter.class, getTileWorld(), getTilePos().relative(side));
                    if (tile != null) {
                        tile.getTransmitter().refreshConnections(side.getOpposite());
                    }
                }
            }
        }
    }

    /**
     * @param side The side that a transmitter is now enabled on after having been disabled.
     *
     * @apiNote Only call this from the server side
     */
    protected void recheckConnection(Direction side) {
    }

    public void onModeChange(Direction side) {
        markDirtyAcceptor(side);
        if (getPossibleTransmitterConnections() != currentTransmitterConnections) {
            markDirtyTransmitters();
        }
        getTransmitterTile().setChanged();
    }

    public void onNeighborTileChange(Direction side) {
        refreshConnections(side);
    }

    public void onNeighborBlockChange(Direction side) {
        if (handlesRedstone() && redstoneReactive) {
            //If our tile can handle redstone, and we are redstone reactive we need to recheck all connections
            // as the power might have changed, and we may have to update our own visuals
            refreshConnections();
        } else {
            //Otherwise, we can just get away with checking the single side
            refreshConnections(side);
        }
    }

    protected void markDirtyTransmitters() {
        notifyTileChange();
        if (hasTransmitterNetwork()) {
            //TODO - 1.18: Can this be done in a way that doesn't require reforming the network if it is still valid and the same
            TransmitterNetworkRegistry.invalidateTransmitter(getTransmitter());
        }
    }

    public void markDirtyAcceptor(Direction side) {
        if (hasTransmitterNetwork()) {
            getTransmitterNetwork().acceptorChanged(getTransmitter(), side);
        }
    }

    public void remove() {
        //Clear our cached listeners
        acceptorCache.clear();
    }

    public ConnectionType getConnectionType(Direction side) {
        return getConnectionType(side, getAllCurrentConnections(), currentTransmitterConnections, connectionTypes);
    }

    public Set<Direction> getConnections(ConnectionType type) {
        Set<Direction> sides = null;
        for (Direction side : EnumUtils.DIRECTIONS) {
            if (getConnectionType(side) == type) {
                if (sides == null) {
                    //Lazy init the set so that if there are none we can just use an empty set
                    // instead of having to initialize an enum set
                    sides = EnumSet.noneOf(Direction.class);
                }
                sides.add(side);
            }
        }
        return sides == null ? Collections.emptySet() : sides;
    }

    public InteractionResult onConfigure(Player player, Direction side) {
        return InteractionResult.PASS;
    }

    public InteractionResult onRightClick(Player player, Direction side) {
        if (handlesRedstone()) {
            redstoneReactive = !redstoneReactive;
            refreshConnections();
            notifyTileChange();
            player.sendSystemMessage(MekanismUtils.logFormat(MekanismLang.REDSTONE_SENSITIVITY.translate(EnumColor.INDIGO, OnOff.of(redstoneReactive))));
        }
        return InteractionResult.SUCCESS;
    }

    public void notifyTileChange() {
        WorldUtils.notifyLoadedNeighborsOfTileChange(getTileWorld(), getTilePos());
    }

    public abstract void takeShare();

    public void validateAndTakeShare() {
        takeShare();
    }

    public void startUpgrading() {
        isUpgrading = true;
        takeShare();
        setTransmitterNetwork(null);
    }
}