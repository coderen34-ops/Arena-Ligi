package me.arenaligi.kit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.EsyaKodu;
import me.arenaligi.veri.YamlDosya;

/**
 * Maç envanter yedeği (yedekler.yml). Dövüşçünün kendi eşyası arenaya girmez:
 *  1) Envanter + zırh + offhand diske YAZILIR (senkron); yazılamazsa hiçbir şeye dokunulmaz.
 *  2) Envanter boşaltılır, kit verilir (oyuncu "aktif").
 *  3) Maç bitince envanter temizlenir (kit eşyaları gider), yedek geri verilir, oyuncu verisi diske yazılır, sonra yedek silinir.
 * Çökme/çıkış: oyuncu girişte aktif değilse ve yedeği varsa envanteri temizlenip yedek geri verilir
 * (her zaman önce temizlendiği için eşya kopyalanamaz). Ender sandığına dokunulmaz.
 * Aktif oyuncu: eşya atamaz/alamaz, sandık açamaz; ölünce eşya düşmez.
 */
public class EnvanterYedek implements Listener {

    private record Yedek(String isim, ItemStack[] icerik, String donus, long zaman) {}

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final Map<UUID, Yedek> yedekler = new HashMap<>();
    private final Set<UUID> aktif = new HashSet<>(); // Şu an kit kullananlar (bellekte; çökmede boşalır)
    private final Map<String, Map<String, Object>> okunamayan = new HashMap<>(); // Bozuk kayıtlar ham haliyle korunur

