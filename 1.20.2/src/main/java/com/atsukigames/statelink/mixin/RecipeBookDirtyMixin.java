package com.atsukigames.statelink.mixin;

import com.atsukigames.statelink.StateLink;
import com.atsukigames.statelink.sync.MutationRevision;
import net.minecraft.recipe.book.RecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBook.class)
public abstract class RecipeBookDirtyMixin implements MutationRevision {
    @Unique private long statelink$revision;
    @Override public long statelink$mutationRevision() { return statelink$revision; }
    @Inject(method={"add(Lnet/minecraft/util/Identifier;)V", "remove(Lnet/minecraft/util/Identifier;)V",
        "display(Lnet/minecraft/util/Identifier;)V", "onRecipeDisplayed", "setGuiOpen",
        "setFilteringCraftable", "setOptions", "setCategoryOptions", "copyFrom"}, at=@At("RETURN"))
    private void statelink$changed(CallbackInfo ci) {
        if (StateLink.isEnabled()) statelink$revision++;
    }
}
