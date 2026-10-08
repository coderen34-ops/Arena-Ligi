package me.arenaligi.bahis;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.mac.Mac;
import me.arenaligi.mac.MacYonetici;
import me.arenaligi.veri.YamlDosya;

/**
 * Havuz bahsi (para yaratılmaz):
 * - Bahis sadece maçın bekleme penceresinde (BEKLEME) oynanır, anında bankadan çekilip emanete alınır (bahisler.yml).
 * - Maç biterse havuzdan %komisyon düşülür; kalan, kazanan tarafa oynayanlara bahisleri oranında dağıtılır.
 *   Komisyonun "komisyon-kazanan-payi" kadarı kazanan dövüşçüye, kalanı belediye kasasına gider
 *   (kasa kabul etmezse o pay bahis sahiplerine oransal iade edilir).
 * - Berabere / iptal / tek tarafa bahis varsa: herkese tam iade.
 * - Sunucu çökerse açılışta emanetteki bahisler iade edilir.
 * - Dövüşçü kendi maçına oynayamaz; dövüşçüyle aynı IP'den bahis engellenir ve loglanır.
 */
public class BahisYonetici {

    /** Bir oyuncunun bu maçtaki bahsi (tek tarafa). */
    public static class Bahis {
        public final UUID oyuncu;
        public final String isim;
        public final int takim;
        public double miktar;

        Bahis(UUID oyuncu, String isim, int takim, double miktar) {
            this.oyuncu = oyuncu;
            this.isim = isim;
            this.takim = takim;
            this.miktar = miktar;
        }
    }

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private String macId;                                         // Bahislerin ait olduğu maç (null = pencere kapalı ve emanet boş)
    private boolean acik = false;
    private final Map<UUID, Bahis> bahisler = new LinkedHashMap<>();
    private final Map<UUID, double[]> gunluk = new HashMap<>();  // oyuncu -> {gün, toplam}

