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
    private double hazineOduluMax() { return plugin.getConfig().getDouble("hazine.odul-max", 15000); }
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
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        Bukkit.broadcastMessage(Mesaj.renk("&6&lHAZİNE BULUNDU! &e" + p.getName() + " &7hazineyi buldu."));
        hazine = null; // Yeni hazine için temiz sayfa; sonraki otomatik zamanlamayı tick() belirler
    }

    // ------------------------------------------------------------------ YAML
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
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
