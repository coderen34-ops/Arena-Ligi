package me.arenaligi.etkinlik;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.YamlDosya;

/**
 * Zindan/Boss + Hazine Avı (Faz 5, Entry #10). Aynı dosya/zamanlayıcı altyapısını paylaşırlar.
 *
 * Zindan: admin, inşa edilmiş bir zindan/boss odasında /arena admin zindan kur ile BİR boss mob'u
 * kaydeder. Boss öldürülünce (hasar katkısına göre ödül dağıtılır) o lokasyon kalıcı olarak
 * "temizlendi" sayılır - bir daha boss doğmaz (diske yazılır, restart'ta kaybolmaz; dupe önlemi).
 * Bulma/duyuru sunucu geneline otomatik yapılmaz - bulan kişi isterse kendi klanını/arkadaşlarını çağırır.
 *
 * Hazine Avı: periyodik olarak (config'teki aralıkla) ya da admin elle (/arena admin hazine baslat)
 * haritada rastgele bir yüzey noktasında bir sandık belirir; koordinat basit sürüm olarak doğrudan
 * duyurulur. İlk kıran ödülü alır, sandık tek seferliktir.
 */
public class EtkinlikYonetici implements Listener {

    private static class Zindan {
        String isim;
        Location konum;
        int seviye;
        boolean temizlendi;
        UUID bossUuid;
        final Map<UUID, Double> hasarKatkisi = new LinkedHashMap<>(); // runtime-only, restart'ta sıfırlanır
    }

    private static class Hazine {
        Location konum;
        boolean acildi;
    }

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private final NamespacedKey bossKey;

    private final Map<String, Zindan> zindanlar = new LinkedHashMap<>();
    private Hazine hazine; // Aynı anda en fazla 1 aktif hazine

    private long sonrakiHazineZamani;

