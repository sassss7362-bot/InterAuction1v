package net.intertime.auction;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Економіка аукціону. Плагін працює окремо від інших, а гроші бере з того,
 * що є на сервері: InterCasino (монети), Vault або власний баланс.
 */
interface Eco {

    String name();

    long balance(Player p);

    /** Забирає суму. false, якщо коштів не вистачає. */
    boolean take(Player p, long amount);

    /** Нараховує суму гравцю (також офлайн). */
    void give(UUID id, long amount);

    // ---------------------------------------------------------------- вибір

    static Eco create(InterAuction pl) {
        String mode = pl.getConfig().getString("economy", "auto").toLowerCase();
        if (mode.equals("auto") || mode.equals("intercasino")) {
            Plugin ic = Bukkit.getPluginManager().getPlugin("InterCasino");
            if (ic != null && ic.isEnabled()) {
                try {
                    return new CasinoEco(ic);
                } catch (Exception ex) {
                    pl.getLogger().warning("Не вдалось підключити InterCasino: " + ex);
                }
            }
        }
        if (mode.equals("auto") || mode.equals("vault")) {
            try {
                return new VaultEco();
            } catch (Exception ignored) {
            }
        }
        return new InternalEco(pl);
    }

    // ---------------------------------------------------------------- InterCasino

    class CasinoEco implements Eco {
        private final Object plugin;
        private final Object coins;
        private final Method mBalance, mTake, mAdd;

        @SuppressWarnings({"unchecked", "rawtypes"})
        CasinoEco(Plugin ic) throws Exception {
            this.plugin = ic;
            Class<?> cur = Class.forName("net.intertime.casino.Currency", true, ic.getClass().getClassLoader());
            this.coins = Enum.valueOf((Class) cur, "COINS");
            Class<?> main = ic.getClass();
            this.mBalance = main.getMethod("balance", Player.class, cur);
            this.mTake = main.getMethod("take", Player.class, cur, long.class);
            this.mAdd = main.getMethod("addCoins", UUID.class, long.class);
        }

        @Override
        public String name() {
            return "InterCasino";
        }

        @Override
        public long balance(Player p) {
            try {
                return (long) mBalance.invoke(plugin, p, coins);
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public boolean take(Player p, long amount) {
            try {
                return (boolean) mTake.invoke(plugin, p, coins, amount);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void give(UUID id, long amount) {
            try {
                mAdd.invoke(plugin, id, amount);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- Vault

    class VaultEco implements Eco {
        private final Object eco;
        private final Method mBal, mWithdraw, mDeposit;

        VaultEco() throws Exception {
            Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) throw new IllegalStateException("Vault economy not found");
            this.eco = rsp.getProvider();
            this.mBal = c.getMethod("getBalance", OfflinePlayer.class);
            this.mWithdraw = c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            this.mDeposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        }

        @Override
        public String name() {
            return "Vault";
        }

        @Override
        public long balance(Player p) {
            try {
                return (long) (double) (Double) mBal.invoke(eco, p);
            } catch (Exception e) {
                return 0;
            }
        }

        @Override
        public boolean take(Player p, long amount) {
            if (balance(p) < amount) return false;
            try {
                Object resp = mWithdraw.invoke(eco, p, (double) amount);
                return (boolean) resp.getClass().getMethod("transactionSuccess").invoke(resp);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void give(UUID id, long amount) {
            try {
                mDeposit.invoke(eco, Bukkit.getOfflinePlayer(id), (double) amount);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- власний баланс

    class InternalEco implements Eco {
        private final InterAuction pl;
        private final File file;
        private final Map<UUID, Long> bal = new HashMap<>();
        private final long start;

        InternalEco(InterAuction pl) {
            this.pl = pl;
            this.file = new File(pl.getDataFolder(), "balances.yml");
            this.start = pl.getConfig().getLong("internal-start-balance", 0);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String k : y.getKeys(false)) {
                try {
                    bal.put(UUID.fromString(k), y.getLong(k));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        private void save() {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<UUID, Long> e : bal.entrySet()) y.set(e.getKey().toString(), e.getValue());
            try {
                pl.getDataFolder().mkdirs();
                y.save(file);
            } catch (IOException ex) {
                pl.getLogger().warning("Не вдалось зберегти balances.yml: " + ex.getMessage());
            }
        }

        @Override
        public String name() {
            return "внутрішній баланс";
        }

        @Override
        public long balance(Player p) {
            return bal.getOrDefault(p.getUniqueId(), start);
        }

        @Override
        public boolean take(Player p, long amount) {
            long b = balance(p);
            if (b < amount) return false;
            bal.put(p.getUniqueId(), b - amount);
            save();
            return true;
        }

        @Override
        public void give(UUID id, long amount) {
            bal.put(id, Math.max(0, bal.getOrDefault(id, start) + amount));
            save();
        }
    }
}
