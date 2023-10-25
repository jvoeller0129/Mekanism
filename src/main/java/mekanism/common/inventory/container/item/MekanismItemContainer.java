package mekanism.common.inventory.container.item;

import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class MekanismItemContainer extends MekanismContainer {

    protected final InteractionHand hand;
    protected final ItemStack stack;

    protected MekanismItemContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, InteractionHand hand, ItemStack stack) {
        super(type, id, inv);
        this.hand = hand;
        this.stack = stack;
        if (!stack.isEmpty()) {
            //It shouldn't be empty but validate it just in case
            addContainerTrackers();
        }
        addSlotsAndOpen();
    }

    protected void addContainerTrackers() {
        if (stack.getItem() instanceof IItemContainerTracker containerTracker) {
            containerTracker.addContainerTrackers(this, stack);
        }
    }

    @Nullable
    @Override
    public ICapabilityProvider getSecurityObject() {
        return stack;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return !this.stack.isEmpty() && player.getItemInHand(this.hand).is(this.stack.getItem());
    }

    public interface IItemContainerTracker {

        void addContainerTrackers(MekanismContainer container, ItemStack stack);
    }
}