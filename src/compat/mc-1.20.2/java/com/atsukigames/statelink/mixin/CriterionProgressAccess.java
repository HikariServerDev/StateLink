package com.atsukigames.statelink.mixin;

import java.time.Instant;
import net.minecraft.advancement.criterion.CriterionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CriterionProgress.class)
public interface CriterionProgressAccess {
    @Accessor("obtainedDate") void statelink$obtainedDate(Instant value);
}
