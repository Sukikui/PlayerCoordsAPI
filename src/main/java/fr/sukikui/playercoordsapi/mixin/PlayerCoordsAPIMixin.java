package fr.sukikui.playercoordsapi.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Empty template mixin kept as a starting point for future common-side injections.
 */
@Mixin(MinecraftServer.class)
public class PlayerCoordsAPIMixin {

	/**
	 * No-op hook placeholder.
	 */
	@Inject(at = @At("HEAD"), method = "loadWorld")
	private void init(CallbackInfo info) {
	}
}
