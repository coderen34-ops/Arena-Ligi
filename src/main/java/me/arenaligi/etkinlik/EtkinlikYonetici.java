package me.arenaligi.etkinlik;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.veri.YamlDosya;

/**
 * Hazine Avı (Faz 5, Entry #10'un bir parçası - diğer parçası "Zindan/Boss" artık ayrı ve çok daha
 * derin bir sistem: bkz. me.arenaligi.zindan.ZindanYoneticisi, katman katman yer altı zindanları).
 *
 * Periyodik olarak (config'teki aralıkla) ya da admin elle (/arena admin hazine baslat) haritada
 * rastgele bir yüzey noktasında bir sandık belirir; koordinat basit sürüm olarak doğrudan duyurulur.
 * İlk kıran ödülü alır, sandık tek seferliktir.
 */
public class EtkinlikYonetici implements Listener {

    private static class Hazine {
        Location konum;
        boolean acildi;
        boolean buyuk;
        String ipucu;
    }

    private final ArenaLigi plugin;
    private final YamlDosya dosya;

    private Hazine hazine; // Aynı anda en fazla 1 aktif hazine

    private long sonrakiHazineZamani;

    public EtkinlikYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "etkinlikler.yml", this::olustur);
        this.sonrakiHazineZamani = System.currentTimeMillis() + hazineAralikMs(); // yukle() geçerli bir kayıtlı zamanla ezebilir
        yukle();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); // 10 sn'de bir
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR
    private long hazineAralikMs() { return (long) (plugin.getConfig().getDouble("hazine.aralik-saat", 4) * 3_600_000L); }
    private double hazineOduluMin() { return plugin.getConfig().getDouble("hazine.odul-min", 5000); }
    private double hazineOduluMax() { return Math.min(10000, plugin.getConfig().getDouble("hazine.odul-max", 10000)); } // normal hazine tavanı $10.000
    private double buyukHazineOdulu() { return plugin.getConfig().getDouble("hazine.buyuk-odul", 15000); }
    private double buyukHazineSansi() { return plugin.getConfig().getDouble("hazine.buyuk-sans", 0.12); }
    private int buyukHazineMinMesafe() { return plugin.getConfig().getInt("hazine.buyuk-min-mesafe", 10000); }
    private long yenidenUretimMs() { return plugin.getConfig().getLong("hazine.yeniden-dakika", 30) * 60_000L; }
    private int hazineMerkezYaricap() { return plugin.getConfig().getInt("hazine.yaricap", 500); }
    private String hazineDunyaAdi() { return plugin.getConfig().getString("hazine.dunya", "world"); }

    // ------------------------------------------------------------------ KOMUT
    public void hazineKomut(CommandSender s, String[] args) {
        if (args.length < 3) { hazineDurum(s); return; }
        if (args[2].equalsIgnoreCase("baslat")) hazineBaslat(s);
        else hazineDurum(s);
    }

    private void hazineDurum(CommandSender s) {
        if (hazine == null) s.sendMessage(Mesaj.renk("&7Şu an aktif bir hazine yok. Sonraki otomatik hazine: &e~" + ((sonrakiHazineZamani - System.currentTimeMillis()) / 60000) + " dk sonra. &7(&e/arena admin hazine baslat&7 ile hemen başlat)"));
        else s.sendMessage(Mesaj.renk("&6Aktif hazine" + (hazine.buyuk ? " &d(BÜYÜK)" : "") + ": &f" + hazine.konum.getWorld().getName() + " " + hazine.konum.getBlockX() + ", " + hazine.konum.getBlockY() + ", " + hazine.konum.getBlockZ()
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
        boolean buyuk = rng.nextDouble() < buyukHazineSansi();
        Location merkez = w.getSpawnLocation();
        int yaricap = hazineMerkezYaricap();
        Location konum = null;
        for (int deneme = 0; deneme < 25 && konum == null; deneme++) {
            int x, z;
            if (buyuk) { // spawn'dan en az ~10.000 blok uzakta, yeryüzünde
                double aci = rng.nextDouble() * Math.PI * 2;
                double mesafe = buyukHazineMinMesafe() + rng.nextDouble() * 2500;
                x = (int) (merkez.getX() + Math.cos(aci) * mesafe);
                z = (int) (merkez.getZ() + Math.sin(aci) * mesafe);
                double sinir = w.getWorldBorder().getSize() / 2.0;
                if (Math.abs(x - w.getWorldBorder().getCenter().getX()) > sinir - 10 || Math.abs(z - w.getWorldBorder().getCenter().getZ()) > sinir - 10) continue;
            } else {
                x = rng.nextInt(yaricap * 2) - yaricap;
                z = rng.nextInt(yaricap * 2) - yaricap;
            }
            int y = w.getHighestBlockYAt(x, z);
            if (y <= w.getSeaLevel() + 1) continue; // Okyanus/çok alçak - tekrar dene
            konum = new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        if (konum == null && buyuk) { buyuk = false; sonrakiHazineZamani = System.currentTimeMillis() + 60_000L; return; } // uygun uzak nokta yok: bir dk sonra (normal) tekrar dener
        if (konum == null) { plugin.getLogger().warning("Hazine Avı: uygun bir yüzey noktası bulunamadı, bu sefer atlandı."); return; }
        konum.getBlock().setType(org.bukkit.Material.CHEST);
        Hazine h = new Hazine();
        h.konum = konum;
        h.buyuk = buyuk;
        // Tam koordinat DEĞİL, hazineyi içeren (rastgele kaydırılmış) bir aralık; büyük hazinede aralık çok daha geniş
        int genislik = buyuk ? 4000 : 1000;
        int ofsetX = rng.nextInt(genislik - 100) + 50, ofsetZ = rng.nextInt(genislik - 100) + 50;
        int x0 = konum.getBlockX() - ofsetX, z0 = konum.getBlockZ() - ofsetZ;
        h.ipucu = "x: " + x0 + " ile " + (x0 + genislik) + " arası, z: " + z0 + " ile " + (z0 + genislik) + " arası";
        hazine = h;
        kaydet();
        if (buyuk) Bukkit.broadcastMessage(Mesaj.renk("&d&lBÜYÜK HAZİNE! &eÇok uzaklarda nadir bir hazine belirdi ($" + (int) buyukHazineOdulu() + "). &7Konum aralığı: &f" + w.getName() + " " + h.ipucu));
        else Bukkit.broadcastMessage(Mesaj.renk("&6&lHAZİNE AVI! &eBir hazine sandığı belirdi. &7Konum aralığı: &f" + w.getName() + " " + h.ipucu + " &e- ilk bulan kazanır!"));
        sonrakiHazineZamani = System.currentTimeMillis() + hazineAralikMs();
    }

    private void tick() {
        if (hazine == null && System.currentTimeMillis() >= sonrakiHazineZamani) hazineDogur();
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHazineKir(BlockBreakEvent event) {
        if (hazine == null || hazine.acildi) return;
        Block b = event.getBlock();
        if (!b.getLocation().getWorld().equals(hazine.konum.getWorld())
                || b.getX() != hazine.konum.getBlockX() || b.getY() != hazine.konum.getBlockY() || b.getZ() != hazine.konum.getBlockZ()) return;
        hazine.acildi = true;
        event.setDropItems(false);
        kaydet();
        double odul = hazine.buyuk ? buyukHazineOdulu()
                : Math.min(10000.0, Math.round((hazineOduluMin() + Math.random() * (hazineOduluMax() - hazineOduluMin())) * 100.0) / 100.0);
        Player p = event.getPlayer();
        if (plugin.meslek().bankayaYatir(p.getUniqueId(), odul)) {
            plugin.meslek().ekonomiLog("hazine-avi-odul", p.getName(), "Hazine Avı ödülü +$" + odul);
            p.sendMessage(Mesaj.renk("&6Hazineyi sen buldun! &eÖdül: &a$" + odul));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        Bukkit.broadcastMessage(Mesaj.renk("&6&lHAZİNE BULUNDU! &e" + p.getName() + " &7hazineyi buldu."));
        hazine = null;
        sonrakiHazineZamani = System.currentTimeMillis() + yenidenUretimMs(); // bulunduktan ~yarım saat sonra yenisi yeni konumda
        kaydet();
    }

    // ------------------------------------------------------------------ YAML
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        if (hazine != null) {
            y.set("hazine.konum", Konum.yaz(hazine.konum));
            y.set("hazine.acildi", hazine.acildi);
            y.set("hazine.buyuk", hazine.buyuk);
            y.set("hazine.ipucu", hazine.ipucu);
        }
        y.set("sonraki-hazine-zamani", sonrakiHazineZamani);
        return y;
    }

    private void kaydet() { dosya.kaydet(); }

    private void yukle() {
        YamlConfiguration y = dosya.oku();
        String hazineKonum = y.getString("hazine.konum");
        if (hazineKonum != null) {
            Location l = Konum.oku(hazineKonum);
            if (l != null) {
                Hazine h = new Hazine();
                h.konum = l;
                h.acildi = y.getBoolean("hazine.acildi", false);
                h.buyuk = y.getBoolean("hazine.buyuk", false);
                h.ipucu = y.getString("hazine.ipucu");
                if (!h.acildi) hazine = h; // Açılmış eski hazineyi tekrar yüklemeye gerek yok
            }
        }
        long kayitliZaman = y.getLong("sonraki-hazine-zamani", 0);
        if (kayitliZaman > System.currentTimeMillis()) sonrakiHazineZamani = kayitliZaman;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }
}
