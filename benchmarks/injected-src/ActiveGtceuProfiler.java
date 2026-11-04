package com.lowdragmc.lowdraglib.bench;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * CI-ONLY source injected IDENTICALLY into pristine upstream and patched
 * LDLib before each build. Never added to either distribution's tracked
 * Java sources. Runs GTCEu 7.5.3 actual recipe machinery on the server
 * thread. No private TFG world or Minecraft client is involved.
 *
 * Reflection deliberately uses GTCEu's public implementation API rather than
 * guessing internal NBT. Fail closed when the pinned GTCEu API changes.
 */
@Mod.EventBusSubscriber(modid = "ldlib", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ActiveGtceuProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("ldlib.bench.active");
    private static final int Y = 80;
    private static final int RADIUS = 16;
    private static long lastStartNanos;
    private static long ticks;
    private static int machines, working, outputs;
    private static BufferedWriter writer;

    private static Class<?> workableType;
    private static Method findMachine;
    private static Method getProgress, getLastRecipe;
    private static Method energyCapacity, setEnergy;
    private static Method getItem, setItem;
    private static Field importItems, exportItems, energyContainer, recipeLogic;

    private ActiveGtceuProfiler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void start(TickEvent.ServerTickEvent event) {
        if (ENABLED && event.phase == TickEvent.Phase.START) {
            lastStartNanos = System.nanoTime();
        }
    }

    private static void initializeApi() throws Exception {
        if (findMachine != null) return;
        Class<?> metaType = Class.forName("com.gregtechceu.gtceu.api.machine.MetaMachine");
        workableType = Class.forName("com.gregtechceu.gtceu.api.machine.WorkableTieredMachine");
        Class<?> energyType = Class.forName("com.gregtechceu.gtceu.api.machine.TieredEnergyMachine");
        Class<?> handlerType =
                Class.forName("com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableItemStackHandler");
        Class<?> containerType =
                Class.forName("com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableEnergyContainer");
        Class<?> logicType =
                Class.forName("com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic");

        findMachine = metaType.getMethod("getMachine", BlockGetter.class, BlockPos.class);
        importItems = workableType.getField("importItems");
        exportItems = workableType.getField("exportItems");
        recipeLogic = workableType.getField("recipeLogic");
        energyContainer = energyType.getField("energyContainer");
        getItem = handlerType.getMethod("getStackInSlot", int.class);
        setItem = handlerType.getMethod("setStackInSlot", int.class, ItemStack.class);
        getProgress = logicType.getMethod("getProgress");
        getLastRecipe = logicType.getMethod("getLastRecipe");
        energyCapacity = containerType.getMethod("getEnergyCapacity");
        setEnergy = containerType.getMethod("setEnergyStored", long.class);
    }

    private static void scanMachines(ServerLevel level) throws Exception {
        int detected = 0;
        int active = 0;
        int totalOutputs = 0;
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                var position = new BlockPos(x, Y, z);
                Object machine = findMachine.invoke(null, level, position);
                if (!workableType.isInstance(machine)) continue;
                detected++;
                Object input = importItems.get(machine);
                Object output = exportItems.get(machine);
                Object energy = energyContainer.get(machine);
                Object logic = recipeLogic.get(machine);

                // Let real GTCEu recipe logic consume copper ingots and EU.
                // Refill only when input is exhausted; output is never silently
                // cleared, so the recipe load itself remains observable.
                ItemStack stack = (ItemStack) getItem.invoke(input, 0);
                if (stack.isEmpty()) {
                    setItem.invoke(input, 0, new ItemStack(Items.COPPER_INGOT, 64));
                }
                long capacity = (long) energyCapacity.invoke(energy);
                setEnergy.invoke(energy, capacity);

                int progress = (int) getProgress.invoke(logic);
                if (progress > 0 && getLastRecipe.invoke(logic) != null) active++;
                ItemStack finished = (ItemStack) getItem.invoke(output, 0);
                totalOutputs += finished.getCount();
            }
        }
        machines = detected;
        working = active;
        outputs = totalOutputs;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void end(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) throw new IllegalStateException("No running MinecraftServer");
            ServerLevel level = server.overworld();
            initializeApi();
            if (writer == null) {
                writer = new BufferedWriter(new FileWriter("ldlib-active-metrics.csv", false));
                writer.write("tick,tick_event_ms,detected_machines,working_machines,output_items\n");
                writer.flush();
            }
            ticks++;
            if (ticks % 20 == 0) scanMachines(level);
            double ms = (System.nanoTime() - lastStartNanos) / 1_000_000.0;
            // Raw tick-event time (START to END). This is an instrumented
            // interval, not necessarily the complete MinecraftServer tick.
            writer.write(ticks + "," + ms + "," + machines + "," + working + "," + outputs + "\n");
            if (ticks % 20 == 0) writer.flush();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "GTCEU_ACTIVE_BENCHMARK_FATAL: pinned GTCEu workload failed", e);
        }
    }
}