    public EtkinlikYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "zindan-boss-id");
        this.dosya = new YamlDosya(plugin, "etkinlikler.yml", this::olustur);
        this.sonrakiHazineZamani = System.currentTimeMillis() + hazineAralikMs(); // yukle() geçerli bir kayıtlı zamanla ezebilir
        yukle();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); // 10 sn'de bir
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR
    private long hazineAralikMs() { return (long) (plugin.getConfig().getDouble("hazine.aralik-saat", 4) * 3_600_000L); }
    private double hazineOduluMin() { return plugin.getConfig().getDouble("hazine.odul-min", 5000); }
    private double hazineOduluMax() { return plugin.getConfig().getDouble("hazine.odul-max", 15000); }
    private int hazineMerkezYaricap() { return plugin.getConfig().getInt("hazine.yaricap", 500); }
    private String hazineDunyaAdi() { return plugin.getConfig().getString("hazine.dunya", "world"); }
    private double zindanCan(int seviye) { return plugin.getConfig().getDouble("zindan.seviye-" + seviye + ".can", 100 + seviye * 50); }
    private double zindanHasar(int seviye) { return plugin.getConfig().getDouble("zindan.seviye-" + seviye + ".hasar", 8 + seviye * 2); }
    private double zindanOduluMin(int seviye) { return plugin.getConfig().getDouble("zindan.seviye-" + seviye + ".odul-min", 8000 * seviye); }
    private double zindanOduluMax(int seviye) { return plugin.getConfig().getDouble("zindan.seviye-" + seviye + ".odul-max", 20000 * seviye); }

    // ------------------------------------------------------------------ KOMUT
    public void zindanKomut(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan <kur <seviye 1-5>|sil <isim>|liste>")); return; }
        String alt = args[2].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur" -> zindanKur(s, args);
            case "sil" -> zindanSil(s, args);
            case "liste" -> zindanListe(s);
            default -> s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan <kur <seviye 1-5>|sil <isim>|liste>"));
        }
    }

    public void hazineKomut(CommandSender s, String[] args) {
        if (args.length < 3) { hazineDurum(s); return; }
        if (args[2].equalsIgnoreCase("baslat")) hazineBaslat(s);
        else hazineDurum(s);
    }

    private void zindanKur(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage(Mesaj.renk("&cBu komut sadece oyun içinden kullanılabilir.")); return; }
        if (args.length < 4) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan kur <seviye 1-5>")); return; }
        int seviye;
        try { seviye = Integer.parseInt(args[3]); } catch (NumberFormatException e) { s.sendMessage(Mesaj.renk("&cGeçersiz seviye.")); return; }
        if (seviye < 1 || seviye > 5) { p.sendMessage(Mesaj.renk("&cSeviye 1-5 arasında olmalı.")); return; }

        Zindan z = new Zindan();
        z.isim = "Zindan-" + (zindanlar.size() + 1);
        z.konum = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        z.seviye = seviye;
        Ravager boss = z.konum.getWorld().spawn(z.konum, Ravager.class);
        boss.setPersistent(true);
        boss.setRemoveWhenFarAway(false);
        boss.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, z.isim);
        boss.setCustomName(ChatColor.DARK_RED + z.isim + " Lideri (Zindan Boss'u)");
        boss.setCustomNameVisible(true);
        AttributeInstance can = boss.getAttribute(Attribute.MAX_HEALTH);
        if (can != null) can.setBaseValue(zindanCan(seviye));
        boss.setHealth(zindanCan(seviye));
        AttributeInstance hasar = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (hasar != null) hasar.setBaseValue(zindanHasar(seviye));
        z.bossUuid = boss.getUniqueId();
        zindanlar.put(z.isim, z);
        kaydet();
        p.sendMessage(Mesaj.renk("&a" + z.isim + " kuruldu (seviye " + seviye + "). Boss doğdu. Öldürülünce hasar katkısına göre ödül dağıtılır, bu lokasyon bir daha dolmaz."));
    }

    private void zindanSil(CommandSender s, String[] args) {
        if (args.length < 4) { s.sendMessage(Mesaj.renk("&cKullanım: &e/arena admin zindan sil <isim>")); return; }
        Zindan z = zindanlar.remove(args[3]);
        if (z == null) { s.sendMessage(Mesaj.renk("&cBöyle bir zindan yok.")); return; }
        if (z.bossUuid != null) {
            Entity e = Bukkit.getEntity(z.bossUuid);
            if (e != null) e.remove();
        }
        kaydet();
        s.sendMessage(Mesaj.renk("&a" + z.isim + " silindi."));
    }

    private void zindanListe(CommandSender s) {
        if (zindanlar.isEmpty()) { s.sendMessage(Mesaj.renk("&7Hiç zindan yok. &e/arena admin zindan kur <seviye>")); return; }
        s.sendMessage(Mesaj.renk("&6&l--- ZİNDANLAR ---"));
        for (Zindan z : zindanlar.values()) {
            s.sendMessage(Mesaj.renk("&e" + z.isim + " &7(seviye " + z.seviye + ") - " + (z.temizlendi ? "&cTemizlendi" : "&aAktif")));
        }
    }

    private void hazineDurum(CommandSender s) {
        if (hazine == null) s.sendMessage(Mesaj.renk("&7Şu an aktif bir hazine yok. Sonraki otomatik hazine: &e~" + ((sonrakiHazineZamani - System.currentTimeMillis()) / 60000) + " dk sonra. &7(&e/arena admin hazine baslat&7 ile hemen başlat)"));
        else s.sendMessage(Mesaj.renk("&6Aktif hazine: &f" + hazine.konum.getWorld().getName() + " " + hazine.konum.getBlockX() + ", " + hazine.konum.getBlockY() + ", " + hazine.konum.getBlockZ()
                + " &7- " + (hazine.acildi ? "&cAçıldı" : "&aBekliyor")));
    }

    private void hazineBaslat(CommandSender s) {
        if (hazine != null && !hazine.acildi) { s.sendMessage(Mesaj.renk("&cZaten aktif bir hazine var.")); return; }
        hazineDogur();
        s.sendMessage(Mesaj.renk("&aHazine başlatıldı."));
    }

    // ------------------------------------------------------------------ HAZİNE DOĞURMA
    private void hazineDogur() {
        World w = Bukkit.getWorld(hazineDunyaAdi());
        if (w == null) { plugin.getLogger().warning("Hazine Avı: '" + hazineDunyaAdi() + "' dünyası yüklü değil."); return; }
        java.util.Random rng = new java.util.Random();
        int yaricap = hazineMerkezYaricap();
        Location konum = null;
        for (int deneme = 0; deneme < 20; deneme++) {
            int x = rng.nextInt(yaricap * 2) - yaricap;
            int z = rng.nextInt(yaricap * 2) - yaricap;
            int y = w.getHighestBlockYAt(x, z);
            if (y <= w.getSeaLevel() + 1) continue; // Okyanus/çok alçak - tekrar dene
            konum = new Location(w, x + 0.5, y + 1, z + 0.5);
            break;
        }
        if (konum == null) { plugin.getLogger().warning("Hazine Avı: uygun bir yüzey noktası bulunamadı, bu sefer atlandı."); return; }
        konum.getBlock().setType(org.bukkit.Material.CHEST);
        Hazine h = new Hazine();
        h.konum = konum;
        hazine = h;
        kaydet();
        Bukkit.broadcastMessage(Mesaj.renk("&6&lHAZİNE AVI! &eBir hazine sandığı belirdi: &f" + w.getName() + " " + konum.getBlockX() + ", " + konum.getBlockY() + ", " + konum.getBlockZ() + " &e- ilk bulan kazanır!"));
        sonrakiHazineZamani = System.currentTimeMillis() + hazineAralikMs();
    }

    private void tick() {
        if (hazine == null && System.currentTimeMillis() >= sonrakiHazineZamani) hazineDogur();
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onZindanHasar(EntityDamageByEntityEvent event) {
        String isim = event.getEntity().getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        if (isim == null) return;
        Zindan z = zindanlar.get(isim);
        if (z == null) return;
        Player saldiran = saldiranOyuncu(event);
        if (saldiran == null) return;
        z.hasarKatkisi.merge(saldiran.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    private static Player saldiranOyuncu(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZindanOlum(EntityDeathEvent event) {
        String isim = event.getEntity().getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        if (isim == null) return;
        Zindan z = zindanlar.get(isim);
        if (z == null || z.temizlendi) return;
        z.temizlendi = true;
        kaydet();

        double toplamHasar = z.hasarKatkisi.values().stream().mapToDouble(Double::doubleValue).sum();
        if (toplamHasar <= 0) return; // Kimse hasar vermedi (örn. /kill ile), ödül dağıtılmaz
        double toplamOdul = zindanOduluMin(z.seviye) + Math.random() * (zindanOduluMax(z.seviye) - zindanOduluMin(z.seviye));
        for (Map.Entry<UUID, Double> e : z.hasarKatkisi.entrySet()) {
            double pay = Math.round(toplamOdul * (e.getValue() / toplamHasar) * 100.0) / 100.0;
            if (pay <= 0) continue;
            if (plugin.meslek().bankayaYatir(e.getKey(), pay)) {
                Player p = Bukkit.getPlayer(e.getKey());
                String isimOyuncu = p != null ? p.getName() : e.getKey().toString();
                plugin.meslek().ekonomiLog("zindan-boss-odul", isimOyuncu, z.isim + " boss ödülü +$" + pay);
                if (p != null) {
                    p.sendMessage(Mesaj.renk("&6" + z.isim + " &7boss'u düştü! Hasar katkına göre ödülün: &e$" + pay));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHazineKir(BlockBreakEvent event) {
        if (hazine == null || hazine.acildi) return;
        Block b = event.getBlock();
        if (!b.getLocation().getWorld().equals(hazine.konum.getWorld())
                || b.getX() != hazine.konum.getBlockX() || b.getY() != hazine.konum.getBlockY() || b.getZ() != hazine.konum.getBlockZ()) return;
        hazine.acildi = true;
        event.setDropItems(false);
        kaydet();
        double odul = Math.round((hazineOduluMin() + Math.random() * (hazineOduluMax() - hazineOduluMin())) * 100.0) / 100.0;
        Player p = event.getPlayer();
        if (plugin.meslek().bankayaYatir(p.getUniqueId(), odul)) {
            plugin.meslek().ekonomiLog("hazine-avi-odul", p.getName(), "Hazine Avı ödülü +$" + odul);
            p.sendMessage(Mesaj.renk("&6Hazineyi sen buldun! &eÖdül: &a$" + odul));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        Bukkit.broadcastMessage(Mesaj.renk("&6&lHAZİNE BULUNDU! &e" + p.getName() + " &7hazineyi buldu."));
        hazine = null; // Yeni hazine için temiz sayfa; sonraki otomatik zamanlamayı tick() belirler
    }

    // ------------------------------------------------------------------ YAML
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zindan z : zindanlar.values()) {
            y.set("zindanlar." + z.isim + ".konum", Konum.yaz(z.konum));
            y.set("zindanlar." + z.isim + ".seviye", z.seviye);
            y.set("zindanlar." + z.isim + ".temizlendi", z.temizlendi);
            y.set("zindanlar." + z.isim + ".boss-uuid", z.bossUuid == null ? null : z.bossUuid.toString());
        }
        if (hazine != null) {
            y.set("hazine.konum", Konum.yaz(hazine.konum));
            y.set("hazine.acildi", hazine.acildi);
        }
        y.set("sonraki-hazine-zamani", sonrakiHazineZamani);
        return y;
    }

    private void kaydet() { dosya.kaydet(); }

    private void yukle() {
        YamlConfiguration y = dosya.oku();
        ConfigurationSection zs = y.getConfigurationSection("zindanlar");
        if (zs != null) {
            for (String isim : zs.getKeys(false)) {
                ConfigurationSection c = zs.getConfigurationSection(isim);
                if (c == null) continue;
                Zindan z = new Zindan();
                z.isim = isim;
                z.konum = Konum.oku(c.getString("konum"));
                z.seviye = c.getInt("seviye", 1);
                z.temizlendi = c.getBoolean("temizlendi", false);
                String bossStr = c.getString("boss-uuid");
                z.bossUuid = bossStr == null ? null : UUID.fromString(bossStr);
                if (z.konum == null) {
                    plugin.getLogger().warning("'" + isim + "' zindanının dünyası yüklü değil, şimdilik atlanıyor.");
                    continue;
                }
                zindanlar.put(isim, z);
            }
        }
        String hazineKonum = y.getString("hazine.konum");
        if (hazineKonum != null) {
            Location l = Konum.oku(hazineKonum);
            if (l != null) {
                Hazine h = new Hazine();
                h.konum = l;
                h.acildi = y.getBoolean("hazine.acildi", false);
                if (!h.acildi) hazine = h; // Açılmış eski hazineyi tekrar yüklemeye gerek yok
            }
        }
        long kayitliZaman = y.getLong("sonraki-hazine-zamani", 0);
        if (kayitliZaman > System.currentTimeMillis()) sonrakiHazineZamani = kayitliZaman;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }
}
