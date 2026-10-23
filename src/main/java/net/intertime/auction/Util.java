package net.intertime.auction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Locale;

/** Допоміжні методи: текст, предмети, форматування. */
final class Util {

    static final String PREFIX = "<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>» ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Util() {
    }

    static Component c(String s) {
        return MM.deserialize("<!italic>" + s);
    }

    static void msg(Player p, String s) {
        p.sendMessage(c(PREFIX + s));
    }

    static String fmt(long n) {
        return String.format(Locale.US, "%,d", n).replace(",", " ");
    }

    static String money(long n) {
        return fmt(n) + " монет";
    }

    static Component title(String s) {
        return MM.deserialize("<gradient:#00e5ff:#7c4dff><bold>ɪɴᴛᴇʀᴛɪᴍᴇ</bold></gradient> <dark_gray>• <white>" + s);
    }

    static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(c(name));
        if (lore.length > 0) im.lore(Arrays.stream(lore).map(Util::c).toList());
        it.setItemMeta(im);
        return it;
    }

    static ItemStack glow(ItemStack it) {
        ItemMeta im = it.getItemMeta();
        im.setEnchantmentGlintOverride(true);
        it.setItemMeta(im);
        return it;
    }
}
