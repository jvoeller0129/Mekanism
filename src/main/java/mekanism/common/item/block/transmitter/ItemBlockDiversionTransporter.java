package mekanism.common.item.block.transmitter;

import java.util.List;
import javax.annotation.Nonnull;
import mekanism.api.text.EnumColor;
import mekanism.client.MekKeyHandler;
import mekanism.client.MekanismKeyHandler;
import mekanism.common.MekanismLang;
import mekanism.common.block.transmitter.BlockDiversionTransporter;
import mekanism.common.item.block.ItemBlockMultipartAble;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class ItemBlockDiversionTransporter extends ItemBlockMultipartAble<BlockDiversionTransporter> {

    public ItemBlockDiversionTransporter(BlockDiversionTransporter block) {
        super(block);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void addInformation(@Nonnull ItemStack stack, World world, @Nonnull List<ITextComponent> tooltip, @Nonnull ITooltipFlag flag) {
        if (!MekKeyHandler.getIsKeyPressed(MekanismKeyHandler.sneakKey)) {
            tooltip.add(MekanismLang.HOLD_FOR_DETAILS.translateColored(EnumColor.GRAY, EnumColor.INDIGO, MekanismKeyHandler.sneakKey.getLocalizedName()));
        } else {
            tooltip.add(MekanismLang.CAPABLE_OF_TRANSFERRING.translateColored(EnumColor.DARK_GRAY));
            tooltip.add(MekanismLang.ITEMS.translateColored(EnumColor.PURPLE, MekanismLang.UNIVERSAL));
            tooltip.add(MekanismLang.BLOCKS.translateColored(EnumColor.PURPLE, MekanismLang.UNIVERSAL));
            tooltip.add(MekanismLang.DESCRIPTION_DIVERSION.translateColored(EnumColor.DARK_RED));
        }
    }
}