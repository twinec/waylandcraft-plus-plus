package dev.evvie.waylandcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.evvie.waylandcraft.WaylandCraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	
	@Shadow
	private HitResult pick(Entity entity, double d, double e, float f) {throw new AssertionError();}
	
	@Redirect(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;"))
	public HitResult pick(GameRenderer renderer, Entity cameraEntity, double blockInteractRange, double entityInteractRange, float partialTicks) {
		HitResult result = pick(cameraEntity, blockInteractRange, entityInteractRange, partialTicks);
		Vec3 pos = cameraEntity.getEyePosition(partialTicks);
		
		WaylandCraft.instance.trueGameHitResult = result;
		if(WaylandCraft.instance.overridePickBlock) return BlockHitResult.miss(pos, Direction.DOWN, BlockPos.containing(pos));
		
		return result;
	}
	
}
