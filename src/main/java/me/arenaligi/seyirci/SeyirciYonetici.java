package me.arenaligi.seyirci;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.mac.Mac;
import me.arenaligi.mac.MacYonetici;
import me.arenaligi.model.Dovuscu;
import me.arenaligi.veri.YamlDosya;

/**
 * Seyirciler: "Maçı İzle" NPC'si menüsünden tribüne ışınlanma ve bahis.
 * Tribünde hasar/PvP/blok kırma kapalıdır (ArenaYonetici). /arena cik ya da maç bitince seyirci
 * ışınlanmadan önceki konumuna döner. Seyirci kayıtları seyirciler.yml'de (çökmede girişte geri döner).
 */
public class SeyirciYonetici implements Listener {

    public static class Sahip implements InventoryHolder {
        final String macId;
        final int takim; // -1: ana menü, 0/1: o dövüşçüye bahis miktarı seçimi
        private Inventory envanter;
        Sahip(String macId, int takim) { this.macId = macId; this.takim = takim; }
        @Override public Inventory getInventory() { return envanter; }
    }

    private static final int SLOT_A = 11, SLOT_TRIBUN = 13, SLOT_B = 15, SLOT_GERI = 22;
    private static final double[] MIKTARLAR = {50, 100, 250, 500, 1000, 2500, 5000};

    private final ArenaLigi plugin;
    private final NamespacedKey npcKey, menuKey;
    private final YamlDosya dosya;
    private final Map<UUID, Location> seyirciler = new HashMap<>(); // seyirci -> tribünden önceki konum
    private final Map<UUID, String> hamKonum = new HashMap<>();      // dünyası yüklü olmayan dönüş konumları
    private boolean isinlaniyor = false;

