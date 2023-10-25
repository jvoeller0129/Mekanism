package mekanism.common.integration;

import java.util.List;
import java.util.Optional;
import mekanism.common.integration.computer.FactoryRegistry;
import mekanism.common.integration.computer.computercraft.CCCapabilityHelper;
import mekanism.common.integration.crafttweaker.content.CrTContentUtils;
import mekanism.common.integration.curios.CuriosIntegration;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.integration.jsonthings.JsonThingsIntegration;
import mekanism.common.integration.lookingat.theoneprobe.TOPProvider;
import mekanism.common.integration.projecte.NSSHelper;
import mekanism.common.recipe.bin.BinInsertRecipe;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.loading.DatagenModLoader;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;

/**
 * Hooks for Mekanism. Use to grab items or blocks out of different mods.
 *
 * @author AidanBrady
 */
public final class MekanismHooks {

    public static final String CC_MOD_ID = "computercraft";
    public static final String CRAFTTWEAKER_MOD_ID = "crafttweaker";
    public static final String CURIOS_MODID = "curios";
    public static final String DARK_MODE_EVERYWHERE_MODID = "darkmodeeverywhere";
    public static final String FLUX_NETWORKS_MOD_ID = "fluxnetworks";
    public static final String IC2_MOD_ID = "ic2";
    public static final String JEI_MOD_ID = "jei";
    public static final String JEITWEAKER_MOD_ID = "jeitweaker";
    public static final String JSON_THINGS_MOD_ID = "jsonthings";
    public static final String OC2_MOD_ID = "oc2";
    public static final String PROJECTE_MOD_ID = "projecte";
    public static final String RECIPE_STAGES_MOD_ID = "recipestages";
    public static final String TOP_MOD_ID = "theoneprobe";
    public static final String WILDFIRE_GENDER_MOD_ID = "wildfire_gender";

    public boolean CCLoaded;
    public boolean CraftTweakerLoaded;
    public boolean CuriosLoaded;
    public boolean DMELoaded;
    public boolean FluxNetworksLoaded;
    public boolean IC2Loaded;
    public boolean JEILoaded;
    public boolean JsonThingsLoaded;
    public boolean OC2Loaded;
    public boolean ProjectELoaded;
    public boolean RecipeStagesLoaded;
    public boolean TOPLoaded;
    public boolean WildfireGenderModLoaded;

    public void hookConstructor(final IEventBus bus) {
        ModList modList = ModList.get();
        CraftTweakerLoaded = modList.isLoaded(CRAFTTWEAKER_MOD_ID);
        CuriosLoaded = modList.isLoaded(CURIOS_MODID);
        JsonThingsLoaded = modList.isLoaded(JSON_THINGS_MOD_ID);
        if (CuriosLoaded) {
            CuriosIntegration.addListeners(bus);
        }
        if (CraftTweakerLoaded && !DatagenModLoader.isRunningDataGen()) {
            //Attempt to grab the mod event bus for CraftTweaker so that we can register our custom content in their namespace
            // to make it clearer which chemicals were added by CraftTweaker, and which are added by actual mods.
            // Gracefully fallback to our event bus if something goes wrong with getting CrT's and just then have the log have
            // warnings about us registering things in their namespace.
            IEventBus crtModEventBus = bus;
            Optional<? extends ModContainer> crtModContainer = ModList.get().getModContainerById(MekanismHooks.CRAFTTWEAKER_MOD_ID);
            if (crtModContainer.isPresent()) {
                ModContainer container = crtModContainer.get();
                if (container instanceof FMLModContainer modContainer) {
                    crtModEventBus = modContainer.getEventBus();
                }
            }
            //Register our CrT listener at lowest priority to try and ensure they get later ids than our normal registries
            crtModEventBus.addListener(EventPriority.LOWEST, CrTContentUtils::registerCrTContent);
        }
        if (JsonThingsLoaded) {
            JsonThingsIntegration.hook(bus);
        }
    }

    public void hookCommonSetup() {
        ModList modList = ModList.get();
        CCLoaded = modList.isLoaded(CC_MOD_ID);
        DMELoaded = modList.isLoaded(DARK_MODE_EVERYWHERE_MODID);
        IC2Loaded = modList.isLoaded(IC2_MOD_ID);
        JEILoaded = modList.isLoaded(JEI_MOD_ID);
        OC2Loaded = modList.isLoaded(OC2_MOD_ID);
        ProjectELoaded = modList.isLoaded(PROJECTE_MOD_ID);
        RecipeStagesLoaded = modList.isLoaded(RECIPE_STAGES_MOD_ID);
        TOPLoaded = modList.isLoaded(TOP_MOD_ID);
        FluxNetworksLoaded = modList.isLoaded(FLUX_NETWORKS_MOD_ID);
        WildfireGenderModLoaded = modList.isLoaded(WILDFIRE_GENDER_MOD_ID);
        if (computerCompatEnabled()) {
            FactoryRegistry.load();
            if (CCLoaded) {
                CCCapabilityHelper.registerApis();
            }
        }
        EnergyCompatUtils.initLoadedCache();

        //TODO - 1.20: Move this out of here and back to always being registered whenever it gets fixed in Neo.
        // Modifying the result doesn't apply properly when "quick crafting"
        if (modList.isLoaded("fastbench")) {
            MinecraftForge.EVENT_BUS.addListener(BinInsertRecipe::onCrafting);
        }
    }

    public void sendIMCMessages(InterModEnqueueEvent event) {
        if (DMELoaded) {
            //Note: While it is only strings, so it is safe to call and IMC validates the mods are loaded
            // we add this check here, so we can skip iterating the list of things we want to blacklist when it is not present
            sendDarkModeEverywhereIMC();
        }
        if (ProjectELoaded) {
            NSSHelper.init();
        }
        if (TOPLoaded) {
            InterModComms.sendTo(TOP_MOD_ID, "getTheOneProbe", TOPProvider::new);
        }
    }

    public boolean computerCompatEnabled() {
        return CCLoaded || OC2Loaded;
    }

    /**
     * @apiNote DME only uses strings in IMC, so we can safely just include them here without worrying about classloading issues
     */
    private void sendDarkModeEverywhereIMC() {
        List<String> methodBlacklist = List.of(
              //Used for drawing fluids and chemicals in various GUIs including JEI as well as similar styled things
              "mekanism.client.gui.GuiUtils:drawTiledSprite",
              //MekaSuit HUD rendering (already configurable by the user)
              "mekanism.client.render.HUDRenderer:renderCompass",
              "mekanism.client.render.HUDRenderer:renderHUDElement"
        );
        for (String method : methodBlacklist) {
            InterModComms.sendTo(DARK_MODE_EVERYWHERE_MODID, "dme-shaderblacklist", () -> method);
        }
    }
}
