package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.sync.MutationRevision;
import com.atsukigames.statelink.utils.StatisticsDirtyPolicy;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.Stat;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StatHandler.class)
public abstract class StatisticsDirtyMixin implements MutationRevision {
    @Unique private long statelink$revision;
    @Override public long statelink$mutationRevision() { return statelink$revision; }
    @Inject(method="setStat", at=@At("HEAD"))
    private void statelink$changed(PlayerEntity player, Stat<?> stat, int value, CallbackInfo ci) {
        if (StateLink.isEnabled()
                && StatisticsDirtyPolicy.isDirtyRelevant(stat)
                && ((StatHandler)(Object)this).getStat(stat) != value) {
            statelink$revision++;
        }
    }
}
