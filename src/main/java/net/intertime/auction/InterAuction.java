package net.intertime.auction;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class InterAuction extends JavaPlugin {

    private Auction auction;
    private Eco eco;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        auction = new Auction(this);
        getServer().getPluginManager().registerEvents(auction, this);
        getLogger().info("InterAuction увімкнено!");
    }

    @Override
    public void onDisable() {
        if (auction != null) auction.save();
    }

    /** Економіка створюється при першому використанні, щоб усі плагіни вже встигли завантажитись. */
    public Eco eco() {
        if (eco == null) {
            eco = Eco.create(this);
            getLogger().info("Економіка аукціону: " + eco.name());
        }
        return eco;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 0) {
            switch (a[0].toLowerCase()) {
                case "balance", "bal", "баланс" -> {
                    if (s instanceof Player p) {
                        Util.msg(p, "<gray>Баланс: <gold>" + Util.money(eco().balance(p))
                                + " <dark_gray>(" + eco().name() + ")");
                    }
                    return true;
                }
                case "give" -> {
                    if (!s.hasPermission("intertime.auction.admin")) {
                        s.sendMessage("Немає прав.");
                        return true;
                    }
                    if (!(eco() instanceof Eco.InternalEco)) {
                        s.sendMessage("Гроші видає " + eco().name() + ", а не InterAuction.");
                        return true;
                    }
                    if (a.length < 3) {
                        s.sendMessage("Використання: /ah give <гравець> <сума>");
                        return true;
                    }
                    Player t = Bukkit.getPlayerExact(a[1]);
                    Long n = Auction.parsePrice(a[2]);
                    if (t == null || n == null) {
                        s.sendMessage("Гравця не знайдено або сума невірна.");
                        return true;
                    }
                    eco().give(t.getUniqueId(), n);
                    s.sendMessage("Видано " + n + " монет гравцю " + t.getName());
                    return true;
                }
                default -> {
                }
            }
        }
        return auction.command(s, a);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) {
            List<String> l = new ArrayList<>(List.of("sell", "search", "my", "balance"));
            if (s.hasPermission("intertime.auction.admin")) l.add("give");
            return l.stream().filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
