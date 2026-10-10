package in.smashandclash.minecraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import in.smashandclash.minecraft.ModContent;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Smash&Clash card in first person is held the way a map is held in one hand: your arm comes
 * up with the card between its fingers, the whole face (all four values) in view above the hotbar.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonCardMixin {
	@Shadow
	private void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, float inverseArmHeight, float attackValue, HumanoidArm arm, PlayerRenderState playerState) {
	}

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void smashandclash$holdCard(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot, InteractionHand hand, float attack,
		ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
		if (itemStack.isEmpty() || itemStack.getItem() != ModContent.CARD || state.isScoping) return;
		AvatarRenderState avatar = playerState.avatarRenderState;
		if (avatar == null) return;
		ci.cancel();
		boolean mainHand = hand == InteractionHand.MAIN_HAND;
		HumanoidArm arm = mainHand ? avatar.mainArm : avatar.mainArm.getOpposite();
		float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
		poseStack.pushPose();
		poseStack.translate(invert * 0.125F, -0.125F, 0.0F);
		if (!avatar.isInvisible) {
			poseStack.pushPose();
			poseStack.rotateDegrees(Axis.ZP, invert * 10.0F);
			renderPlayerArm(poseStack, submitNodeCollector, lightCoords, inverseArmHeight, attack, arm, playerState);
			poseStack.popPose();
		}
		// the card, in the fingers (the map's place in one hand), a little higher so its bottom value clears the hotbar
		poseStack.pushPose();
		poseStack.translate(invert * 0.47F, 0.06F + inverseArmHeight * -1.2F, -0.78F);
		float sqrtAttack = Mth.sqrt(attack);
		float xSwing = Mth.sin(sqrtAttack * (float) Math.PI);
		poseStack.translate(invert * -0.5F * xSwing, 0.4F * Mth.sin(sqrtAttack * (float) (Math.PI * 2)) - 0.3F * xSwing, -0.3F * Mth.sin(attack * (float) Math.PI));
		poseStack.rotateDegrees(Axis.XP, xSwing * -45.0F);
		poseStack.rotateDegrees(Axis.YP, invert * xSwing * -30.0F);
		poseStack.rotateDegrees(Axis.ZP, invert * -8.0F);
		poseStack.rotateDegrees(Axis.YP, invert * -12.0F);
		(mainHand ? state.mainHandRenderState : state.offHandRenderState).submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
		poseStack.popPose();
	}
}
