package com.atsukigames.statelink.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.ServerAdvancementLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerAdvancementTracker.class)
public interface AdvancementTrackerAccess {
    @Accessor("advancementToProgress") Map<Advancement, AdvancementProgress> statelink$progress();
    @Accessor("visibleAdvancements") Set<Advancement> statelink$visible();
    @Accessor("visibilityUpdates") Set<Advancement> statelink$visibilityUpdates();
    @Accessor("progressUpdates") Set<Advancement> statelink$progressUpdates();
    @Accessor("dirty") void statelink$dirty(boolean value);
    @Accessor("currentDisplayTab") void statelink$displayTab(Advancement advancement);
    @Invoker("updateCompleted") void statelink$updateCompleted();
    @Invoker("beginTrackingAllAdvancements") void statelink$beginTrackingAll(ServerAdvancementLoader loader);
}
