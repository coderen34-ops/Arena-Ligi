package me.arenaligi.turnuva;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.model.Dovuscu;
import me.arenaligi.veri.YamlDosya;

/**
 * Haftalık Arena turnuvası (Faz 3): tek eleme (bracket), katılıma giriş ücreti, ödül havuzu katılımcılardan.
 *
 *  - Belirli gün/saatte (config: turnuva.gun / turnuva.saat) kayıt açılır; /arena turnuva kayit ile ücret bankadan alınır.
 *  - Kayıt süresi dolunca min-katilimci altındaysa turnuva İPTAL edilir ve ücretler iade edilir.
 *  - Bracket 2'nin kuvvetine tamamlanır; boşluklar "bye" olur, en yüksek puanlılar bye alır.
 *  - Turnuva maçları normal maç motorunda (MacYonetici) oynanır: ücret yok, ELO yok, berabere yok.
 *  - Ödül: havuzun yüzdeleri (config: turnuva.odul-dagilim) şampiyon/finalist/yarı finalistlere; kalan belediye kasasına.
 *  - Çökme güvenliği: kayıtlı ücretler turnuva.yml'de tutulur; açılışta bitmemiş turnuva varsa ücretler iade edilir.
 *  - Başarım: MeslekSistemi /basarimsayac turnuva_katilim, turnuva_sampiyon.
 */
public class TurnuvaYonetici {

    private enum Faz { KAPALI, KAYIT, OYNANIYOR }

    private final ArenaLigi plugin;
    private final YamlDosya dosya;

    private Faz faz = Faz.KAPALI;
    private long kayitBitis;
    private double ucret;                                  // bu turnuva için alınan giriş ücreti
    private final Map<UUID, String> katilimcilar = new LinkedHashMap<>(); // uuid -> isim (ücreti ödemiş)
    private List<UUID> buTur = new ArrayList<>();          // bu turda oynayacaklar (sırayla çift çift)
    private final List<UUID> sonrakiTur = new ArrayList<>();
    private final List<UUID> yarisFinalKaybedenler = new ArrayList<>();
    private int turNo;
    private int turBoyutu;                                // tur başında kalan toplam oyuncu (bye alanlar dahil)
    private int cift;                                       // buTur içindeki sıradaki çiftin başlangıç indeksi
    private boolean macBekleniyor;
    private long sonrakiMacZamani;
    private int tekrarSayaci;
    private String sonBasladigiHafta = "";                  // yyyy-ww benzeri, aynı hafta iki kez açılmasın
    private final Random rng = new Random();

