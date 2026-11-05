package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.sync.MutationRevision;
import com.atsukigames.statelink.utils.StatisticsDirtyPolicy;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StatsCounter.class)
public abstract class StatisticsDirtyMixin implements MutationRevision {
    @Unique private long statelink$revision;
    @Override public long statelink$mutationRevision() { return statelink$revision; }
    @Inject(method="setValue", at=@At("HEAD"))
    private void statelink$changed(Player player, Stat<?> stat, int value, CallbackInfo ci) {
        if (StateLink.isEnabled()
                && StatisticsDirtyPolicy.isDirtyRelevant(stat)
                && ((StatsCounter)(Object)this).getValue(stat) != value) {
            statelink$revision++;
        }
    }
}