    public BahisYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "bahisler.yml", this::olustur);
        kurtar();
    }

    private Mesaj m() { return plugin.mesaj(); }
    private static String para(double d) { return MacYonetici.para(d); }
    private static double kurus(double d) { return Math.round(d * 100.0) / 100.0; }

    private double c(String yol, double v) {
        double d = plugin.getConfig().getDouble("bahis." + yol, v);
        return Double.isFinite(d) && d >= 0 ? d : v;
    }
    private double min() { return c("min", 50); }
    private double macMax() { return c("max-mac", 5000); }
    private double gunlukMax() { return c("gunluk-max", 20000); }
    private double komisyon() { return Math.min(1.0, c("komisyon", 0.10)); }
    private double komisyonKazananPayi() { return Math.min(1.0, c("komisyon-kazanan-payi", 0.5)); }

    public boolean acikMi() { return acik; }

    public double takimToplami(int takim) {
        double t = 0;
        for (Bahis b : bahisler.values()) if (b.takim == takim) t += b.miktar;
        return kurus(t);
    }

    public int takimKisi(int takim) {
        int n = 0;
        for (Bahis b : bahisler.values()) if (b.takim == takim) n++;
        return n;
    }

    public double havuz() { return kurus(takimToplami(0) + takimToplami(1)); }

    // ------------------------------------------------------------------ PENCERE
    public void ac(Mac mac) {
        macId = mac.id;
        acik = true;
        bahisler.clear();
        dosya.hemenKaydet();
    }

    public void kapat(Mac mac) {
        if (!acik) return;
        acik = false;
        dosya.kaydet();
        if (bahisler.isEmpty()) return;
        duyur("bahis-kapandi", "&6Bahisler kapandı! &f{a}: &e${ta} &7({na} kişi) &8| &f{b}: &e${tb} &7({nb} kişi) &8| &7Havuz: &e${havuz}",
                "a", lakap(mac, 0), "ta", para(takimToplami(0)), "na", takimKisi(0),
                "b", lakap(mac, 1), "tb", para(takimToplami(1)), "nb", takimKisi(1), "havuz", para(havuz()));
    }

    private static String lakap(Mac mac, int takim) {
        List<String> l = new ArrayList<>();
        for (UUID u : mac.takimlar.get(takim)) l.add(mac.lakaplar.getOrDefault(u, "?"));
        return String.join(" & ", l);
    }

    // ------------------------------------------------------------------ BAHİS OYNA
    /** /arena bahis <dövüşçü> <miktar> ya da NPC menüsünden. Başarılıysa true. */
    public boolean oyna(Player p, int takim, double miktar) {
        Mac mac = plugin.mac().aktifMac();
        UUID u = p.getUniqueId();
        if (mac == null || !acik || !mac.id.equals(macId)) {
            m().gonder(p, "bahis-kapali", "&cŞu an bahis penceresi açık değil (maç öncesi bekleme sırasında açılır).");
            return false;
        }
        if (takim < 0 || takim >= mac.takimlar.size()) return false;
        if (mac.oyuncuMu(u)) { m().gonder(p, "bahis-kendi", "&cKendi maçınıza bahis oynayamazsınız."); return false; }
        if (!plugin.seyirci().seyirciMi(u)) {
            m().gonder(p, "bahis-seyirci-degil", "&cBahis oynamak için önce tribüne geçip izleyici olmalısınız: &f/arena izle");
            return false;
        }
        if (!Double.isFinite(miktar) || miktar <= 0) { m().gonder(p, "gecersiz-sayi", "&cGeçersiz sayı."); return false; }
        miktar = kurus(miktar);
        Bahis onceki = bahisler.get(u);
        if (onceki != null && onceki.takim != takim) {
            m().gonder(p, "bahis-tek-taraf", "&cBu maçta zaten diğer tarafa oynadınız; sadece aynı tarafa ekleme yapabilirsiniz.");
            return false;
        }
        double mevcut = onceki == null ? 0 : onceki.miktar;
        if (miktar < min()) { m().gonder(p, "bahis-min", "&cEn az bahis ${min}.", "min", para(min())); return false; }
        if (mevcut + miktar > macMax()) {
            m().gonder(p, "bahis-max", "&cBir maçta en fazla ${max} oynayabilirsiniz (şu an ${mevcut}).", "max", para(macMax()), "mevcut", para(mevcut));
            return false;
        }
        long bugun = LocalDate.now().toEpochDay();
        double[] g = gunluk.get(u);
        double bugunToplam = g != null && (long) g[0] == bugun ? g[1] : 0;
        if (bugunToplam + miktar > gunlukMax()) {
            m().gonder(p, "bahis-gunluk", "&cGünlük bahis sınırınız ${max} (bugün ${bugun} oynadınız).", "max", para(gunlukMax()), "bugun", para(bugunToplam));
            return false;
        }
        String ipSorunu = ayniIp(p, mac);
        if (ipSorunu != null) {
            plugin.meslek().ekonomiLog("arena-bahis", p.getName(), "ENGELLENDİ (aynı IP: " + ipSorunu + ") maç " + mac.id + " $" + para(miktar));
            uyar(m().metin("bahis-ip-uyari", "&c[Arena] {oyuncu} dövüşçü {dovuscu} ile aynı IP'den bahis oynamaya çalıştı (maç {mac}).",
                    "oyuncu", p.getName(), "dovuscu", ipSorunu, "mac", mac.id));
            m().gonder(p, "bahis-ip", "&cBu maçtaki bir dövüşçüyle aynı bağlantıdan bahis oynayamazsınız.");
            return false;
        }
        if (!plugin.meslek().bankadanCek(u, miktar)) {
            m().gonder(p, "bahis-para-yok", "&cBanka hesabınızda ${miktar} yok.", "miktar", para(miktar));
            return false;
        }
        if (onceki == null) bahisler.put(u, new Bahis(u, p.getName(), takim, miktar));
        else onceki.miktar = kurus(onceki.miktar + miktar);
        gunluk.put(u, new double[]{bugun, bugunToplam + miktar});
        plugin.meslek().ekonomiLog("arena-bahis", p.getName(), "Maç " + mac.id + " -> " + lakap(mac, takim) + " -$" + para(miktar) + " (emanete)");
        // Önce banka, sonra emanet kaydı diske: çökmede iade edilecek bahis bankadan düşülmüş haliyle kayıtlı olur
        plugin.meslek().bankayiHemenKaydet();
        dosya.hemenKaydet();
        m().gonder(p, "bahis-alindi", "&a{dovuscu} için ${miktar} bahis oynadınız. &7(Bu maçta toplam ${toplam}, havuz ${havuz})",
                "dovuscu", lakap(mac, takim), "miktar", para(miktar), "toplam", para(mevcut + miktar), "havuz", para(havuz()));
        return true;
    }

    /** Bahisçi, maçtaki bir dövüşçüyle aynı IP'deyse o dövüşçünün adı. */
    private String ayniIp(Player p, Mac mac) {
        if (!plugin.getConfig().getBoolean("bahis.ayni-ip-engel", true)) return null;
        String ip = ip(p);
        if (ip == null) return null;
        for (UUID u : mac.oyuncular()) {
            Player d = Bukkit.getPlayer(u);
            if (d != null && ip.equals(ip(d))) return d.getName();
        }
        return null;
    }

    private static String ip(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    private void uyar(String mesaj) {
        plugin.getLogger().warning(org.bukkit.ChatColor.stripColor(mesaj));
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("arena.admin")) o.sendMessage(mesaj);
    }

    // ------------------------------------------------------------------ SONUÇ
    /**
     * Maç bitince çağrılır. kazanan: takım sırası, -1 berabere/iptal. Para hareketlerini yapar;
     * diske yazmak çağıranın işidir (önce banka, sonra hemenKaydet).
     */
    public void sonuclandir(Mac mac, int kazanan) {
        if (macId == null || !macId.equals(mac.id)) return;
        acik = false;
        if (bahisler.isEmpty()) { temizle(); return; }
        double kazananToplam = kazanan < 0 ? 0 : takimToplami(kazanan);
        double kaybedenToplam = kazanan < 0 ? 0 : kurus(havuz() - kazananToplam);
        if (kazanan < 0 || kazananToplam <= 0 || kaybedenToplam <= 0) {
            for (Bahis b : bahisler.values()) ode(b.oyuncu, b.isim, b.miktar, "arena-bahis", "İADE maç " + mac.id);
            duyur("bahis-iade", "&eBahisler iade edildi &7({sebep}).", "sebep",
                    kazanan < 0 ? m().metin("bahis-iade-berabere", "berabere/iptal") : m().metin("bahis-iade-tek-taraf", "karşı tarafa bahis yok"));
            temizle();
            return;
        }
        double havuz = havuz();
        double komisyon = kurus(havuz * komisyon());
        double dagitilacak = kurus(havuz - komisyon);
        double dovuscuPayi = kurus(komisyon * komisyonKazananPayi());
        double kasaPayi = kurus(komisyon - dovuscuPayi);

        // Kasa payı: belediye kasası kabul etmezse bahis sahiplerine (hepsine) oransal iade
        Map<UUID, Double> kasaIadesi = new HashMap<>();
        if (kasaPayi > 0) {
            if (plugin.meslek().kasayaYatir(kasaPayi)) {
                plugin.meslek().ekonomiLog("arena-komisyon", "Maç " + mac.id, "Bahis komisyonu belediye kasasına +$" + para(kasaPayi));
            } else {
                kasaIadesi = oransalDagit(new ArrayList<>(bahisler.values()), havuz, kasaPayi);
                plugin.meslek().ekonomiLog("arena-komisyon", "Maç " + mac.id, "Kasa kabul etmedi; $" + para(kasaPayi) + " bahis sahiplerine oransal iade");
            }
        }
        // Dövüşçü payı
        List<UUID> kazananlar = mac.takimlar.get(kazanan);
        double kisiBasi = kurus(dovuscuPayi / kazananlar.size());
        for (UUID u : kazananlar) ode(u, mac.isimler.get(u), kisiBasi, "arena-komisyon", "Maç " + mac.id + " bahis komisyonu kazanan dövüşçü payı");
        // Havuz dağıtımı: kazanan tarafa oynayanlara bahisleri oranında
        List<Bahis> kazanBahis = new ArrayList<>();
        for (Bahis b : bahisler.values()) if (b.takim == kazanan) kazanBahis.add(b);
        Map<UUID, Double> pay = oransalDagit(kazanBahis, kazananToplam, dagitilacak);
        for (Bahis b : bahisler.values()) {
            double kazanc = pay.getOrDefault(b.oyuncu, 0.0);
            double iade = kasaIadesi.getOrDefault(b.oyuncu, 0.0);
            if (kazanc > 0) ode(b.oyuncu, b.isim, kazanc, "arena-bahis", "KAZANÇ maç " + mac.id + " (oynanan $" + para(b.miktar) + ")");
            if (iade > 0) ode(b.oyuncu, b.isim, iade, "arena-bahis", "Komisyon kasa payı iadesi maç " + mac.id);
            Player p = Bukkit.getPlayer(b.oyuncu);
            if (p == null) continue;
            if (kazanc > 0) m().gonder(p, "bahis-kazandin", "&a&lBahsi kazandınız! &a+${kazanc} &7(oynadığınız ${miktar})", "kazanc", para(kazanc + iade), "miktar", para(b.miktar));
            else m().gonder(p, "bahis-kaybettin", "&cBahsi kaybettiniz (${miktar}).", "miktar", para(b.miktar));
        }
        duyur("bahis-sonuc", "&6Bahis havuzu ${havuz} dağıtıldı: &f{n} kazanan &7(komisyon ${kom})", "havuz", para(havuz), "n", kazanBahis.size(), "kom", para(komisyon));
        temizle();
    }

    /**
     * toplam'ı bahislere miktarları oranında kuruşu kuruşuna böler (en büyük küsurat yöntemi): herkes payının
     * aşağı yuvarlanmışını alır, artan kuruşlar küsuratı en büyük olanlara birer birer verilir. Dağıtılan toplam
     * tam olarak "toplam"dır; para yaratılmaz, kaybolmaz.
     */
    private static Map<UUID, Double> oransalDagit(List<Bahis> liste, double payda, double toplam) {
        Map<UUID, Double> sonuc = new HashMap<>();
        if (liste.isEmpty() || payda <= 0 || toplam <= 0) return sonuc;
        long toplamKurus = Math.round(toplam * 100), dagitilan = 0;
        long paydaKurus = Math.round(payda * 100);
        Map<UUID, Long> kurusPay = new HashMap<>();
        Map<UUID, Long> kusurat = new HashMap<>();
        for (Bahis b : liste) {
            // Tam sayı aritmetiği: pay = toplam * bahis / payda (kuruş cinsinden)
            long bahisKurus = Math.round(b.miktar * 100);
            long carpim = Math.multiplyExact(toplamKurus, bahisKurus);
            kurusPay.put(b.oyuncu, carpim / paydaKurus);
            kusurat.put(b.oyuncu, carpim % paydaKurus);
            dagitilan += carpim / paydaKurus;
        }
        List<Bahis> sirali = new ArrayList<>(liste);
        sirali.sort((x, y) -> Long.compare(kusurat.get(y.oyuncu), kusurat.get(x.oyuncu)));
        for (int i = 0; dagitilan < toplamKurus; i = (i + 1) % sirali.size(), dagitilan++) {
            kurusPay.merge(sirali.get(i).oyuncu, 1L, Long::sum);
        }
        kurusPay.forEach((u, k) -> sonuc.put(u, k / 100.0));
        return sonuc;
    }

    private void ode(UUID u, String isim, double miktar, String kategori, String detay) {
        if (miktar <= 0) return;
        if (plugin.meslek().bankayaYatir(u, miktar)) plugin.meslek().ekonomiLog(kategori, isim != null ? isim : u.toString(), detay + " +$" + para(miktar));
        else plugin.getLogger().severe("[Para] " + u + " oyuncusuna $" + miktar + " ÖDENEMEDİ (" + detay + ")!");
    }

    private void temizle() {
        bahisler.clear();
        macId = null;
        acik = false;
    }

    public void hemenKaydet() { dosya.hemenKaydet(); }

    private void duyur(String anahtar, String varsayilan, Object... yt) {
        String mesaj = m().onek() + m().metin(anahtar, varsayilan, yt);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(mesaj);
    }

    // ------------------------------------------------------------------ KAYIT / ÇÖKME
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        long bugun = LocalDate.now().toEpochDay();
        for (Map.Entry<UUID, double[]> e : gunluk.entrySet()) {
            if ((long) e.getValue()[0] != bugun) continue;
            y.set("gunluk." + e.getKey(), e.getValue()[1]);
        }
        y.set("gun", bugun);
        if (macId == null) return y;
        y.set("mac", macId);
        for (Bahis b : bahisler.values()) {
            y.set("bahisler." + b.oyuncu + ".isim", b.isim);
            y.set("bahisler." + b.oyuncu + ".takim", b.takim);
            y.set("bahisler." + b.oyuncu + ".miktar", b.miktar);
        }
        return y;
    }

    /** Açılışta: emanette kalan bahisler (yarım kalan maç) sahiplerine iade edilir. */
    private void kurtar() {
        YamlConfiguration y = dosya.oku();
        long bugun = LocalDate.now().toEpochDay();
        ConfigurationSection g = y.getConfigurationSection("gunluk");
        if (g != null && y.getLong("gun") == bugun) {
            for (String k : g.getKeys(false)) gunluk.put(UUID.fromString(k), new double[]{bugun, g.getDouble(k)});
        }
        ConfigurationSection b = y.getConfigurationSection("bahisler");
        if (b != null && !b.getKeys(false).isEmpty()) {
            plugin.getLogger().warning("[Bahis] Yarım kalan maçın (" + y.getString("mac") + ") emanetteki bahisleri iade ediliyor.");
            for (String k : b.getKeys(false)) {
                ode(UUID.fromString(k), b.getString(k + ".isim"), kurus(b.getDouble(k + ".miktar")), "arena-bahis", "İADE (yarım kalan maç " + y.getString("mac") + ")");
            }
            plugin.meslek().bankayiHemenKaydet();
        }
        temizle();
        dosya.hemenKaydet();
    }
}
