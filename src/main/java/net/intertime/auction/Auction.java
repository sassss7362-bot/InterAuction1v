package net.intertime.auction;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Аукціон /ah: лоти, категорії, сортування, пошук. Оплата монетами InterUtil. */
public class Auction implements Listener {

    static final int PAGE = 45;

    enum Sort {
        NEW("Спочатку нові", Material.CLOCK),
        OLD("Спочатку старі", Material.COMPASS),
        CHEAP("Дешевші спочатку", Material.GOLD_NUGGET),
        RICH("Дорожчі спочатку", Material.GOLD_BLOCK),
        NAME("За назвою (А-Я)", Material.NAME_TAG),
        ENDING("Скоро закінчаться", Material.REDSTONE_TORCH);

        final String title;
        final Material icon;

        Sort(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    enum Cat {
        ALL("Усе", Material.NETHER_STAR),
        BLOCKS("Блоки", Material.GRASS_BLOCK),
        TOOLS("Інструменти", Material.DIAMOND_PICKAXE),
        WEAPONS("Зброя", Material.DIAMOND_SWORD),
        ARMOR("Броня", Material.DIAMOND_CHESTPLATE),
        FOOD("Їжа", Material.GOLDEN_APPLE),
        POTIONS("Зілля", Material.POTION),
        RESOURCES("Ресурси", Material.DIAMOND),
        REDSTONE("Редстоун", Material.REDSTONE),
        BOOKS("Книги", Material.ENCHANTED_BOOK),
        MISC("Різне", Material.ENDER_PEARL);

        final String title;
        final Material icon;

        Cat(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    static class Lot {
        int id;
        UUID seller;
        String name;
        long price, created, expires;
        ItemStack item;
    }

    static class View {
        int page;
        Cat cat = Cat.ALL;
        Sort sort = Sort.NEW;
        String search;
    }

    enum MType {MAIN, CATS, MY, CONFIRM}

    static class AhMenu implements InventoryHolder {
        final MType type;
        Inventory inv;
        View view;
        Lot lot;
        final int[] ids = new int[54];
        final int[] rets = new int[54];

        AhMenu(MType type) {
            this.type = type;
            Arrays.fill(ids, -1);
            Arrays.fill(rets, -1);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    enum Kind {SEARCH, SELL}

    record Pending(Kind kind, ItemStack snap, int slot) {
    }

    private static final int[] CAT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22};
    private static final int[] SORT_SLOTS = {28, 29, 30, 31, 32, 33};

    private static final Map<String, String> UA = new LinkedHashMap<>();

    static {
        String[][] d = {
                {"алмаз", "diamond"}, {"незерит", "netherite"}, {"заліз", "iron"}, {"золот", "gold"},
                {"смарагд", "emerald"}, {"меч", "sword"}, {"кирк", "pickaxe"}, {"сокир", "axe"},
                {"лопат", "shovel"}, {"мотик", "hoe"}, {"шолом", "helmet"}, {"нагрудн", "chestplate"},
                {"поножі", "leggings"}, {"наголінн", "leggings"}, {"черевик", "boots"}, {"чобіт", "boots"},
                {"елітр", "elytra"}, {"тотем", "totem"}, {"яблук", "apple"}, {"арбалет", "crossbow"},
                {"лук", "bow"}, {"триз", "trident"}, {"булав", "mace"}, {"щит", "shield"},
                {"перл", "pearl"}, {"зілл", "potion"}, {"книг", "book"}, {"гострот", "sharpness"},
                {"захист", "protection"}, {"міцн", "unbreaking"}, {"лагодж", "mending"}
        };
        for (String[] e : d) UA.put(e[0], e[1]);
    }

    private final InterAuction pl;
    private final File file;
    private final Map<Integer, Lot> lots = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> returns = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private int nextId = 1;

    public Auction(InterAuction pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "auction.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::cleanup, 1200L, 1200L);
    }

    // ================================================================ збереження

    static String enc(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    static ItemStack dec(String s) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next", nextId);
        for (Lot l : lots.values()) {
            String k = "lots." + l.id;
            y.set(k + ".seller", l.seller.toString());
            y.set(k + ".name", l.name);
            y.set(k + ".price", l.price);
            y.set(k + ".created", l.created);
            y.set(k + ".expires", l.expires);
            y.set(k + ".item", enc(l.item));
        }
        for (Map.Entry<UUID, List<ItemStack>> e : returns.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            y.set("returns." + e.getKey(), e.getValue().stream().map(Auction::enc).toList());
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            pl.getLogger().warning("Не вдалось зберегти auction.yml: " + ex.getMessage());
        }
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next", 1);
        ConfigurationSection ls = y.getConfigurationSection("lots");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                try {
                    ConfigurationSection s = ls.getConfigurationSection(k);
                    Lot l = new Lot();
                    l.id = Integer.parseInt(k);
                    l.seller = UUID.fromString(s.getString("seller"));
                    l.name = s.getString("name", "?");
                    l.price = s.getLong("price");
                    l.created = s.getLong("created");
                    l.expires = s.getLong("expires");
                    l.item = dec(s.getString("item"));
                    lots.put(l.id, l);
                    nextId = Math.max(nextId, l.id + 1);
                } catch (Exception ex) {
                    pl.getLogger().warning("Пропущено пошкоджений лот " + k);
                }
            }
        }
        ConfigurationSection rs = y.getConfigurationSection("returns");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                try {
                    List<ItemStack> list = new ArrayList<>();
                    for (String s : rs.getStringList(k)) list.add(dec(s));
                    returns.put(UUID.fromString(k), list);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ================================================================ утиліти

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private static void fill(Inventory inv, int from, int to) {
        ItemStack dark = Util.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = from; i < to; i++) inv.setItem(i, dark);
    }

    static String left(long ms) {
        if (ms <= 0) return "0хв";
        long m = ms / 60000, h = m / 60, d = h / 24;
        if (d > 0) return d + "д " + (h % 24) + "г";
        if (h > 0) return h + "г " + (m % 60) + "хв";
        return Math.max(1, m) + "хв";
    }

    static String itemName(ItemStack it) {
        ItemMeta im = it.getItemMeta();
        if (im != null && im.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(im.displayName());
        }
        return it.getType().name().toLowerCase().replace('_', ' ');
    }

    static Long parsePrice(String s) {
        s = s.toLowerCase().replace(" ", "").replace(",", ".");
        long mul = 1;
        if (s.endsWith("к") || s.endsWith("k")) {
            mul = 1000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("м") || s.endsWith("m")) {
            mul = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            long v = (long) (Double.parseDouble(s) * mul);
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack withLore(ItemStack base, String... extra) {
        ItemStack it = base.clone();
        ItemMeta im = it.getItemMeta();
        List<Component> lore = im.hasLore() ? new ArrayList<>(im.lore()) : new ArrayList<>();
        for (String s : extra) lore.add(Util.c(s));
        im.lore(lore);
        it.setItemMeta(im);
        return it;
    }

    private ItemStack lotItem(Lot l, Player p) {
        boolean own = l.seller.equals(p.getUniqueId());
        List<String> ex = new ArrayList<>();
        ex.add(" ");
        ex.add("<gold>Ціна: <yellow>" + Util.money(l.price));
        if (l.item.getAmount() > 1) {
            ex.add("<gray>За 1 шт: <white>" + Util.money(Math.max(1, l.price / l.item.getAmount())));
        }
        ex.add("<gray>Продавець: <white>" + l.name);
        ex.add("<gray>Закінчується через: <white>" + left(l.expires - System.currentTimeMillis()));
        ex.add(" ");
        ex.add(own ? "<red>Це ваш лот. Клік - зняти з продажу" : "<green>▶ Клік - купити");
        return withLore(l.item, ex.toArray(new String[0]));
    }

    // ================================================================ категорії

    static Cat catOf(Material m) {
        String n = m.name();
        if (n.equals("BOOK") || n.equals("ENCHANTED_BOOK") || n.equals("WRITABLE_BOOK")
                || n.equals("WRITTEN_BOOK") || n.equals("KNOWLEDGE_BOOK")) return Cat.BOOKS;
        if (n.endsWith("POTION") || n.equals("TIPPED_ARROW") || n.equals("EXPERIENCE_BOTTLE")
                || n.equals("DRAGON_BREATH")) return Cat.POTIONS;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_ARMOR") || n.equals("ELYTRA") || n.equals("SHIELD")) return Cat.ARMOR;
        if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.equals("ARROW") || n.equals("SPECTRAL_ARROW")) return Cat.WEAPONS;
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("FISHING_ROD") || n.equals("SHEARS") || n.equals("FLINT_AND_STEEL")
                || n.equals("BRUSH") || n.equals("SPYGLASS")) return Cat.TOOLS;
        if (m.isEdible()) return Cat.FOOD;
        if (n.equals("REDSTONE") || n.equals("REPEATER") || n.equals("COMPARATOR") || n.equals("PISTON")
                || n.equals("STICKY_PISTON") || n.equals("OBSERVER") || n.equals("HOPPER") || n.equals("DISPENSER")
                || n.equals("DROPPER") || n.equals("LEVER") || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
                || n.equals("TNT") || n.equals("DAYLIGHT_DETECTOR") || n.equals("TARGET") || n.equals("REDSTONE_TORCH")
                || n.equals("REDSTONE_LAMP") || n.equals("REDSTONE_BLOCK") || n.equals("TRIPWIRE_HOOK")
                || n.equals("SLIME_BLOCK") || n.equals("HONEY_BLOCK")) return Cat.REDSTONE;
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_") || n.equals("DIAMOND")
                || n.equals("EMERALD") || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("LAPIS_LAZULI")
                || n.equals("QUARTZ") || n.equals("NETHERITE_SCRAP") || n.equals("AMETHYST_SHARD")
                || n.equals("GUNPOWDER") || n.equals("BLAZE_ROD") || n.equals("ENDER_PEARL")
                || n.equals("NETHER_STAR") || n.equals("STRING") || n.equals("LEATHER") || n.equals("BONE")
                || n.equals("SLIME_BALL") || n.equals("PRISMARINE_SHARD")) return Cat.RESOURCES;
        if (m.isBlock()) return Cat.BLOCKS;
        return Cat.MISC;
    }

    // ================================================================ пошук

    private String norm(String q) {
        StringBuilder sb = new StringBuilder();
        for (String t : q.toLowerCase().trim().split("\\s+")) {
            String r = t;
            for (Map.Entry<String, String> e : UA.entrySet()) {
                if (t.startsWith(e.getKey())) {
                    r = e.getValue();
                    break;
                }
            }
            sb.append(r).append(' ');
        }
        return sb.toString().trim();
    }

    private boolean matches(ItemStack it, String[] toks) {
        StringBuilder hay = new StringBuilder(itemName(it).toLowerCase());
        hay.append(' ').append(it.getType().name().toLowerCase().replace('_', ' '));
        for (Enchantment en : it.getEnchantments().keySet()) hay.append(' ').append(en.getKey().getKey());
        if (it.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            for (Enchantment en : esm.getStoredEnchants().keySet()) hay.append(' ').append(en.getKey().getKey());
        }
        String h = hay.toString();
        for (String t : toks) if (!h.contains(t)) return false;
        return true;
    }

    private List<Lot> filtered(View v) {
        long now = System.currentTimeMillis();
        String[] toks = v.search == null ? null : norm(v.search).split("\\s+");
        List<Lot> out = new ArrayList<>();
        for (Lot l : lots.values()) {
            if (l.expires <= now) continue;
            if (v.cat != Cat.ALL && catOf(l.item.getType()) != v.cat) continue;
            if (toks != null && !matches(l.item, toks)) continue;
            out.add(l);
        }
        Comparator<Lot> cmp = switch (v.sort) {
            case NEW -> Comparator.comparingLong((Lot l) -> l.created).reversed();
            case OLD -> Comparator.comparingLong((Lot l) -> l.created);
            case CHEAP -> Comparator.comparingLong((Lot l) -> l.price);
            case RICH -> Comparator.comparingLong((Lot l) -> l.price).reversed();
            case NAME -> Comparator.comparing((Lot l) -> itemName(l.item).toLowerCase());
            case ENDING -> Comparator.comparingLong((Lot l) -> l.expires);
        };
        out.sort(cmp);
        return out;
    }

    // ================================================================ прострочені лоти

    private void cleanup() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Lot> it = lots.values().iterator(); it.hasNext(); ) {
            Lot l = it.next();
            if (l.expires > now) continue;
            it.remove();
            returns.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) Util.msg(s, "<yellow>Ваш лот закінчився. Предмет чекає в розділі <white>Мої лоти<yellow>.");
            changed = true;
        }
        if (changed) save();
    }

