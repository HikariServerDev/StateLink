package com.atsukigames.statelink.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlacedAdvancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.ServerAdvancementLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerAdvancementTracker.class)
public interface AdvancementTrackerAccess {
    @Accessor("progress") Map<AdvancementEntry, AdvancementProgress> statelink$progress();
    @Accessor("visibleAdvancements") Set<AdvancementEntry> statelink$visible();
    @Accessor("updatedRoots") Set<PlacedAdvancement> statelink$visibilityUpdates();
    @Accessor("progressUpdates") Set<AdvancementEntry> statelink$progressUpdates();
    @Accessor("dirty") void statelink$dirty(boolean value);
    @Accessor("currentDisplayTab") void statelink$displayTab(AdvancementEntry advancement);
    @Invoker("onStatusUpdate") void statelink$onStatusUpdate(AdvancementEntry advancement);
    @Invoker("beginTrackingAllAdvancements") void statelink$beginTrackingAll(ServerAdvancementLoader loader);
}
