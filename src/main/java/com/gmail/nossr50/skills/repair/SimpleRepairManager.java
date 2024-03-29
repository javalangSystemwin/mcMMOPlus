package com.gmail.nossr50.skills.repair;

import java.util.HashMap;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.gmail.nossr50.datatypes.PlayerProfile;
import com.gmail.nossr50.datatypes.SkillType;
import com.gmail.nossr50.events.skills.McMMOPlayerRepairCheckEvent;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.util.Misc;
import com.gmail.nossr50.util.Permissions;
import com.gmail.nossr50.util.Users;

public class SimpleRepairManager implements RepairManager {
    private HashMap<Integer, Repairable> repairables;

    protected SimpleRepairManager() {
        this(55);
    }

    protected SimpleRepairManager(int repairablesSize) {
        this.repairables = new HashMap<Integer, Repairable>(repairablesSize);
    }

    @Override
    public void registerRepairable(Repairable repairable) {
        Integer itemId = repairable.getItemId();
        repairables.put(itemId, repairable);
    }

    @Override
    public void registerRepairables(List<Repairable> repairables) {
        for (Repairable repairable : repairables) {
            registerRepairable(repairable);
        }
    }

    @Override
    public boolean isRepairable(int itemId) {
        return repairables.containsKey(itemId);
    }

    @Override
    public boolean isRepairable(ItemStack itemStack) {
        return isRepairable(itemStack.getTypeId());
    }

    @Override
    public Repairable getRepairable(int id) {
        return repairables.get(id);
    }

    @Override
    public void handleRepair(Player player, ItemStack item) {
        // Load some variables for use
        PlayerProfile profile = Users.getProfile(player);
        short startDurability = item.getDurability();
        PlayerInventory inventory = player.getInventory();
        int skillLevel = profile.getSkillLevel(SkillType.REPAIR);
        Repairable repairable = repairables.get(item.getTypeId());

        // Permissions checks on material and item types
        if (!repairable.getRepairItemType().getPermissions(player)) {
            player.sendMessage(LocaleLoader.getString("mcMMO.NoPermission"));
            return;
        }

        if (!repairable.getRepairMaterialType().getPermissions(player)) {
            player.sendMessage(LocaleLoader.getString("mcMMO.NoPermission"));
            return;
        }

        // Level check
        if (skillLevel < repairable.getMinimumLevel()) {
            player.sendMessage(LocaleLoader.getString("Repair.Skills.Adept", new Object[] { String.valueOf(repairable.getMinimumLevel()), Misc.prettyItemString(item.getTypeId()) } ));
            return;
        }

        // Check if they have the proper material to repair with
        if (!inventory.contains(repairable.getRepairMaterialId())) {
            String message = LocaleLoader.getString("Skills.NeedMore") + " " + ChatColor.YELLOW + Misc.prettyItemString(repairable.getRepairMaterialId());
            if (repairable.getRepairMaterialMetadata() != (byte) -1) {
                // TODO: Do something nicer than append the metadata as a :# ?
                if (findInInventory(inventory, repairable.getRepairMaterialId(), repairable.getRepairMaterialMetadata()) == -1) {
                    message += ":" + repairable.getRepairMaterialMetadata();
                }
            }
            player.sendMessage(message);
            return;
        }

        // Do not repair if at full durability
        if (startDurability <= 0) {
            player.sendMessage(LocaleLoader.getString("Repair.Skills.FullDurability"));
            return;
        }

        // Do not repair stacked items
        if (item.getAmount() != 1) {
            player.sendMessage(LocaleLoader.getString("Repair.Skills.StackedItems"));
            return;
        }

        // Lets get down to business,
        // To defeat, the huns.
        int baseRepairAmount = repairable.getBaseRepairDurability(); // Did they send me daughters?
        short newDurability = Repair.repairCalculate(player, skillLevel, startDurability, baseRepairAmount); // When I asked for sons?

        // We're going to hold onto our repair item location
        int repairItemLocation;
        if (repairable.getRepairMaterialMetadata() == (byte) -1) {
            repairItemLocation = findInInventory(inventory, repairable.getRepairMaterialId());
        }
        else {
            // Special case for when the repairable has metadata that must be addressed
            repairItemLocation = findInInventory(inventory, repairable.getRepairMaterialId(), repairable.getRepairMaterialMetadata());
        }

        // This should never happen, but if it does we need to complain loudly about it.
        if (repairItemLocation == -1) {
            player.sendMessage("mcMMO encountered an error attempting to repair this item!");  // TODO: Locale ?
            return;
        }

        // Call event
        McMMOPlayerRepairCheckEvent event = new McMMOPlayerRepairCheckEvent(player, (short) (startDurability - newDurability), inventory.getItem(repairItemLocation), item);
        Bukkit.getServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }

        // Handle the enchants
        if (Repair.advancedConfig.getArcaneForgingEnchantLossEnabled() && !Permissions.arcaneBypass(player)) {
            // Generalize away enchantment work
            Repair.addEnchants(player, item);
        }

        // Remove the item
        removeOneFrom(inventory, repairItemLocation);

        // Give out XP like candy
        Repair.xpHandler(player, profile, startDurability, newDurability, repairable.getXpMultiplier());

        // Repair the item!
        item.setDurability(newDurability);
    }

    /**
     * Decrease the amount of items in this slot by one
     *
     * @param inventory PlayerInventory to work in
     * @param index Item index to decrement
     */
    private void removeOneFrom(PlayerInventory inventory, int index) {
        ItemStack item = inventory.getItem(index);
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        }
        else {
            item = new ItemStack(Material.AIR);
        }

        // I suspect this may not be needed, but I don't think it hurts
        inventory.setItem(index, item);
    }

    /**
     * Search the inventory for an item and return the index.
     *
     * @param inventory PlayerInventory to scan
     * @param itemId Item id to look for
     * @return index location where the item was found, or -1 if not found
     */
    private int findInInventory(PlayerInventory inventory, int itemId) {
        int location = inventory.first(itemId);

        // VALIDATE
        if (inventory.getItem(location).getTypeId() == itemId) {
            return location;
        }

        return -1;
    }

    /**
     * Search the inventory for an item and return the index.
     *
     * @param inventory PlayerInventory to scan
     * @param itemId Item id to look for
     * @param metadata Metadata to look for
     * @return index location where the item was found, or -1 if not found
     */
    private int findInInventory(PlayerInventory inventory, int itemId, byte metadata) {
        int location = -1;

        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];

            if (item == null) {
                continue;
            }

            if (item.getTypeId() == itemId) {
                if (item.getData().getData() == metadata) {
                    location = i;
                }
            }

            if (location != -1) {
                break;
            }
        }

        return location;
    }
}
