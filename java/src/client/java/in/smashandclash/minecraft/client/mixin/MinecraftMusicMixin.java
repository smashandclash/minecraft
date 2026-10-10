package in.smashandclash.minecraft.client.mixin;

import in.smashandclash.minecraft.client.SmashAndClashClient;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While you sit at a Smash&Clash table, the game's music plays instead of Minecraft's. */
@Mixin(Minecraft.class)
public class MinecraftMusicMixin {
	@Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
	private void smashandclash$music(CallbackInfoReturnable<Music> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.gui.screen() != null && mc.gui.screen().getBackgroundMusic() != null) return;
		Music m = SmashAndClashClient.situational();
		if (m != null) cir.setReturnValue(m);
	}
}
