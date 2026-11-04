package com.atsukigames.statelink.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerAdvancements.class)
public interface AdvancementTrackerAccess {
    @Accessor("progress") Map<AdvancementHolder, AdvancementProgress> statelink$progress();
    @Accessor("visible") Set<AdvancementHolder> statelink$visible();
    @Accessor("rootsToUpdate") Set<AdvancementNode> statelink$visibilityUpdates();
    @Accessor("progressChanged") Set<AdvancementHolder> statelink$progressUpdates();
    @Accessor("isFirstPacket") void statelink$dirty(boolean value);
    @Accessor("lastSelectedTab") void statelink$displayTab(AdvancementHolder advancement);
    @Invoker("markForVisibilityUpdate") void statelink$onStatusUpdate(AdvancementHolder advancement);
    @Invoker("registerListeners") void statelink$beginTrackingAll(ServerAdvancementManager loader);
}
