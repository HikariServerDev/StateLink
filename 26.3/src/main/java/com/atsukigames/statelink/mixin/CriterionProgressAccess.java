package com.atsukigames.statelink.mixin;

import java.time.Instant;
import net.minecraft.advancements.CriterionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CriterionProgress.class)
public interface CriterionProgressAccess {
    @Accessor("obtained") void statelink$obtainedDate(Instant value);
}