    public EnvanterYedek(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "yedekler.yml", this::olustur);
        YamlConfiguration y = dosya.oku();
        ConfigurationSection bolum = y.getConfigurationSection("yedekler");
        if (bolum != null) {
            for (String k : bolum.getKeys(false)) {
                ConfigurationSection c = bolum.getConfigurationSection(k);
                if (c == null) continue;
                try {
                    yedekler.put(UUID.fromString(k), new Yedek(c.getString("isim", "?"), EsyaKodu.oku(c.getString("icerik", "")),
                            c.getString("donus"), c.getLong("zaman")));
                } catch (RuntimeException e) {
                    // Okunamayan yedek SİLİNMEZ: dosyada aynen kalsın, admin elle kurtarabilsin
                    okunamayan.put(k, c.getValues(true));
                    plugin.getLogger().severe("[Yedek] " + k + " oyuncusunun envanter yedeği okunamadı! Dosyada korunuyor: " + e.getMessage());
                }
            }
        }
        if (!yedekler.isEmpty()) plugin.getLogger().warning("[Yedek] " + yedekler.size() + " oyuncunun bekleyen envanter yedeği var; girişte geri verilecek.");
    }

    private Mesaj m() { return plugin.mesaj(); }
    private KitYonetici kit() { return plugin.kitler(); }

    private YamlConfiguration olustur() {
        // Okunamayan kayıtlar ham haliyle aynen geri yazılır (silinmez), sonra bellektekiler
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Object>> e : okunamayan.entrySet()) y.createSection("yedekler." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Yedek> e : yedekler.entrySet()) {
            String yol = "yedekler." + e.getKey();
            y.set(yol + ".isim", e.getValue().isim());
            y.set(yol + ".icerik", EsyaKodu.yaz(e.getValue().icerik()));
            y.set(yol + ".donus", e.getValue().donus());
            y.set(yol + ".zaman", e.getValue().zaman());
        }
        return y;
    }

    public boolean aktifMi(UUID u) { return aktif.contains(u); }
    public boolean yedekVarMi(UUID u) { return yedekler.containsKey(u); }
    public Map<UUID, String> bekleyenler() {
        Map<UUID, String> s = new HashMap<>();
        yedekler.forEach((u, y) -> s.put(u, y.isim()));
        return s;
    }

    /** Yedekteki dönüş konumu (dünyası yüklü değilse null). */
    public Location donusKonumu(UUID u) {
        Yedek y = yedekler.get(u);
        return y == null ? null : Konum.oku(y.donus());
    }

    // ------------------------------------------------------------------ YEDEKLE / GERİ VER
    /**
     * Envanteri diske yedekler, boşaltır ve kiti verir. Herhangi bir adım olmazsa oyuncunun envanterine dokunulmaz.
     * @param donus maçtan sonra dönülecek konum
     * @return başarılıysa true
     */
    public boolean kitVer(Player p, String kitAdi, Location donus) {
        UUID u = p.getUniqueId();
        if (yedekler.containsKey(u)) {
            // Eski yedeğin üzerine ASLA yazılmaz (eşya kaybolur): önce o geri verilmeli
            plugin.getLogger().warning("[Yedek] " + p.getName() + " için zaten bekleyen bir yedek var, yeni yedek alınmadı.");
            return false;
        }
        if (kitAdi == null || !kit().adlar().contains(kitAdi)) return false;
        p.closeInventory(); // İmleçteki eşya envantere dönsün
        ItemStack[] icerik = p.getInventory().getContents();
        ItemStack[] kopya = new ItemStack[icerik.length];
        for (int i = 0; i < icerik.length; i++) {
            if (icerik[i] != null && !kit().kitEsyasiMi(icerik[i])) kopya[i] = icerik[i].clone();
        }
        yedekler.put(u, new Yedek(p.getName(), kopya, Konum.yaz(donus), System.currentTimeMillis()));
        if (!dosya.hemenKaydet()) {
            yedekler.remove(u);
            plugin.getLogger().severe("[Yedek] " + p.getName() + " envanteri diske yazılamadı; maça alınmadı, envanterine dokunulmadı.");
            return false;
        }
        p.getInventory().clear();
        kit().ver(p, kitAdi);
        aktif.add(u);
        p.saveData(); // Diskteki oyuncu verisi de kitli hale gelsin (çökmede asıl eşyalar hem yedekte hem oyuncuda kalmasın)
        return true;
    }

    /**
     * Kit eşyalarını siler ve yedeği geri verir.
     * @param isinla true ise maç öncesi konuma ışınlar
     * @return yedek vardıysa true
     */
    public boolean geriVer(Player p, boolean isinla) {
        UUID u = p.getUniqueId();
        aktif.remove(u);
        Yedek y = yedekler.get(u);
        p.closeInventory();
        kit().temizle(p);
        if (y == null) return false;
        p.getInventory().clear();
        ItemStack[] icerik = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < Math.min(icerik.length, y.icerik().length); i++) icerik[i] = y.icerik()[i] == null ? null : y.icerik()[i].clone();
        p.getInventory().setContents(icerik);
        p.updateInventory();
        // Sıra önemli: önce oyuncu verisi diske, sonra yedek silinir. Arada çökerse girişte aynı yedek
        // yeniden verilir (envanter önce temizlendiği için kopyalanmaz); tersi olsaydı eşya kaybolabilirdi.
        p.saveData();
        yedekler.remove(u);
        dosya.hemenKaydet();
        if (isinla) {
            Location donus = Konum.oku(y.donus());
            if (donus == null) donus = Bukkit.getWorlds().get(0).getSpawnLocation();
            p.teleport(donus, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return true;
    }

    /** Sunucu kapanırken: kit kullanan herkesin eşyası geri verilir. */
    public void hepsiniGeriVer() {
        for (UUID u : new HashSet<>(aktif)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) geriVer(p, true);
        }
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Diğer eklentilerin giriş işlemlerinden sonra
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || aktif.contains(p.getUniqueId())) return;
            int silinen = kit().temizle(p);
            if (yedekler.containsKey(p.getUniqueId())) {
                geriVer(p, true);
                m().gonder(p, "yedek-geri-verildi", "&aYarıda kalan maçtan önceki eşyalarınız geri verildi.");
                plugin.getLogger().info("[Yedek] " + p.getName() + " girişte envanter yedeğini geri aldı.");
            } else if (silinen > 0) {
                plugin.getLogger().warning("[Yedek] " + p.getName() + " üzerinde " + silinen + " kit eşyası vardı, silindi.");
            }
        });
    }

    /** Kit kullanırken çıkan oyuncu eşyalarını geri alarak çıkar (oyuncu verisi asıl eşyalarla kaydedilir). */
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        if (aktif.contains(event.getPlayer().getUniqueId())) geriVer(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!aktif.contains(event.getEntity().getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (aktif.contains(event.getPlayer().getUniqueId()) || kit().kitEsyasiMi(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && aktif.contains(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (aktif.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** Her ihtimale karşı: kit eşyası yere düşemez. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (kit().kitEsyasiMi(event.getEntity().getItemStack())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ ADMIN
    /** /arena admin test kit [ad] | test bitir — kendi üzerinde kit/yedek denemesi (çökme testi için). */
    public void test(Player p, String[] args) {
        String islem = args.length > 2 ? args[2].toLowerCase() : "";
        if (islem.equals("kit")) {
            if (kit().bosMu()) { m().gonder(p, "kit-liste-bos", "&7Kit havuzu boş. Eklemek için: &f/arena admin kit ekle <ad>"); return; }
            String ad = args.length > 3 ? args[3].toLowerCase() : kit().rastgele();
            if (!kit().adlar().contains(ad)) { m().gonder(p, "kit-yok", "&cBöyle bir kit yok."); return; }
            if (kitVer(p, ad, p.getLocation())) {
                m().gonder(p, "test-kit", "&aTest: eşyaların yedeklendi, &f{kit} &akiti verildi. Bitirmek için: &f/arena admin test bitir", "kit", ad);
            } else {
                m().gonder(p, "test-kit-olmadi", "&cKit verilemedi (bekleyen yedek var ya da yedek diske yazılamadı). Konsola bakın.");
            }
        } else if (islem.equals("bitir")) {
            if (geriVer(p, true)) m().gonder(p, "test-bitti", "&aTest bitti: eşyaların geri verildi.");
            else m().gonder(p, "test-yedek-yok", "&eBekleyen yedeğiniz yok.");
        } else {
            m().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin test <kit [ad]|bitir>");
        }
    }

    /** /arena admin yedek liste | yedek geriver <oyuncu> */
    public void yedekKomut(org.bukkit.command.CommandSender s, String[] args) {
        String islem = args.length > 2 ? args[2].toLowerCase() : "liste";
        if (islem.equals("geriver") && args.length > 3) {
            Player p = Bukkit.getPlayerExact(args[3]);
            if (p == null) { m().gonder(s, "oyuncu-yok", "&cOyuncu çevrimiçi değil (girişte otomatik geri verilir)."); return; }
            if (geriVer(p, true)) m().gonder(s, "yedek-verildi", "&a{oyuncu} oyuncusunun yedeği geri verildi.", "oyuncu", p.getName());
            else m().gonder(s, "test-yedek-yok", "&eBekleyen yedeğiniz yok.");
            return;
        }
        if (yedekler.isEmpty()) { m().gonder(s, "yedek-liste-bos", "&7Bekleyen envanter yedeği yok."); return; }
        m().gonder(s, "yedek-liste", "&6Bekleyen yedekler ({sayi}): &f{liste}", "sayi", yedekler.size(),
                "liste", String.join(", ", bekleyenler().values()));
    }
}