    public TurnuvaYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "turnuva.yml", this::olustur);
        kurtar();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 40L); // 2 sn'de bir
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR
    private boolean aktif() { return plugin.getConfig().getBoolean("turnuva.aktif", true); }
    private double girisUcreti() { return Math.max(0, plugin.getConfig().getDouble("turnuva.giris-ucreti", 1000)); }
    private int minKatilimci() { return Math.max(2, plugin.getConfig().getInt("turnuva.min-katilimci", 4)); }
    private int maxKatilimci() { return Math.max(2, Math.min(64, plugin.getConfig().getInt("turnuva.max-katilimci", 16))); }
    private long kayitMs() { return (long) (Math.max(1, plugin.getConfig().getDouble("turnuva.kayit-dakika", 15)) * 60_000L); }
    private ZoneId zone() {
        try { return ZoneId.of(plugin.getConfig().getString("turnuva.saat-dilimi", "Europe/Istanbul")); }
        catch (Exception e) { return ZoneId.of("Europe/Istanbul"); }
    }
    /** [şampiyon, finalist, yarı finalist (her biri)] yüzdeleri; kalan belediye kasasına. */
    private double[] dagilim() {
        List<Double> l = plugin.getConfig().getDoubleList("turnuva.odul-dagilim");
        if (l.size() < 3) return new double[]{0.60, 0.25, 0.05};
        return new double[]{l.get(0), l.get(1), l.get(2)};
    }

    // ------------------------------------------------------------------ ZAMANLAMA
    private void tick() {
        long simdi = System.currentTimeMillis();
        switch (faz) {
            case KAPALI -> {
                if (!aktif()) return;
                LocalDateTime t = LocalDateTime.now(zone());
                DayOfWeek gun;
                try { gun = DayOfWeek.valueOf(plugin.getConfig().getString("turnuva.gun", "SATURDAY").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { gun = DayOfWeek.SATURDAY; }
                String[] sa = plugin.getConfig().getString("turnuva.saat", "20:00").split(":");
                int saat = 20, dk = 0;
                try { saat = Integer.parseInt(sa[0].trim()); dk = Integer.parseInt(sa[1].trim()); } catch (Exception ignored) { }
                String hafta = t.getYear() + "-" + t.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                // Belirlenen dakikanın ilk 5 dakikası içinde açılır (kaçırma payı); aynı hafta tekrar açılmaz
                if (t.getDayOfWeek() == gun && t.getHour() == saat && t.getMinute() >= dk && t.getMinute() < dk + 5 && !hafta.equals(sonBasladigiHafta)) {
                    sonBasladigiHafta = hafta;
                    kayitAc(null);
                }
            }
            case KAYIT -> { if (simdi >= kayitBitis) kayitKapat(false); }
            case OYNANIYOR -> oynat(simdi);
        }
    }

    // ------------------------------------------------------------------ KAYIT
    public boolean kayitAc(CommandSender admin) {
        if (faz != Faz.KAPALI) { if (admin != null) m().gonder(admin, "turnuva-zaten", "&cZaten bir turnuva sürüyor."); return false; }
        faz = Faz.KAYIT;
        ucret = Math.round(girisUcreti() * 100.0) / 100.0;
        katilimcilar.clear();
        kayitBitis = System.currentTimeMillis() + kayitMs();
        dosya.hemenKaydet();
        duyur("turnuva-acildi", "&6&l🏆 ARENA TURNUVASI KAYITLARI AÇILDI! &7Tek eleme, giriş ücreti &e${ucret}&7 (ödül havuzu katılımcılardan oluşur). Kayıt: &e/arena turnuva kayit &7({dk} dk)",
                "ucret", ucretMetni(ucret), "dk", kayitMs() / 60_000L);
        return true;
    }

    private static String ucretMetni(double d) { return me.arenaligi.mac.MacYonetici.para(d); }

    public void kayit(Player p) {
        if (faz != Faz.KAYIT) { m().gonder(p, "turnuva-kayit-yok", "&cŞu an açık bir turnuva kaydı yok."); return; }
        UUID u = p.getUniqueId();
        Dovuscu d = plugin.dovusculer().get(u);
        if (d == null) { m().gonder(p, "kayitli-degil", "&cÖnce Arena Kayıt NPC'sinden dövüşçü olarak kayıt olmalısınız."); return; }
        if (katilimcilar.containsKey(u)) { m().gonder(p, "turnuva-zaten-kayitli", "&eZaten kayıtlısınız."); return; }
        if (katilimcilar.size() >= maxKatilimci()) { m().gonder(p, "turnuva-dolu", "&cTurnuva dolu ({max} kişi).", "max", maxKatilimci()); return; }
        String engel = plugin.kuyruk().engel(p);
        if (engel != null) { p.sendMessage(m().onek() + engel); return; }
        // Önce para, sonra kayıt; kayıt hemen diske yazılır (çökmede ücret iadesi için)
        if (ucret > 0 && !plugin.meslek().bankadanCek(u, ucret)) {
            m().gonder(p, "turnuva-para-yok", "&cBanka hesabınızda giriş ücreti (${ucret}) yok.", "ucret", ucretMetni(ucret));
            return;
        }
        katilimcilar.put(u, d.isim);
        plugin.meslek().ekonomiLog("arena-turnuva", p.getName(), "Turnuva giriş ücreti -$" + ucretMetni(ucret));
        plugin.meslek().bankayiHemenKaydet();
        dosya.hemenKaydet();
        m().gonder(p, "turnuva-kayit-tamam", "&aTurnuvaya kayıt oldunuz! &7({sayi}/{max}) Kayıt kapanınca bracket duyurulur.", "sayi", katilimcilar.size(), "max", maxKatilimci());
    }

    /** Kayıt açıkken çekilme: ücret iade edilir. */
    public void cik(Player p) {
        if (faz != Faz.KAYIT || !katilimcilar.containsKey(p.getUniqueId())) { m().gonder(p, "turnuva-kayitli-degil", "&cKayıtlı değilsiniz ya da kayıt kapandı."); return; }
        katilimcilar.remove(p.getUniqueId());
        iadeEt(p.getUniqueId(), p.getName(), "kayıttan çekilme");
        dosya.hemenKaydet();
        m().gonder(p, "turnuva-cekildi", "&eTurnuva kaydından çekildiniz, ücret iade edildi.");
    }

    public void bilgi(CommandSender s) {
        switch (faz) {
            case KAPALI -> m().gonder(s, "turnuva-kapali", "&7Şu an turnuva yok. Her hafta {gun} {saat} saatinde kayıtlar açılır.",
                    "gun", plugin.getConfig().getString("turnuva.gun", "SATURDAY"), "saat", plugin.getConfig().getString("turnuva.saat", "20:00"));
            case KAYIT -> m().gonder(s, "turnuva-bilgi-kayit", "&6Turnuva kayıtta: &e{sayi}/{max} &7kayıtlı, giriş ${ucret}, kalan {sn} sn. En az {min} kişi gerekir.",
                    "sayi", katilimcilar.size(), "max", maxKatilimci(), "ucret", ucretMetni(ucret), "sn", Math.max(0, (kayitBitis - System.currentTimeMillis()) / 1000), "min", minKatilimci());
            case OYNANIYOR -> m().gonder(s, "turnuva-bilgi-oynaniyor", "&6Turnuva sürüyor: &e{tur}. tur&7, kalan {kalan} oyuncu.", "tur", turNo, "kalan", buTur.size() - cift + sonrakiTur.size());
        }
    }

    // ------------------------------------------------------------------ KAYIT KAPANIŞ / BRACKET
    /** zorla: admin /arena admin turnuva baslat - süre dolmasını beklemeden kapatır. */
    public void kayitKapat(boolean zorla) {
        if (faz != Faz.KAYIT) return;
        // Çevrimdışı kalan kayıtlılar elenir ve iade edilir
        List<UUID> cikanlar = new ArrayList<>();
        for (UUID u : katilimcilar.keySet()) if (Bukkit.getPlayer(u) == null) cikanlar.add(u);
        for (UUID u : cikanlar) { iadeEt(u, katilimcilar.remove(u), "turnuva başlarken çevrimdışı"); }
        if (katilimcilar.size() < minKatilimci()) {
            duyur("turnuva-iptal-yetersiz", "&c🏆 Turnuva iptal edildi: yeterli katılımcı yok ({sayi}/{min}). Giriş ücretleri iade edildi.", "sayi", katilimcilar.size(), "min", minKatilimci());
            tumunuIadeEtVeKapat("yetersiz katılım");
            return;
        }
        faz = Faz.OYNANIYOR;
        plugin.mac().turnuvaAktif(true);
        plugin.kuyruk().temizle();
        for (UUID u : katilimcilar.keySet()) plugin.kuyruk().cikar(u);
        turNo = 1;
        List<UUID> sirali = new ArrayList<>(katilimcilar.keySet());
        sirali.sort(Comparator.comparingInt((UUID u) -> { Dovuscu d = plugin.dovusculer().get(u); return d == null ? 0 : d.puan; }).reversed());
        int boyut = 1; while (boyut < sirali.size()) boyut <<= 1;
        int bye = boyut - sirali.size();
        sonrakiTur.clear();
        yarisFinalKaybedenler.clear();
        // En yüksek puanlı 'bye' kişi ilk turu atlar; kalanlar güçlü-zayıf eşleşir
        for (int i = 0; i < bye; i++) sonrakiTur.add(sirali.get(i));
        List<UUID> oynayacak = new ArrayList<>(sirali.subList(bye, sirali.size()));
        buTur = new ArrayList<>();
        int l = 0, r = oynayacak.size() - 1;
        while (l < r) { buTur.add(oynayacak.get(l++)); buTur.add(oynayacak.get(r--)); }
        cift = 0;
        turBoyutu = sirali.size();
        macBekleniyor = false;
        sonrakiMacZamani = System.currentTimeMillis() + 15_000L;
        tekrarSayaci = 0;
        StringBuilder b = new StringBuilder();
        for (UUID u : katilimcilar.keySet()) b.append(katilimcilar.get(u)).append(" ");
        double havuz = havuz();
        duyur("turnuva-basliyor", "&6&l🏆 TURNUVA BAŞLIYOR! &f{sayi} &7katılımcı, ödül havuzu &e${havuz}&7. İlk maç birazdan! İzlemek için: &e/arena izle", "sayi", katilimcilar.size(), "havuz", ucretMetni(havuz));
        for (UUID u : katilimcilar.keySet()) {
            Bukkit.getScheduler().runTask(plugin, () -> basarimSayac(u, "turnuva_katilim"));
            if (sonrakiTur.contains(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) m().gonder(p, "turnuva-bye", "&aEn yüksek puanlılardansınız: ilk turu &lbye&a ile geçtiniz.");
            }
        }
        plugin.meslek().ekonomiLog("arena-turnuva", "Turnuva", "Başladı: " + katilimcilar.size() + " katılımcı, havuz $" + ucretMetni(havuz) + " | " + b);
        dosya.hemenKaydet();
    }

    private double havuz() { return Math.round(ucret * katilimcilar.size() * 100.0) / 100.0; }

    // ------------------------------------------------------------------ OYNATMA
    private void oynat(long simdi) {
        if (macBekleniyor) return; // sonuç geri çağrısı bekleniyor
        if (simdi < sonrakiMacZamani) return;
        // Tur bitti mi?
        if (cift >= buTur.size()) {
            tureGec();
            return;
        }
        UUID a = buTur.get(cift), b = buTur.get(cift + 1);
        boolean ao = Bukkit.getPlayer(a) != null, bo = Bukkit.getPlayer(b) != null;
        if (!ao || !bo) {
            // Çevrimdışı olan hükmen yenik sayılır (ikisi de yoksa yüksek puanlı ilerler)
            UUID galip = ao ? a : (bo ? b : yuksekPuanli(a, b));
            UUID maglup = galip.equals(a) ? b : a;
            duyur("turnuva-hukmen", "&e🏆 {galip} &7hükmen turu geçti ({maglup} maça gelmedi).", "galip", ad(galip), "maglup", ad(maglup));
            turSonucu(galip, maglup);
            return;
        }
        if (plugin.mac().aktifMac() != null) return; // süren normal maç bitsin
        final UUID fa = a, fb = b;
        duyur("turnuva-mac", "&6&l🏆 {tur}. TUR: &f{a} &7vs &f{b}", "tur", turNo, "a", ad(a), "b", ad(b));
        macBekleniyor = plugin.mac().turnuvaMaciBaslat(a, b, galip -> macBitti(fa, fb, galip));
        if (!macBekleniyor) {
            // Başlatılamadı (arena hazır değil, oyuncu engelli vb.): birkaç denemeden sonra yüksek puanlı hükmen ilerler
            tekrarSayaci++;
            sonrakiMacZamani = simdi + 10_000L;
            if (tekrarSayaci >= 12) {
                UUID galip = yuksekPuanli(a, b);
                duyur("turnuva-hukmen-teknik", "&e🏆 {galip} &7teknik nedenle turu geçti.", "galip", ad(galip));
                turSonucu(galip, galip.equals(a) ? b : a);
            }
        } else {
            tekrarSayaci = 0;
        }
    }

    private void macBitti(UUID a, UUID b, UUID galip) {
        macBekleniyor = false;
        if (faz != Faz.OYNANIYOR) return;
        if (galip == null) {
            // Maç iptal/hata: aynı çift tekrar denenir
            tekrarSayaci++;
            sonrakiMacZamani = System.currentTimeMillis() + 15_000L;
            if (tekrarSayaci >= 3) { UUID g = yuksekPuanli(a, b); turSonucu(g, g.equals(a) ? b : a); }
            return;
        }
        turSonucu(galip, galip.equals(a) ? b : a);
    }

    private void turSonucu(UUID galip, UUID maglup) {
        sonrakiTur.add(galip);
        // Yarı final kaybedenleri (4 kişi kala) 3. sıra ödülü için ayrıca tutulur
        if (turBoyutu == 4) yarisFinalKaybedenler.add(maglup);
        if (turBoyutu == 2) finalKaybeden = maglup;
        cift += 2;
        tekrarSayaci = 0;
        macBekleniyor = false;
        sonrakiMacZamani = System.currentTimeMillis() + 20_000L; // maçlar arası nefes
    }

    private UUID finalKaybeden;

    private void tureGec() {
        if (sonrakiTur.size() == 1) { bitir(sonrakiTur.get(0)); return; }
        if (sonrakiTur.isEmpty()) { tumunuIadeEtVeKapat("bracket boş"); return; }
        buTur = new ArrayList<>(sonrakiTur);
        sonrakiTur.clear();
        // Bye almış güçlüler baştaki sırada; adil karışıklık için kura çekilir ama bye alanlar birbirine denk gelmesin diye sıra korunur
        turNo++;
        cift = 0;
        turBoyutu = buTur.size() + sonrakiTur.size();
        sonrakiMacZamani = System.currentTimeMillis() + 15_000L;
        if (buTur.size() % 2 == 1) { // beklenmedik tek sayı: sonuncu bye
            sonrakiTur.add(buTur.remove(buTur.size() - 1));
        }
        duyur("turnuva-tur", "&6🏆 {tur}. tura geçildi, kalan &f{kalan} &6oyuncu.", "tur", turNo, "kalan", buTur.size() + sonrakiTur.size());
    }

    private UUID yuksekPuanli(UUID a, UUID b) {
        Dovuscu da = plugin.dovusculer().get(a), db = plugin.dovusculer().get(b);
        int pa = da == null ? 0 : da.puan, pb = db == null ? 0 : db.puan;
        return pa >= pb ? a : b;
    }

    private String ad(UUID u) {
        Dovuscu d = plugin.dovusculer().get(u);
        return d != null ? "\"" + d.lakap + "\"" : String.valueOf(katilimcilar.get(u));
    }

    // ------------------------------------------------------------------ BİTİŞ / ÖDÜL
    private void bitir(UUID sampiyon) {
        double havuz = havuz();
        double[] dg = dagilim();
        double sampiyonOdul = Math.round(havuz * dg[0] * 100.0) / 100.0;
        double finalistOdul = Math.round(havuz * dg[1] * 100.0) / 100.0;
        double yariOdul = Math.round(havuz * dg[2] * 100.0) / 100.0;
        double dagitilan = sampiyonOdul;
        odulVer(sampiyon, sampiyonOdul, "şampiyon");
        if (finalKaybeden != null) { odulVer(finalKaybeden, finalistOdul, "finalist"); dagitilan += finalistOdul; }
        for (UUID u : yarisFinalKaybedenler) { odulVer(u, yariOdul, "yarı finalist"); dagitilan += yariOdul; }
        double kasaPayi = Math.round((havuz - dagitilan) * 100.0) / 100.0;
        if (kasaPayi > 0 && !plugin.meslek().kasayaYatir(kasaPayi)) {
            // Kasa kabul etmezse şampiyona eklenir (para kaybolmasın)
            odulVer(sampiyon, kasaPayi, "kasa payı (kasa dolu)");
        }
        duyur("turnuva-sampiyon", "&6&l🏆 TURNUVA ŞAMPİYONU: &e{sampiyon}&6&l! &7Ödül: &a${odul} &7(toplam havuz ${havuz})", "sampiyon", ad(sampiyon), "odul", ucretMetni(sampiyonOdul), "havuz", ucretMetni(havuz));
        basarimSayac(sampiyon, "turnuva_sampiyon");
        plugin.meslek().ekonomiLog("arena-turnuva", "Turnuva", "Bitti. Şampiyon " + katilimcilar.get(sampiyon) + " $" + ucretMetni(sampiyonOdul) + ", kasa $" + ucretMetni(kasaPayi));
        plugin.meslek().bankayiHemenKaydet();
        sifirla();
    }

    private void odulVer(UUID u, double miktar, String sebep) {
        if (miktar <= 0) return;
        if (plugin.meslek().bankayaYatir(u, miktar)) {
            plugin.meslek().ekonomiLog("arena-turnuva", String.valueOf(katilimcilar.get(u)), "Turnuva ödülü (" + sebep + ") +$" + ucretMetni(miktar));
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(m().onek() + m().metin("turnuva-odul", "&aTurnuva ödülü ({sebep}): &e${miktar}", "sebep", sebep, "miktar", ucretMetni(miktar)));
        }
    }

    // ------------------------------------------------------------------ İPTAL / İADE / KAPANIŞ
    public void iptal(CommandSender s) {
        if (faz == Faz.KAPALI) { m().gonder(s, "turnuva-yok", "&7Aktif turnuva yok."); return; }
        duyur("turnuva-iptal", "&c🏆 Turnuva iptal edildi. Giriş ücretleri iade edildi.");
        if (plugin.mac().aktifMac() != null && plugin.mac().aktifMac().turnuva) plugin.mac().macIptal("turnuva iptal");
        tumunuIadeEtVeKapat("admin iptali");
    }

    private void tumunuIadeEtVeKapat(String sebep) {
        for (Map.Entry<UUID, String> e : new ArrayList<>(katilimcilar.entrySet())) iadeEt(e.getKey(), e.getValue(), sebep);
        plugin.meslek().bankayiHemenKaydet();
        sifirla();
    }

    private void iadeEt(UUID u, String isim, String sebep) {
        if (ucret <= 0) return;
        if (plugin.meslek().bankayaYatir(u, ucret)) plugin.meslek().ekonomiLog("arena-turnuva", String.valueOf(isim), "Turnuva ücreti iade +$" + ucretMetni(ucret) + " (" + sebep + ")");
        else plugin.getLogger().severe("[Turnuva] " + isim + " ücreti iade EDİLEMEDİ: $" + ucret + " (" + sebep + ") - elle iade gerekli!");
    }

    private void sifirla() {
        faz = Faz.KAPALI;
        katilimcilar.clear();
        buTur = new ArrayList<>();
        sonrakiTur.clear();
        yarisFinalKaybedenler.clear();
        finalKaybeden = null;
        macBekleniyor = false;
        plugin.mac().turnuvaAktif(false);
        dosya.hemenKaydet();
    }

    /** Sunucu kapanırken: bitmemiş turnuva (kayıt ya da oynanan) iptal, ücretler iade. */
    public void kapanis() {
        if (faz == Faz.KAPALI) return;
        for (Map.Entry<UUID, String> e : new ArrayList<>(katilimcilar.entrySet())) iadeEt(e.getKey(), e.getValue(), "sunucu kapanışı");
        plugin.meslek().bankayiHemenKaydet();
        sifirla();
    }

    // ------------------------------------------------------------------ KALICILIK (çökme güvenliği)
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("faz", faz.name());
        y.set("ucret", ucret);
        for (Map.Entry<UUID, String> e : katilimcilar.entrySet()) y.set("katilimcilar." + e.getKey(), e.getValue());
        y.set("son-hafta", sonBasladigiHafta);
        return y;
    }

    /** Açılışta: dosyada bitmemiş turnuva varsa (çökme/restart) ücretler iade edilir. */
    private void kurtar() {
        YamlConfiguration y = dosya.oku();
        sonBasladigiHafta = y.getString("son-hafta", "");
        String f = y.getString("faz", "KAPALI");
        if ("KAPALI".equals(f)) return;
        double u = y.getDouble("ucret", 0);
        ConfigurationSection k = y.getConfigurationSection("katilimcilar");
        if (k != null && u > 0) {
            for (String key : k.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    if (plugin.meslek().bankayaYatir(id, u)) plugin.meslek().ekonomiLog("arena-turnuva", k.getString(key, key), "Turnuva ücreti iade +$" + ucretMetni(u) + " (sunucu yeniden başladı)");
                } catch (Exception e) {
                    plugin.getLogger().warning("[Turnuva] iade kaydı okunamadı: " + key);
                }
            }
            plugin.meslek().bankayiHemenKaydet();
        }
        faz = Faz.KAPALI;
        dosya.hemenKaydet();
        plugin.getLogger().warning("[Turnuva] Bitmemiş turnuva bulundu, giriş ücretleri iade edildi.");
    }

    // ------------------------------------------------------------------ YARDIMCI
    private void duyur(String anahtar, String varsayilan, Object... yt) {
        String mesaj = m().onek() + m().metin(anahtar, varsayilan, yt);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(mesaj);
    }

    private void basarimSayac(UUID u, String sayac) {
        Player p = Bukkit.getPlayer(u);
        if (p != null && p.getName().matches("[A-Za-z0-9_]{1,16}")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "basarimsayac " + p.getName() + " " + sayac + " 1");
        }
    }

    public List<String> tabAdlari() { return Collections.unmodifiableList(List.of("kayit", "cik", "bilgi")); }
}
