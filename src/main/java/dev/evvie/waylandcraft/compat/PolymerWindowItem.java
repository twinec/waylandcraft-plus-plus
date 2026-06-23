package dev.evvie.waylandcraft.compat;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.item.WindowItem;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * WindowItem variant that implements {@link PolymerItem} so that vanilla clients
 * connected to a Polymer-enabled server see a Name Tag as a placeholder rather
 * than an unknown item.  The server-side behaviour (window handle tracking,
 * item use, etc.) is unchanged since it is all inherited from {@link WindowItem}.
 */
public class PolymerWindowItem extends WindowItem implements PolymerItem {

	@Override
	public Item getPolymerItem(ItemStack stack, @Nullable ServerPlayer player) {
		return Items.NAME_TAG;
	}

}