    private void giveItem(Player p, ItemStack item) {
        Map<Integer, ItemStack> over = p.getInventory().addItem(item.clone());
        if (!over.isEmpty()) {
            for (ItemStack rest : over.values()) {
                returns.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(rest);
            }
            Util.msg(p, "<yellow>Інвентар повний. Предмет лежить у розділі <white>Мої лоти<yellow>.");
        }
    }

    // ================================================================ команда

    public boolean command(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Тільки для гравців.");
            return true;
        }
        if (a.length == 0) {
            openMain(p);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "sell", "s", "продати" -> {
                if (a.length < 2) {
                    Util.msg(p, "<gray>Використання: <white>/ah sell <ціна> <gray>(наприклад 500 або 10к)");
                    return true;
                }
                Long price = parsePrice(a[1]);
                if (price == null) {
                    Util.msg(p, "<red>Невірна ціна.");
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    Util.msg(p, "<red>Візьми предмет у руку.");
                    return true;
                }
                if (createLot(p, price, hand.clone())) p.getInventory().setItemInMainHand(null);
            }
            case "search", "пошук" -> {
                View v = views.computeIfAbsent(p.getUniqueId(), k -> new View());
                v.search = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null;
                v.page = 0;
                v.cat = Cat.ALL;
                openMain(p);
            }
            case "my", "мої" -> openMy(p);
            default -> openMain(p);
        }
        return true;
    }

    private boolean createLot(Player p, long price, ItemStack item) {
        long min = pl.getConfig().getLong("auction.min-price", 1);
        long max = pl.getConfig().getLong("auction.max-price", 1_000_000_000L);
        int maxLots = pl.getConfig().getInt("auction.max-lots", 10);
        long hours = pl.getConfig().getLong("auction.duration-hours", 48);
        if (price < min || price > max) {
            Util.msg(p, "<red>Ціна має бути від " + Util.fmt(min) + " до " + Util.fmt(max) + ".");
            return false;
        }
        int own = 0;
        for (Lot l : lots.values()) if (l.seller.equals(p.getUniqueId())) own++;
        if (own >= maxLots) {
            Util.msg(p, "<red>Ліміт лотів: " + maxLots + ". Зніми або дочекайся продажу старих.");
            return false;
        }
        Lot l = new Lot();
        l.id = nextId++;
        l.seller = p.getUniqueId();
        l.name = p.getName();
        l.price = price;
        l.created = System.currentTimeMillis();
        l.expires = l.created + hours * 3_600_000L;
        l.item = item;
        lots.put(l.id, l);
        save();
        Util.msg(p, "<green>Лот виставлено: <white>" + itemName(item) + " <gray>x" + item.getAmount()
                + " <gray>за <gold>" + Util.money(price));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        return true;
    }

    // ================================================================ головне меню

    private void openMain(Player p) {
        AhMenu m = new AhMenu(MType.MAIN);
        m.view = views.computeIfAbsent(p.getUniqueId(), k -> new View());
        Component title = m.view.search == null
                ? Util.title("Аукціон")
                : Util.title("Пошук: " + m.view.search);
        m.inv = Bukkit.createInventory(m, 54, title);
        drawMain(p, m);
        p.openInventory(m.inv);
    }

    private void drawMain(Player p, AhMenu m) {
        cleanup();
        View v = m.view;
        List<Lot> list = filtered(v);
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        v.page = Math.max(0, Math.min(v.page, pages - 1));

        Inventory inv = m.inv;
        inv.clear();
        Arrays.fill(m.ids, -1);
        for (int i = 0; i < PAGE; i++) {
            int idx = v.page * PAGE + i;
            if (idx >= list.size()) break;
            Lot l = list.get(idx);
            inv.setItem(i, lotItem(l, p));
            m.ids[i] = l.id;
        }
        fill(inv, 45, 54);

        int mine = 0;
        for (Lot l : lots.values()) if (l.seller.equals(p.getUniqueId())) mine++;
        int ret = returns.getOrDefault(p.getUniqueId(), List.of()).size();

        inv.setItem(45, Util.item(Material.PAPER, "<red>[⟳] <gold>Оновити",
                "<yellow>→ <gold>Натисни, щоб оновити", " ", "<gray>Ця кнопка дозволяє", "<gray>оновити список товарів"));
        inv.setItem(46, Util.item(Material.ENDER_CHEST, "<green>Мої лоти",
                "<gray>Активних лотів: <white>" + mine, "<gray>Чекають на повернення: <white>" + ret,
                " ", "<yellow>→ Натисни щоб відкрити"));
        inv.setItem(47, Util.item(Material.ARROW, "<yellow>← Назад", "<gray>Попередня сторінка"));
        ItemStack pg = Util.item(Material.PAPER, "<white>Сторінка <yellow>" + (v.page + 1) + "<gray>/<yellow>" + pages,
                "<gray>Знайдено лотів: <white>" + list.size());
        pg.setAmount(Math.min(64, v.page + 1));
        inv.setItem(48, pg);
        inv.setItem(49, Util.item(Material.ARROW, "<yellow>Вперед →", "<gray>Наступна сторінка"));
        inv.setItem(50, Util.item(Material.GOLD_INGOT, "<gold>Продати предмет",
                "<gray>Команда: <white>/ah sell <ціна>", "<gray>Або клікни по предмету", "<gray>у своєму інвентарі"));
        inv.setItem(51, Util.item(Material.CHEST, "<aqua>Категорії та сортування",
                "<gray>Категорія: <white>" + v.cat.title, "<gray>Сортування: <white>" + v.sort.title,
                " ", "<yellow>→ Натисни щоб відкрити"));

        String[] sl = new String[Sort.values().length + 3];
        int k = 0;
        for (Sort s : Sort.values()) sl[k++] = (s == v.sort ? "<green>▶ <white>" : "<gray>  ") + s.title;
        sl[k++] = " ";
        sl[k++] = "<yellow>ЛКМ - наступне";
        sl[k] = "<yellow>ПКМ - попереднє";
        inv.setItem(52, Util.item(Material.COMPARATOR, "<light_purple>Сортування", sl));

        inv.setItem(53, Util.item(Material.COMPASS, "<aqua>Пошук",
                "<gray>Зараз: <white>" + (v.search == null ? "—" : v.search),
                " ", "<yellow>ЛКМ - ввести назву в чаті", "<yellow>ПКМ - скинути пошук"));
    }

    private void clickMain(Player p, AhMenu m, int s, ClickType ct) {
        View v = m.view;
        if (s < PAGE) {
            int id = m.ids[s];
            if (id < 0) return;
            Lot l = lots.get(id);
            if (l == null || l.expires <= System.currentTimeMillis()) {
                Util.msg(p, "<red>Цей лот вже недоступний.");
                drawMain(p, m);
                return;
            }
            click(p);
            if (l.seller.equals(p.getUniqueId())) {
                removeOwn(p, l);
                drawMain(p, m);
            } else {
                openConfirm(p, l);
            }
            return;
        }
        switch (s) {
            case 45 -> {
                click(p);
                drawMain(p, m);
            }
            case 46 -> {
                click(p);
                openMy(p);
            }
            case 47 -> {
                click(p);
                v.page--;
                drawMain(p, m);
            }
            case 49 -> {
                click(p);
                v.page++;
                drawMain(p, m);
            }
            case 50 -> Util.msg(p, "<gray>Щоб продати: <white>/ah sell <ціна> <gray>з предметом у руці, або клікни предмет у своєму інвентарі.");
            case 51 -> {
                click(p);
                openCats(p);
            }
            case 52 -> {
                Sort[] all = Sort.values();
                int i = v.sort.ordinal();
                i = (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT) ? (i + all.length - 1) % all.length : (i + 1) % all.length;
                v.sort = all[i];
                v.page = 0;
                click(p);
                drawMain(p, m);
            }
            case 53 -> {
                click(p);
                if (ct == ClickType.RIGHT || ct == ClickType.SHIFT_RIGHT) {
                    v.search = null;
                    v.page = 0;
                    openMain(p);
                } else {
                    pending.put(p.getUniqueId(), new Pending(Kind.SEARCH, null, -1));
                    Bukkit.getScheduler().runTask(pl, p::closeInventory);
                    Util.msg(p, "<yellow>Напиши в чаті, що шукати (наприклад <white>алмазний шолом<yellow>). <gray>Напиши <white>скасувати <gray>щоб відмінити.");
                }
            }
            default -> {
            }
        }
    }

    private void startSell(Player p, int slot) {
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) return;
        pending.put(p.getUniqueId(), new Pending(Kind.SELL, it.clone(), slot));
        Bukkit.getScheduler().runTask(pl, p::closeInventory);
        Util.msg(p, "<yellow>Введи в чаті ціну для <white>" + itemName(it) + " <gray>x" + it.getAmount()
                + "<yellow> (наприклад <white>500 <yellow>або <white>10к<yellow>). <gray>Напиши <white>скасувати <gray>щоб відмінити.");
    }

    private void removeOwn(Player p, Lot l) {
        lots.remove(l.id);
        giveItem(p, l.item);
        save();
        Util.msg(p, "<green>Лот знято з продажу.");
    }

    // ================================================================ категорії та сортування

    private void openCats(Player p) {
        AhMenu m = new AhMenu(MType.CATS);
        m.view = views.computeIfAbsent(p.getUniqueId(), k -> new View());
        m.inv = Bukkit.createInventory(m, 45, Util.title("Категорії та сортування"));
        drawCats(m);
        p.openInventory(m.inv);
    }

    private void drawCats(AhMenu m) {
        Inventory inv = m.inv;
        View v = m.view;
        ItemStack edge = Util.item(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 45; i++) inv.setItem(i, (i < 9 || i >= 36) ? edge : Util.item(Material.BLACK_STAINED_GLASS_PANE, " "));
        long now = System.currentTimeMillis();
        Cat[] cats = Cat.values();
        for (int i = 0; i < cats.length; i++) {
            Cat c = cats[i];
            int n = 0;
            for (Lot l : lots.values()) {
                if (l.expires > now && (c == Cat.ALL || catOf(l.item.getType()) == c)) n++;
            }
            boolean sel = c == v.cat;
            ItemStack it = Util.item(c.icon, (sel ? "<green>✔ " : "<yellow>") + c.title,
                    "<gray>Лотів: <white>" + n, sel ? "<green>Обрано" : "<yellow>→ Клік щоб обрати");
            if (sel) Util.glow(it);
            inv.setItem(CAT_SLOTS[i], it);
        }
        Sort[] sorts = Sort.values();
        inv.setItem(27, Util.item(Material.COMPARATOR, "<light_purple><bold>Сортування"));
        for (int i = 0; i < sorts.length; i++) {
            boolean sel = sorts[i] == v.sort;
            ItemStack it = Util.item(sorts[i].icon, (sel ? "<green>✔ " : "<gray>") + sorts[i].title,
                    sel ? "<green>Обрано" : "<yellow>→ Клік щоб обрати");
            if (sel) Util.glow(it);
            inv.setItem(SORT_SLOTS[i], it);
        }
        inv.setItem(40, Util.item(Material.ARROW, "<red>← Назад до аукціону"));
    }

    private void clickCats(Player p, AhMenu m, int s) {
        View v = m.view;
        if (s == 40) {
            click(p);
            openMain(p);
            return;
        }
        Cat[] cats = Cat.values();
        for (int i = 0; i < cats.length; i++) {
            if (CAT_SLOTS[i] == s) {
                v.cat = cats[i];
                v.page = 0;
                click(p);
                openMain(p);
                return;
            }
        }
        Sort[] sorts = Sort.values();
        for (int i = 0; i < sorts.length; i++) {
            if (SORT_SLOTS[i] == s) {
                v.sort = sorts[i];
                v.page = 0;
                click(p);
                drawCats(m);
                return;
            }
        }
    }

    // ================================================================ мої лоти

    private void openMy(Player p) {
        AhMenu m = new AhMenu(MType.MY);
        m.view = views.computeIfAbsent(p.getUniqueId(), k -> new View());
        m.inv = Bukkit.createInventory(m, 54, Util.title("Мої лоти"));
        drawMy(p, m);
        p.openInventory(m.inv);
    }

    private void drawMy(Player p, AhMenu m) {
        cleanup();
        Inventory inv = m.inv;
        inv.clear();
        Arrays.fill(m.ids, -1);
        Arrays.fill(m.rets, -1);
        int slot = 0;
        for (Lot l : lots.values()) {
            if (l.seller.equals(p.getUniqueId()) && slot < PAGE) {
                inv.setItem(slot, lotItem(l, p));
                m.ids[slot++] = l.id;
            }
        }
        List<ItemStack> r = returns.getOrDefault(p.getUniqueId(), List.of());
        for (int i = 0; i < r.size() && slot < PAGE; i++) {
            inv.setItem(slot, withLore(r.get(i), " ", "<yellow>Повернення (лот закінчився або інвентар був повний)", "<green>▶ Клік - забрати"));
            m.rets[slot++] = i;
        }
        fill(inv, 45, 54);
        inv.setItem(49, Util.item(Material.ARROW, "<red>← Назад до аукціону"));
        inv.setItem(53, Util.item(Material.HOPPER, "<green>Забрати все", "<gray>Забрати всі повернені предмети"));
    }

    private void clickMy(Player p, AhMenu m, int s) {
        UUID id = p.getUniqueId();
        if (s == 49) {
            click(p);
            openMain(p);
            return;
        }
        if (s == 53) {
            List<ItemStack> list = returns.get(id);
            if (list != null) {
                for (Iterator<ItemStack> it = list.iterator(); it.hasNext(); ) {
                    if (p.getInventory().addItem(it.next().clone()).isEmpty()) it.remove();
                    else {
                        Util.msg(p, "<yellow>Інвентар повний - решту забереш пізніше.");
                        break;
                    }
                }
                save();
            }
            click(p);
            drawMy(p, m);
            return;
        }
        if (s >= PAGE) return;
        if (m.ids[s] >= 0) {
            Lot l = lots.get(m.ids[s]);
            if (l != null) removeOwn(p, l);
            click(p);
            drawMy(p, m);
        } else if (m.rets[s] >= 0) {
            List<ItemStack> list = returns.get(id);
            int idx = m.rets[s];
            if (list != null && idx < list.size()) {
                if (p.getInventory().addItem(list.get(idx).clone()).isEmpty()) {
                    list.remove(idx);
                    save();
                } else {
                    Util.msg(p, "<red>Інвентар повний.");
                }
            }
            click(p);
            drawMy(p, m);
        }
    }

    // ================================================================ підтвердження купівлі

    private void openConfirm(Player p, Lot l) {
        AhMenu m = new AhMenu(MType.CONFIRM);
        m.lot = l;
        m.inv = Bukkit.createInventory(m, 27, Util.title("Підтвердження купівлі"));
        fill(m.inv, 0, 27);
        m.inv.setItem(13, lotItem(l, p));
        m.inv.setItem(11, Util.item(Material.LIME_CONCRETE, "<green><bold>Підтвердити",
                "<gray>Ціна: <gold>" + Util.money(l.price),
                "<gray>Твій баланс: <white>" + Util.money(pl.eco().balance(p))));
        m.inv.setItem(15, Util.item(Material.RED_CONCRETE, "<red><bold>Скасувати"));
        p.openInventory(m.inv);
    }

    private void clickConfirm(Player p, AhMenu m, int s) {
        if (s == 11) buy(p, m.lot);
        else if (s == 15) {
            click(p);
            openMain(p);
        }
    }

    private void buy(Player p, Lot l) {
        if (lots.get(l.id) != l || l.expires <= System.currentTimeMillis()) {
            Util.msg(p, "<red>Цей лот вже продано або він недоступний.");
            openMain(p);
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            Util.msg(p, "<red>Не можна купити власний лот.");
            return;
        }
        if (!pl.eco().take(p, l.price)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            Util.msg(p, "<red>Недостатньо монет. Потрібно: " + Util.money(l.price));
            return;
        }
        lots.remove(l.id);
        long net = l.price;
        pl.eco().give(l.seller, net);
        giveItem(p, l.item);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        Util.msg(p, "<green>Куплено: <white>" + itemName(l.item) + " <gray>x" + l.item.getAmount()
                + " <gray>за <gold>" + Util.money(l.price));
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            Util.msg(seller, "<green>" + p.getName() + " купив ваш лот <white>" + itemName(l.item)
                    + "<green>! Отримано: <gold>+" + Util.money(net));
        }
        openMain(p);
    }

    // ================================================================ події

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof AhMenu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        int s = e.getSlot();
        if (clicked != e.getView().getTopInventory()) {
            if (m.type == MType.MAIN && (e.getSlotType() == InventoryType.SlotType.CONTAINER
                    || e.getSlotType() == InventoryType.SlotType.QUICKBAR)) {
                startSell(p, s);
            }
            return;
        }
        switch (m.type) {
            case MAIN -> clickMain(p, m, s, e.getClick());
            case CATS -> clickCats(p, m, s);
            case MY -> clickMy(p, m, s);
            case CONFIRM -> clickConfirm(p, m, s);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof AhMenu) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> handleInput(p, pd, text));
    }

    private void handleInput(Player p, Pending pd, String text) {
        pending.remove(p.getUniqueId());
        if (text.equalsIgnoreCase("скасувати") || text.equalsIgnoreCase("cancel") || text.equals("-")) {
            Util.msg(p, "<gray>Скасовано.");
            return;
        }
        if (pd.kind() == Kind.SEARCH) {
            View v = views.computeIfAbsent(p.getUniqueId(), k -> new View());
            v.search = text;
            v.page = 0;
            v.cat = Cat.ALL;
            openMain(p);
            return;
        }
        Long price = parsePrice(text);
        if (price == null) {
            Util.msg(p, "<red>Невірна ціна. Спробуй ще раз через клік по предмету.");
            return;
        }
        ItemStack cur = p.getInventory().getItem(pd.slot());
        if (cur == null || !cur.isSimilar(pd.snap()) || cur.getAmount() != pd.snap().getAmount()) {
            Util.msg(p, "<red>Предмет у слоті змінився. Спробуй ще раз.");
            return;
        }
        if (createLot(p, price, cur.clone())) p.getInventory().setItem(pd.slot(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