    public SeyirciYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "izle_npc");
        this.menuKey = new NamespacedKey(plugin, "izle_menu");
        this.dosya = new YamlDosya(plugin, "seyirciler.yml", this::olustur);
        ConfigurationSection c = dosya.oku().getConfigurationSection("seyirciler");
        if (c != null) for (String k : c.getKeys(false)) {
            Location l = Konum.oku(c.getString(k));
            if (l != null) seyirciler.put(UUID.fromString(k), l);
            else hamKonum.put(UUID.fromString(k), c.getString(k));
        }
    }

    private Mesaj m() { return plugin.mesaj(); }

    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        hamKonum.forEach((u, s) -> { if (!seyirciler.containsKey(u)) y.set("seyirciler." + u, s); });
        seyirciler.forEach((u, l) -> y.set("seyirciler." + u, Konum.yaz(l)));
        return y;
    }

    public boolean seyirciMi(UUID u) { return seyirciler.containsKey(u); }
    public NamespacedKey npcKey() { return npcKey; }

    // ------------------------------------------------------------------ NPC
    public void kur(Player p) {
        Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCollidable(false);
        v.setProfession(Villager.Profession.CARTOGRAPHER);
        v.setCustomName(m().metin("npc-izle-isim", "&6&lMaçı İzle"));
        v.setCustomNameVisible(true);
        v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        m().gonder(p, "npc-izle-kuruldu", "&aMaçı İzle NPC'si kuruldu.");
    }

    public int sil(Player p) {
        int silinen = 0;
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) { e.remove(); silinen++; }
        }
        return silinen;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        menuAc(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHasar(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ MENÜ
    private ItemStack ikon(Material mat, String ad, List<String> lore, String veri) {
        ItemStack i = new ItemStack(mat);
        ItemMeta meta = i.getItemMeta();
        meta.setDisplayName(Mesaj.renk(ad));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(Mesaj.renk(s));
        meta.setLore(l);
        meta.addItemFlags(ItemFlag.values());
        if (veri != null) meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, veri);
        i.setItemMeta(meta);
        return i;
    }

    private ItemStack dovuscuIkonu(Mac mac, int takim, boolean bahisAcik) {
        UUID u = mac.takimlar.get(takim).get(0);
        Dovuscu d = plugin.dovusculer().get(u);
        ItemStack kafa = ikon(Material.PLAYER_HEAD, "&6\"" + mac.lakaplar.get(u) + "\"",
                List.of("&7Lig: " + (d == null ? "?" : plugin.lig().lig(d).gorunen()) + " &7| Puan: &f" + (d == null ? 0 : d.puan),
                        "&7G/M: &a" + (d == null ? 0 : d.galibiyet) + "&7/&c" + (d == null ? 0 : d.maglubiyet),
                        "&7Bahis: &e$" + MacYonetici.para(plugin.bahis().takimToplami(takim)) + " &7(" + plugin.bahis().takimKisi(takim) + " kişi)",
                        "", bahisAcik ? "&a► Bahis oynamak için tıkla" : "&8Bahis penceresi kapalı"), bahisAcik ? "takim:" + takim : null);
        SkullMeta sm = (SkullMeta) kafa.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
        kafa.setItemMeta(sm);
        return kafa;
    }

    public void menuAc(Player p) {
        Mac mac = plugin.mac().aktifMac();
        if (mac == null || mac.durum == Mac.Durum.ONAY) {
            long kalan = Math.max(0, plugin.mac().sonrakiEslestirme() - System.currentTimeMillis());
            m().gonder(p, "mac-yok", "&7Şu an maç yok. Sırada {sayi} kişi; sonraki eşleştirme ~{dk} dk sonra.",
                    "sayi", plugin.kuyruk().boyut(), "dk", (kalan + 59_999) / 60_000);
            return;
        }
        Sahip sahip = new Sahip(mac.id, -1);
        Inventory inv = Bukkit.createInventory(sahip, 27, m().metin("menu-izle-baslik", "&8Arena: &6{baslik}", "baslik", mac.baslik()));
        sahip.envanter = inv;
        boolean bahis = plugin.bahis().acikMi();
        inv.setItem(SLOT_A, dovuscuIkonu(mac, 0, bahis));
        inv.setItem(SLOT_B, dovuscuIkonu(mac, 1, bahis));
        inv.setItem(SLOT_TRIBUN, ikon(Material.ENDER_EYE, "&b&lTribüne Git",
                List.of("&7Maçı tribünden izle.", "&7Dönmek için: &f/arena cik", "", "&7Havuz: &e$" + MacYonetici.para(plugin.bahis().havuz())), "tribun"));
        p.openInventory(inv);
    }

    private void miktarMenusu(Player p, Mac mac, int takim) {
        Sahip sahip = new Sahip(mac.id, takim);
        UUID u = mac.takimlar.get(takim).get(0);
        Inventory inv = Bukkit.createInventory(sahip, 27, m().metin("menu-bahis-baslik", "&8Bahis: &6\"{lakap}\"", "lakap", mac.lakaplar.get(u)));
        sahip.envanter = inv;
        double max = plugin.getConfig().getDouble("bahis.max-mac", 5000), min = plugin.getConfig().getDouble("bahis.min", 50);
        int slot = 10;
        for (double mk : MIKTARLAR) {
            if (mk < min || mk > max) continue;
            inv.setItem(slot++, ikon(Material.GOLD_NUGGET, "&e$" + MacYonetici.para(mk), List.of("&7Bankandan çekilir ve emanete alınır.", "&a► Oyna"), "miktar:" + mk));
        }
        inv.setItem(SLOT_GERI, ikon(Material.ARROW, "&7◄ Geri", List.of("&7Başka miktar: &f/arena bahis <lakap> <miktar>"), "geri"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack i = event.getCurrentItem();
        String veri = i == null || !i.hasItemMeta() ? null : i.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        if (veri == null) return;
        Mac mac = plugin.mac().aktifMac();
        if (mac == null || !mac.id.equals(sahip.macId)) { p.closeInventory(); return; }
        if (veri.equals("tribun")) { p.closeInventory(); tribuneGit(p); }
        else if (veri.equals("geri")) menuAc(p);
        else if (veri.startsWith("takim:")) miktarMenusu(p, mac, Integer.parseInt(veri.substring(6)));
        else if (veri.startsWith("miktar:")) {
            if (plugin.bahis().oyna(p, sahip.takim, Double.parseDouble(veri.substring(7)))) menuAc(p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ TRİBÜN
    public void tribuneGit(Player p) {
        Mac mac = plugin.mac().aktifMac();
        UUID u = p.getUniqueId();
        if (mac == null || mac.durum == Mac.Durum.ONAY) { m().gonder(p, "izle-mac-yok", "&cŞu an izlenecek bir maç yok."); return; }
        if (mac.oyuncuMu(u)) { m().gonder(p, "izle-dovuscu", "&cKendi maçınızı tribünden izleyemezsiniz."); return; }
        Location tribun = plugin.arena().tribun();
        if (tribun == null) { m().gonder(p, "izle-tribun-yok", "&cTribün ayarlanmamış."); return; }
        if (plugin.meslek().hapisteMi(u) || plugin.meslek().durusmadaMi(u) || plugin.meslek().agirYaraliMi(u)) {
            m().gonder(p, "izle-engel", "&cŞu an tribüne gidemezsiniz.");
            return;
        }
        if (!seyirciler.containsKey(u)) seyirciler.put(u, p.getLocation().clone());
        dosya.hemenKaydet();
        isinla(p, tribun);
        m().gonder(p, "izle-tribun", "&aTribüne hoş geldiniz! &7Dönmek için: &f/arena cik");
    }

    /** /arena cik */
    public void cik(Player p) {
        if (!geriGonder(p)) m().gonder(p, "izle-seyirci-degil", "&cTribünde değilsiniz.");
    }

    /** Seyirciyi tribünden önceki konumuna döndürür. Seyirci değilse false. */
    public boolean geriGonder(Player p) {
        Location l = seyirciler.remove(p.getUniqueId());
        hamKonum.remove(p.getUniqueId());
        if (l == null) return false;
        dosya.kaydet();
        isinla(p, l);
        m().gonder(p, "izle-cikti", "&eTribünden ayrıldınız.");
        return true;
    }

    /** Maç bitince: çevrimiçi seyirciler döner; çevrimdışılar girişte döner. */
    public void macBitti() {
        for (UUID u : new ArrayList<>(seyirciler.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !p.isDead()) geriGonder(p);
        }
    }

    private void isinla(Player p, Location l) {
        isinlaniyor = true;
        try {
            p.teleport(l, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            isinlaniyor = false;
        }
    }

    /** Seyirci başka bir yolla (komut, başka eklenti) tribünden ayrılırsa seyircilikten düşer, geri ışınlanmaz. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isinlaniyor || !seyirciler.containsKey(event.getPlayer().getUniqueId())) return;
        if (plugin.arena().tribundeMi(event.getTo())) return;
        seyirciler.remove(event.getPlayer().getUniqueId());
        dosya.kaydet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !seyirciler.containsKey(p.getUniqueId())) return;
            Mac mac = plugin.mac().aktifMac();
            if (mac == null || mac.durum == Mac.Durum.ONAY) geriGonder(p);
        }, 5L);
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    /** Tribündekiler (anons için). */
    public List<Player> cevrimiciSeyirciler() {
        List<Player> l = new ArrayList<>();
        for (UUID u : seyirciler.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) l.add(p);
        }
        return l;
    }
}
