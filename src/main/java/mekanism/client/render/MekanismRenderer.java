package mekanism.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import mekanism.api.SupportsColorMap;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.SpecialColors;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.model.baked.DigitalMinerBakedModel;
import mekanism.client.render.RenderResizableCuboid.FaceDisplay;
import mekanism.client.render.data.FluidRenderData;
import mekanism.client.render.data.ValveRenderData;
import mekanism.client.render.lib.ColorAtlas;
import mekanism.client.render.lib.ColorAtlas.ColorRegistryObject;
import mekanism.client.render.tileentity.RenderDigitalMiner;
import mekanism.client.render.tileentity.RenderDimensionalStabilizer;
import mekanism.client.render.tileentity.RenderFluidTank;
import mekanism.client.render.tileentity.RenderNutritionalLiquifier;
import mekanism.client.render.tileentity.RenderPigmentMixer;
import mekanism.client.render.tileentity.RenderSeismicVibrator;
import mekanism.client.render.tileentity.RenderTeleporter;
import mekanism.client.render.transmitter.RenderLogisticalTransporter;
import mekanism.client.render.transmitter.RenderMechanicalPipe;
import mekanism.client.render.transmitter.RenderTransmitterBase;
import mekanism.common.Mekanism;
import mekanism.common.lib.Color;
import mekanism.common.lib.multiblock.IValveHandler.ValveData;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.util.EnumUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Mod.EventBusSubscriber(modid = Mekanism.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MekanismRenderer {

    //TODO: Replace various usages of LightTexture.FULL_BRIGHT with the getter for calculating glow light, at least if we end up making it only
    // effect block light for the glow rather than having it actually become full light
    public static TextureAtlasSprite energyIcon;
    public static TextureAtlasSprite heatIcon;
    public static TextureAtlasSprite whiteIcon;
    public static TextureAtlasSprite teleporterPortal;
    public static TextureAtlasSprite redstonePulse;
    public static final Map<TransmissionType, TextureAtlasSprite> overlays = new EnumMap<>(TransmissionType.class);

    /**
     * Get a fluid texture when a stack does not exist.
     *
     * @param fluid the fluid to get
     * @param type  Still or Flowing
     *
     * @return the sprite, or missing sprite if not found
     */
    public static TextureAtlasSprite getBaseFluidTexture(@NotNull Fluid fluid, @NotNull FluidTextureType type) {
        IClientFluidTypeExtensions properties = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation spriteLocation;
        if (type == FluidTextureType.STILL) {
            spriteLocation = properties.getStillTexture();
        } else {
            spriteLocation = properties.getFlowingTexture();
        }
        return getSprite(spriteLocation);
    }

    public static TextureAtlasSprite getFluidTexture(@NotNull FluidStack fluidStack, @NotNull FluidTextureType type) {
        IClientFluidTypeExtensions properties = IClientFluidTypeExtensions.of(fluidStack.getFluid());
        ResourceLocation spriteLocation;
        if (type == FluidTextureType.STILL) {
            spriteLocation = properties.getStillTexture(fluidStack);
        } else {
            spriteLocation = properties.getFlowingTexture(fluidStack);
        }
        return getSprite(spriteLocation);
    }

    public static TextureAtlasSprite getChemicalTexture(@NotNull Chemical<?> chemical) {
        return getSprite(chemical.getIcon());
    }

    public static TextureAtlasSprite getSprite(ResourceLocation spriteLocation) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(spriteLocation);
    }

    public static void renderObject(@Nullable Model3D object, @NotNull PoseStack matrix, VertexConsumer buffer, int argb, int light, int overlay,
          FaceDisplay faceDisplay, Camera camera, BlockPos renderPos) {
        if (object != null) {
            renderObject(object, matrix, buffer, argb, light, overlay, faceDisplay, camera, Vec3.atLowerCornerOf(renderPos));
        }
    }

    public static void renderObject(@Nullable Model3D object, @NotNull PoseStack matrix, VertexConsumer buffer, int argb, int light, int overlay,
          FaceDisplay faceDisplay, Camera camera) {
        renderObject(object, matrix, buffer, argb, light, overlay, faceDisplay, camera, (Vec3) null);
    }

    public static void renderObject(@Nullable Model3D object, @NotNull PoseStack matrix, VertexConsumer buffer, int argb, int light, int overlay,
          FaceDisplay faceDisplay, Camera camera, @Nullable Vec3 renderPos) {
        if (object != null) {
            RenderResizableCuboid.renderCube(object, matrix, buffer, argb, light, overlay, faceDisplay, camera, renderPos);
        }
    }

    public static void renderObject(@Nullable Model3D object, @NotNull PoseStack matrix, VertexConsumer buffer, int[] colors, int light, int overlay,
          FaceDisplay faceDisplay, Camera camera) {
        renderObject(object, matrix, buffer, colors, light, overlay, faceDisplay, camera, null);
    }

    public static void renderObject(@Nullable Model3D object, @NotNull PoseStack matrix, VertexConsumer buffer, int[] colors, int light, int overlay,
          FaceDisplay faceDisplay, Camera camera, @Nullable Vec3 renderPos) {
        if (object != null) {
            RenderResizableCuboid.renderCube(object, matrix, buffer, colors, light, overlay, faceDisplay, camera, renderPos);
        }
    }

    public static void renderValves(PoseStack matrix, VertexConsumer buffer, Set<ValveData> valves, FluidRenderData data, float fluidHeight, BlockPos pos, int glow,
          int overlay, FaceDisplay faceDisplay, Camera camera) {
        for (ValveData valveData : valves) {
            ValveRenderData valveRenderData = ValveRenderData.get(data, valveData);
            Model3D valveModel = ModelRenderer.getValveModel(valveRenderData, fluidHeight);
            if (valveModel != null) {
                matrix.pushPose();
                matrix.translate(valveData.location.getX() - pos.getX(), valveData.location.getY() - pos.getY(), valveData.location.getZ() - pos.getZ());
                renderObject(valveModel, matrix, buffer, valveRenderData.getColorARGB(), glow, overlay, faceDisplay, camera, valveData.location);
                matrix.popPose();
            }
        }
    }

    //Color
    public static void resetColor(GuiGraphics guiGraphics) {
        guiGraphics.setColor(1, 1, 1, 1);
    }

    public static float getRed(int color) {
        return FastColor.ARGB32.red(color) / 255.0F;
    }

    public static float getGreen(int color) {
        return FastColor.ARGB32.green(color) / 255.0F;
    }

    public static float getBlue(int color) {
        return FastColor.ARGB32.blue(color) / 255.0F;
    }

    public static float getAlpha(int color) {
        return FastColor.ARGB32.alpha(color) / 255.0F;
    }

    public static void color(GuiGraphics guiGraphics, int color) {
        guiGraphics.setColor(getRed(color), getGreen(color), getBlue(color), getAlpha(color));
    }

    public static void color(GuiGraphics guiGraphics, ColorRegistryObject colorRO) {
        color(guiGraphics, colorRO.get());
    }

    public static void color(GuiGraphics guiGraphics, Color color) {
        guiGraphics.setColor(color.rf(), color.gf(), color.bf(), color.af());
    }

    public static void color(GuiGraphics guiGraphics, @NotNull FluidStack fluid) {
        if (!fluid.isEmpty()) {
            color(guiGraphics, IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor(fluid));
        }
    }

    public static void color(GuiGraphics guiGraphics, @NotNull ChemicalStack<?> chemicalStack) {
        if (!chemicalStack.isEmpty()) {
            color(guiGraphics, chemicalStack.getType());
        }
    }

    public static void color(GuiGraphics guiGraphics, @NotNull Chemical<?> chemical) {
        if (!chemical.isEmptyType()) {
            int color = chemical.getTint();
            guiGraphics.setColor(getRed(color), getGreen(color), getBlue(color), 1);
        }
    }

    public static void color(GuiGraphics guiGraphics, @Nullable SupportsColorMap color) {
        color(guiGraphics, color, 1.0F);
    }

    public static void color(GuiGraphics guiGraphics, @Nullable SupportsColorMap color, float alpha) {
        if (color != null) {
            guiGraphics.setColor(color.getColor(0), color.getColor(1), color.getColor(2), alpha);
        }
    }

    public static int getColorARGB(SupportsColorMap color, float alpha) {
        return getColorARGB(color.getRgbCode()[0], color.getRgbCode()[1], color.getRgbCode()[2], alpha);
    }

    public static int getColorARGB(@NotNull FluidStack fluidStack) {
        return IClientFluidTypeExtensions.of(fluidStack.getFluid()).getTintColor(fluidStack);
    }

    public static int getColorARGB(@NotNull FluidStack fluidStack, float fluidScale) {
        if (fluidStack.isEmpty()) {
            return -1;
        }
        int color = getColorARGB(fluidStack);
        if (MekanismUtils.lighterThanAirGas(fluidStack)) {
            //TODO: We probably want to factor in the fluid's alpha value somehow
            return getColorARGB(FastColor.ARGB32.red(color), FastColor.ARGB32.green(color), FastColor.ARGB32.blue(color), Math.min(1, fluidScale + 0.2F));
        }
        return color;
    }

    public static int getColorARGB(@NotNull ChemicalStack<?> stack, float scale, boolean gaseous) {
        return getColorARGB(stack.getType(), scale, gaseous);
    }

    public static int getColorARGB(@NotNull Chemical<?> chemical, float scale, boolean gaseous) {
        if (chemical.isEmptyType()) {
            return -1;
        }
        int color = chemical.getTint();
        return getColorARGB(FastColor.ARGB32.red(color), FastColor.ARGB32.green(color), FastColor.ARGB32.blue(color), gaseous ? Math.min(1, scale + 0.2F) : 1);
    }

    public static int getColorARGB(float red, float green, float blue, float alpha) {
        return getColorARGB((int) (255 * red), (int) (255 * green), (int) (255 * blue), alpha);
    }

    public static int getColorARGB(int red, int green, int blue, float alpha) {
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 1) {
            alpha = 1;
        }
        return FastColor.ARGB32.color((int) (255 * alpha), red, green, blue);
    }

    public static int calculateGlowLight(int combinedLight, @NotNull FluidStack fluid) {
        return fluid.isEmpty() ? combinedLight : calculateGlowLight(combinedLight, fluid.getFluid().getFluidType().getLightLevel(fluid));
    }

    public static int calculateGlowLight(int combinedLight, int glow) {
        //Only factor the glow into the block light portion
        return (combinedLight & 0xFFFF0000) | Math.max(Math.min(glow, 15) << 4, combinedLight & 0xFFFF);
    }

    public static void renderColorOverlay(GuiGraphics guiGraphics, int x, int y, int color) {
        guiGraphics.fill(RenderType.guiOverlay(), x, y, guiGraphics.guiWidth(), guiGraphics.guiHeight(), color);
    }

    public static float getPartialTick() {
        return Minecraft.getInstance().getFrameTime();
    }

    public static void rotate(PoseStack matrix, Direction facing, float north, float south, float west, float east) {
        switch (facing) {
            case NORTH -> matrix.mulPose(Axis.YP.rotationDegrees(north));
            case SOUTH -> matrix.mulPose(Axis.YP.rotationDegrees(south));
            case WEST -> matrix.mulPose(Axis.YP.rotationDegrees(west));
            case EAST -> matrix.mulPose(Axis.YP.rotationDegrees(east));
        }
    }

    private static <T extends Enum<T> & SupportsColorMap> void parseColorAtlas(ResourceLocation rl, T[] elements) {
        List<Color> parsed = ColorAtlas.load(rl, elements.length);
        if (parsed.size() < elements.length) {
            Mekanism.logger.error("Failed to parse color atlas: {}.", rl);
            return;
        }
        for (int i = 0; i < elements.length; i++) {
            Color color = parsed.get(i);
            if (color != null) {
                elements[i].setColorFromAtlas(color.rgbArray());
            }
        }
    }

    @SubscribeEvent
    public static void onStitch(TextureStitchEvent.Post event) {
        TextureAtlas map = event.getAtlas();
        if (!map.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            return;
        }
        for (TransmissionType type : EnumUtils.TRANSMISSION_TYPES) {
            overlays.put(type, map.getSprite(Mekanism.rl("block/overlay/" + type.getTransmission() + "_overlay")));
        }

        whiteIcon = map.getSprite(Mekanism.rl("block/overlay/overlay_white"));
        energyIcon = map.getSprite(Mekanism.rl("liquid/energy"));
        heatIcon = map.getSprite(Mekanism.rl("liquid/heat"));
        redstonePulse = map.getSprite(Mekanism.rl("icon/redstone_control_pulse"));
        teleporterPortal = map.getSprite(Mekanism.rl("block/teleporter_portal"));

        DigitalMinerBakedModel.onStitch(event);

        //Note: These are called in post rather than pre to make sure the icons have properly been stitched/attached
        RenderLogisticalTransporter.onStitch(map);
        RenderTransmitterBase.onStitch();

        //Reset any cached models now that the atlases are built
        ModelRenderer.resetCachedModels();
        RenderDigitalMiner.resetCachedVisuals();
        RenderDimensionalStabilizer.resetCachedVisuals();
        RenderFluidTank.resetCachedModels();
        RenderNutritionalLiquifier.resetCachedModels();
        RenderPigmentMixer.resetCached();
        RenderMechanicalPipe.onStitch();
        RenderSeismicVibrator.resetCached();
        RenderTickHandler.resetCached();
        RenderTeleporter.resetCachedModels();

        parseColorAtlas(Mekanism.rl("textures/colormap/primary.png"), EnumUtils.COLORS);
        parseColorAtlas(Mekanism.rl("textures/colormap/tiers.png"), EnumUtils.TIERS);
        SpecialColors.GUI_OBJECTS.parse(Mekanism.rl("textures/colormap/gui_objects.png"));
        SpecialColors.GUI_TEXT.parse(Mekanism.rl("textures/colormap/gui_text.png"));
        GuiElementHolder.updateBackgroundColor();
    }

    public enum FluidTextureType {
        STILL,
        FLOWING
    }

    public static class Model3D {

        public float minX, minY, minZ;
        public float maxX, maxY, maxZ;

        private final SpriteInfo[] textures = new SpriteInfo[6];
        private final boolean[] renderSides = {true, true, true, true, true, true};

        public Model3D setSideRender(Predicate<Direction> shouldRender) {
            for (Direction direction : EnumUtils.DIRECTIONS) {
                setSideRender(direction, shouldRender.test(direction));
            }
            return this;
        }

        public Model3D setSideRender(Direction side, boolean value) {
            renderSides[side.ordinal()] = value;
            return this;
        }

        public Model3D copy() {
            Model3D copy = new Model3D();
            System.arraycopy(textures, 0, copy.textures, 0, textures.length);
            System.arraycopy(renderSides, 0, copy.renderSides, 0, renderSides.length);
            return copy.bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Nullable
        public SpriteInfo getSpriteToRender(Direction side) {
            int ordinal = side.ordinal();
            return renderSides[ordinal] ? textures[ordinal] : null;
        }

        public Model3D shrink(float amount) {
            return grow(-amount);
        }

        public Model3D grow(float amount) {
            return bounds(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
        }

        public Model3D xBounds(float min, float max) {
            this.minX = min;
            this.maxX = max;
            return this;
        }

        public Model3D yBounds(float min, float max) {
            this.minY = min;
            this.maxY = max;
            return this;
        }

        public Model3D zBounds(float min, float max) {
            this.minZ = min;
            this.maxZ = max;
            return this;
        }

        public Model3D bounds(float min, float max) {
            return bounds(min, min, min, max, max, max);
        }

        public Model3D bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return xBounds(minX, maxX)
                  .yBounds(minY, maxY)
                  .zBounds(minZ, maxZ);
        }

        public Model3D prepSingleFaceModelSize(Direction face) {
            bounds(0, 1);
            return switch (face) {
                case DOWN -> yBounds(-0.01F, -0.001F);
                case UP -> yBounds(1.001F, 1.01F);
                case NORTH -> zBounds(-0.01F, -0.001F);
                case SOUTH -> zBounds(1.001F, 1.01F);
                case WEST -> xBounds(-0.01F, -0.001F);
                case EAST -> xBounds(1.001F, 1.01F);
            };
        }

        public Model3D prepFlowing(@NotNull FluidStack fluid) {
            SpriteInfo still = new SpriteInfo(getFluidTexture(fluid, FluidTextureType.STILL), 16);
            SpriteInfo flowing = new SpriteInfo(getFluidTexture(fluid, FluidTextureType.FLOWING), 8);
            return setTextures(still, still, flowing, flowing, flowing, flowing);
        }

        public Model3D setTexture(Direction side, @Nullable SpriteInfo spriteInfo) {
            textures[side.ordinal()] = spriteInfo;
            return this;
        }

        public Model3D setTexture(TextureAtlasSprite tex) {
            return setTexture(tex, 16);
        }

        public Model3D setTexture(TextureAtlasSprite tex, int size) {
            Arrays.fill(textures, new SpriteInfo(tex, size));
            return this;
        }

        public Model3D setTextures(SpriteInfo down, SpriteInfo up, SpriteInfo north, SpriteInfo south, SpriteInfo west, SpriteInfo east) {
            textures[0] = down;
            textures[1] = up;
            textures[2] = north;
            textures[3] = south;
            textures[4] = west;
            textures[5] = east;
            return this;
        }

        public record SpriteInfo(TextureAtlasSprite sprite, int size) {

            public float getU(float u) {
                return sprite.getU(u * size);
            }

            public float getV(float v) {
                return sprite.getV(v * size);
            }
        }

        public interface ModelBoundsSetter {

            Model3D set(float min, float max);
        }
    }

    public static class LazyModel implements Supplier<Model3D> {

        private final Supplier<Model3D> supplier;
        @Nullable
        private Model3D model;

        public LazyModel(Supplier<Model3D> supplier) {
            this.supplier = supplier;
        }

        public void reset() {
            model = null;
        }

        @Override
        public Model3D get() {
            if (model == null) {
                model = supplier.get();
            }
            return model;
        }
    }
}