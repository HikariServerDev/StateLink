package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.sync.MutationRevision;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.advancement.AdvancementEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancementTracker.class)
public abstract class AdvancementsDirtyMixin implements MutationRevision {
    @Unique private long statelink$revision;
    @Override public long statelink$mutationRevision() { return statelink$revision; }
    @Inject(method={"grantCriterion", "revokeCriterion"}, at=@At("RETURN"))
    private void statelink$changed(AdvancementEntry advancement, String criterion, CallbackInfoReturnable<Boolean> ci) {
        if (StateLink.isEnabled() && Boolean.TRUE.equals(ci.getReturnValue())) statelink$revision++;
    }
}
