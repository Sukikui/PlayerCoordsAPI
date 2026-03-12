package fr.sukikui.playercoordsapi.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Empty template mixin kept as a starting point for future client-side injections.
 */
@Mixin(MinecraftClient.class)
public class PlayerCoordsAPIClientMixin {

	/**
	 * No-op hook placeholder.
	 */
	@Inject(at = @At("HEAD"), method = "run")
	private void init(CallbackInfo info) {
	}
}
