package mekanism.common.item;

import javax.annotation.Nonnull;
import mekanism.api.text.EnumColor;
import mekanism.common.Mekanism;
import mekanism.common.MekanismLang;
import mekanism.common.inventory.container.ContainerProvider;
import mekanism.common.inventory.container.item.PortableQIODashboardContainer;
import mekanism.common.item.interfaces.IGuiItem;
import mekanism.common.lib.frequency.IFrequencyItem;
import mekanism.common.network.PacketSecurityUpdate;
import mekanism.common.util.SecurityUtils;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Rarity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

public class ItemPortableQIODashboard extends Item implements IFrequencyItem, IGuiItem {

    public ItemPortableQIODashboard(Properties properties) {
        super(properties.maxStackSize(1).rarity(Rarity.RARE));
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, PlayerEntity player, @Nonnull Hand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            if (getOwnerUUID(stack) == null) {
                setOwnerUUID(stack, player.getUniqueID());
                Mekanism.packetHandler.sendToAll(new PacketSecurityUpdate(player.getUniqueID(), null));
                player.sendMessage(MekanismLang.LOG_FORMAT.translateColored(EnumColor.DARK_BLUE, MekanismLang.MEKANISM, MekanismLang.NOW_OWN.translateColored(EnumColor.GRAY)));
            } else if (SecurityUtils.canAccess(player, stack)) {
                NetworkHooks.openGui((ServerPlayerEntity) player, getContainerProvider(stack, hand), buf -> {
                    buf.writeEnumValue(hand);
                    buf.writeItemStack(stack);
                });
            } else {
                SecurityUtils.displayNoAccess(player);
            }
        }
        return new ActionResult<>(ActionResultType.SUCCESS, stack);
    }

    @Override
    public INamedContainerProvider getContainerProvider(ItemStack stack, Hand hand) {
        return new ContainerProvider(stack.getDisplayName(), (i, inv, p) -> new PortableQIODashboardContainer(i, inv, hand, stack));
    }
}
