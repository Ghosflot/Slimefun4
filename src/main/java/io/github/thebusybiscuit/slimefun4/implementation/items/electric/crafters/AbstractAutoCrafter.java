package io.github.thebusybiscuit.slimefun4.implementation.items.electric.crafters;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.commons.lang.Validate;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.cscorelib2.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.items.ItemState;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.papermc.lib.PaperLib;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Lists.RecipeType;
import me.mrCookieSlime.Slimefun.Objects.Category;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.SlimefunItemStack;

public abstract class AbstractAutoCrafter extends SlimefunItem implements EnergyNetComponent {

    private int energyConsumedPerTick = -1;
    private int energyCapacity = -1;

    @ParametersAreNonnullByDefault
    public AbstractAutoCrafter(Category category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);

        addItemHandler(onRightClick());
        addItemHandler(new BlockTicker() {

            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                AbstractAutoCrafter.this.tick(b, data);
            }

            @Override
            public boolean isSynchronized() {
                return true;
            }
        });
    }

    @Nonnull
    private BlockUseHandler onRightClick() {
        return e -> e.getClickedBlock().ifPresent(b -> onRightClick(e.getPlayer(), b));
    }

    protected void tick(@Nonnull Block b, @Nonnull Config data) {
        AbstractRecipe recipe = getSelectedRecipe(b);

        if (recipe == null || getCharge(b.getLocation(), data) < getEnergyConsumption()) {
            // No valid recipe selected, abort...
            return;
        }

        Block chest = b.getRelative(BlockFace.DOWN);

        if (chest.getType() == Material.CHEST || chest.getType() == Material.TRAPPED_CHEST) {
            BlockState state = PaperLib.getBlockState(chest, false).getState();

            if (state instanceof InventoryHolder) {
                Inventory inv = ((InventoryHolder) state).getInventory();

                if (craft(inv, recipe)) {
                    removeCharge(b.getLocation(), getEnergyConsumption());
                }
            }
        }
    }

    /**
     * This method returns the currently selected {@link AbstractRecipe} for the given
     * {@link Block}.
     * 
     * @param b
     *            The {@link Block}
     * 
     * @return The currently selected {@link AbstractRecipe} or null
     */
    @Nullable
    public abstract AbstractRecipe getSelectedRecipe(@Nonnull Block b);

    /**
     * This method is called when a {@link Player} right clicks the {@link AbstractAutoCrafter}.
     * Use it to choose the {@link AbstractRecipe}.
     * 
     * @param p
     *            The {@link Player} who clicked
     * @param b
     *            The {@link Block} which was clicked
     */
    protected abstract void onRightClick(@Nonnull Player p, @Nonnull Block b);

    /**
     * This method checks whether the given {@link Predicate} matches the provided {@link ItemStack}.
     * 
     * @param item
     *            The {@link ItemStack} to check
     * @param predicate
     *            The {@link Predicate}
     * 
     * @return Whether the {@link Predicate} matches the {@link ItemStack}
     */
    @ParametersAreNonnullByDefault
    protected boolean matches(ItemStack item, Predicate<ItemStack> predicate) {
        return predicate.test(item);
    }

    @ParametersAreNonnullByDefault
    protected boolean matchesAny(Inventory inv, Map<Integer, Integer> itemQuantities, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);

            if (item != null) {
                int amount = itemQuantities.getOrDefault(slot, item.getAmount());

                if (amount > 0 && matches(item, predicate)) {
                    // Update our local quantity map
                    itemQuantities.put(slot, amount - 1);
                    return true;
                }
            }
        }

        return false;
    }

    public boolean craft(@Nonnull Inventory inv, @Nonnull AbstractRecipe recipe) {
        Validate.notNull(inv, "The Inventory must not be null");
        Validate.notNull(recipe, "The Recipe shall not be null");

        if (InvUtils.fits(inv, recipe.getResult())) {
            Map<Integer, Integer> itemQuantities = new HashMap<>();

            for (Predicate<ItemStack> predicate : recipe.getInputs()) {
                // Check if any Item matches the Predicate
                if (!matchesAny(inv, itemQuantities, predicate)) {
                    return false;
                }
            }

            // Remove ingredients
            for (Map.Entry<Integer, Integer> entry : itemQuantities.entrySet()) {
                ItemStack item = inv.getItem(entry.getKey());

                // Double-check to be extra safe
                if (item != null) {
                    item.setAmount(entry.getValue());
                }
            }

            // All Predicates have found a match
            return true;
        }

        return false;
    }

    /**
     * This method returns the max amount of electricity this machine can hold.
     * 
     * @return The max amount of electricity this Block can store.
     */
    @Override
    public int getCapacity() {
        return energyCapacity;
    }

    /**
     * This method returns the amount of energy that is consumed per operation.
     * 
     * @return The rate of energy consumption
     */
    public int getEnergyConsumption() {
        return energyConsumedPerTick;
    }

    /**
     * This sets the energy capacity for this machine.
     * This method <strong>must</strong> be called before registering the item
     * and only before registering.
     * 
     * @param capacity
     *            The amount of energy this machine can store
     * 
     * @return This method will return the current instance of {@link AContainer}, so that can be chained.
     */
    public final AbstractAutoCrafter setCapacity(int capacity) {
        Validate.isTrue(capacity > 0, "The capacity must be greater than zero!");

        if (getState() == ItemState.UNREGISTERED) {
            this.energyCapacity = capacity;
            return this;
        } else {
            throw new IllegalStateException("You cannot modify the capacity after the Item was registered.");
        }
    }

    /**
     * This method sets the energy consumed by this machine per tick.
     * 
     * @param energyConsumption
     *            The energy consumed per tick
     * 
     * @return This method will return the current instance of {@link AContainer}, so that can be chained.
     */
    public final AbstractAutoCrafter setEnergyConsumption(int energyConsumption) {
        Validate.isTrue(energyConsumption > 0, "The energy consumption must be greater than zero!");
        Validate.isTrue(energyCapacity > 0, "You must specify the capacity before you can set the consumption amount.");
        Validate.isTrue(energyConsumption <= energyCapacity, "The energy consumption cannot be higher than the capacity (" + energyCapacity + ')');

        this.energyConsumedPerTick = energyConsumption;
        return this;
    }

    @Override
    public void register(@Nonnull SlimefunAddon addon) {
        this.addon = addon;

        if (getCapacity() <= 0) {
            warn("The capacity has not been configured correctly. The Item was disabled.");
            warn("Make sure to call '" + getClass().getSimpleName() + "#setEnergyCapacity(...)' before registering!");
        }

        if (getEnergyConsumption() <= 0) {
            warn("The energy consumption has not been configured correctly. The Item was disabled.");
            warn("Make sure to call '" + getClass().getSimpleName() + "#setEnergyConsumption(...)' before registering!");
        }

        if (getCapacity() > 0 && getEnergyConsumption() > 0) {
            super.register(addon);
        }
    }

    @Override
    public final EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

}
