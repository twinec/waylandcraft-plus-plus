package dev.evvie.waylandcraft;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.evvie.waylandcraft.compat.PolymerCompat;
import dev.evvie.waylandcraft.item.ServerItemManager;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.item.WindowItemInteractionProvider;
import dev.evvie.waylandcraft.network.WaylandCraftNetworking;
import com.mojang.brigadier.CommandDispatcher;

import dev.evvie.waylandcraft.item.WindowHandle;
import dev.evvie.waylandcraft.sharing.SharingNetworking;
import dev.evvie.waylandcraft.sharing.SharingServer;
import dev.evvie.waylandcraft.sharing.TestPatternSource;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public class WaylandCraftCommon implements ModInitializer {
	
	public static final String MOD_ID = "waylandcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static WaylandCraftCommon instance;
	
	public @Nullable WindowItemInteractionProvider windowItemInteractionProvider = null;
	public ServerItemManager serverItemManager = new ServerItemManager();
	public SharingServer sharingServer = new SharingServer();
	
	/* /waylandcraft testpattern [stop] (operators): a server-generated shared window with
	 * video and audio test signals, for checking window sharing without a second player.
	 * See TestPatternSource.
	 */
	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(MOD_ID)
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("testpattern")
				.executes((context) -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					long handle = sharingServer.startTestPattern(player);
					
					ItemStack item = new ItemStack(WindowItem.WINDOW, 1);
					new WindowHandle(TestPatternSource.OWNER, handle).writeTo(item);
					item.set(DataComponents.CUSTOM_NAME, Component.literal("Test Pattern " + handle));
					player.addItem(item);
					
					context.getSource().sendSuccess(() -> Component.literal(
						"Test pattern " + handle + " is floating in front of you; its item can go in an item frame too. "
						+ "The border flashes on each beep (sync), and beeps alternate left and right (stereo)."), false);
					return 1;
				})
				.then(Commands.literal("stop").executes((context) -> {
					int count = sharingServer.stopTestPatterns();
					context.getSource().sendSuccess(() -> Component.literal("Stopped " + count + " test pattern(s)"), false);
					return count;
				}))));
	}
	
	@Override
	public void onInitialize() {
		instance = this;
		WindowItem.register();

		// Must be gated here, before PolymerCompat is referenced at all --
		// PolymerCompat implements Polymer's PolymerItem interface on a
		// nested class, and merely loading that class (which happens as
		// soon as any of its methods are invoked, regardless of an internal
		// isModLoaded guard) throws NoClassDefFoundError when polymer-core
		// isn't present, since verifying its bytecode requires resolving
		// PolymerItem.
		if(FabricLoader.getInstance().isModLoaded("polymer-core")) {
			LOGGER.info("polymer-core detected, registering Polymer compat");
			PolymerCompat.register();
		} else {
			LOGGER.info("polymer-core not detected, skipping Polymer compat");
		}

		WaylandCraftNetworking.register();
		SharingNetworking.register(sharingServer);
		
		ServerTickEvents.START_LEVEL_TICK.register(serverItemManager);
		ServerTickEvents.START_LEVEL_TICK.register(sharingServer::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sharingServer.onLogout(handler.getPlayer()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
	}
	
}
