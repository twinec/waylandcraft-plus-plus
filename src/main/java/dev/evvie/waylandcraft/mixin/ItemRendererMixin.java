package dev.evvie.waylandcraft.mixin;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.item.WindowItem;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
	
	@Shadow
	private ItemModelShaper itemModelShaper;
	
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	public void render(
		ItemStack itemStack,
		ItemDisplayContext itemDisplayContext,
		boolean bl,
		PoseStack poseStack,
		MultiBufferSource multiBufferSource,
		int light,
		int overlayCoords,
		BakedModel bakedModel,
		CallbackInfo info,
		@Local LocalRef<BakedModel> bakedModelLocal
	) {
		if(itemStack.is(WindowItem.WINDOW)) {
			WLCToplevel toplevel = WindowItem.getToplevel(itemStack);
			if(toplevel == null) {
				bakedModelLocal.set(itemModelShaper.getModelManager().getModel(WindowItem.BROKEN_WINDOW_MODEL));
				return;
			}
			
			if(toplevel.appID == null) return;
			
			ResourceLocation icon = WaylandCraft.instance.xdgManager.getIcon(toplevel.appID);
			if(icon == null) return;
			
			poseStack.pushPose();
			bakedModel.getTransforms().getTransform(itemDisplayContext).apply(bl, poseStack);
			poseStack.translate(-0.5f, -0.5f, 0.0f);
			
			renderIconItem(poseStack.last(), multiBufferSource, icon, light, overlayCoords);
			
			poseStack.popPose();
			
			info.cancel();
		}
	}
	
	private void renderIconItem(Pose pose, MultiBufferSource source, ResourceLocation tex, int light, int overlayCoords) {
		VertexConsumer buffer = source.getBuffer(RenderType.itemEntityTranslucentCull(tex));
		Vector3f pos1 = pose.pose().transformPosition(0, 1, 0, new Vector3f());
		Vector3f pos2 = pose.pose().transformPosition(0, 0, 0, new Vector3f());
		Vector3f pos3 = pose.pose().transformPosition(1, 0, 0, new Vector3f());
		Vector3f pos4 = pose.pose().transformPosition(1, 1, 0, new Vector3f());
		
		Vector2f uv1 = new Vector2f(0, 0);
		Vector2f uv2 = new Vector2f(0, 1);
		Vector2f uv3 = new Vector2f(1, 1);
		Vector2f uv4 = new Vector2f(1, 0);
		
		Vector3f normal = pose.transformNormal(0, 0, 1, new Vector3f());
		
		// Front quad
		buffer.vertex(/* pos */ pos1.x, pos1.y, pos1.z, /* color */ 1, 1, 1, 1, /* uv */ uv1.x, uv1.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos2.x, pos2.y, pos2.z, /* color */ 1, 1, 1, 1, /* uv */ uv2.x, uv2.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos3.x, pos3.y, pos3.z, /* color */ 1, 1, 1, 1, /* uv */ uv3.x, uv3.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos4.x, pos4.y, pos4.z, /* color */ 1, 1, 1, 1, /* uv */ uv4.x, uv4.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		
		// Back quad
		buffer.vertex(/* pos */ pos1.x, pos1.y, pos1.z, /* color */ 1, 1, 1, 1, /* uv */ uv1.x, uv1.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos4.x, pos4.y, pos4.z, /* color */ 1, 1, 1, 1, /* uv */ uv4.x, uv4.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos3.x, pos3.y, pos3.z, /* color */ 1, 1, 1, 1, /* uv */ uv3.x, uv3.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
		buffer.vertex(/* pos */ pos2.x, pos2.y, pos2.z, /* color */ 1, 1, 1, 1, /* uv */ uv2.x, uv2.y, /* overlay */ overlayCoords, /* uv2 */ light, /* normal */ normal.x, normal.y, normal.z);
	}
	
}
