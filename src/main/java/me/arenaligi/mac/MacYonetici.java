package me.arenaligi.mac;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Konum;
import me.arenaligi.Mesaj;
import me.arenaligi.lig.LigSistemi;
import me.arenaligi.model.Dovuscu;
import me.arenaligi.veri.YamlDosya;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * Otomatik maç döngüsü (saniyede bir):
 *  1) Her "aralik-dakika"da sıradan aynı lig ve en yakın puanlı iki dövüşçü eşleşir (yoksa komşu lig).
 *  2) ONAY: "Maça hazır mısın?" (tıklanabilir, 30 sn). Onaylamayan sıradan çıkar.
 *  3) Giriş ücreti bankadan alınır, dövüşçüler bekleme salonuna ışınlanır. BEKLEME: bahis penceresi (90 sn).
 *  4) GIRIS: sırayla envanter yedeklenir, kit verilir, arenaya ışınlanır ve ekran ortasında anons edilir; geri sayım.
 *  5) DOVUS: öldüren/kalan kazanır. Süre dolarsa çok hasar veren kazanır, eşitse berabere (ücret iadesi).
 *     Oyundan çıkan "terk-saniye" içinde dönmezse yenilir.
 *  6) Puan, istatistik, ödül; herkes eski konumuna döner, envanteri geri verilir.
 * Süren maç aktifmac.yml'de tutulur: sunucu çökerse açılışta giriş ücretleri iade edilir, oyuncular girişte eski konumlarına döner.
 */
public class MacYonetici implements Listener {

    private final ArenaLigi plugin;
    private final YamlDosya dosya;
    private Mac mac;
    private long sonrakiEslestirme;
    private final Map<UUID, Location> bekleyenDonus = new HashMap<>(); // Maç bitince çevrimdışı/ölü olanlar -> dönülecek konum
    private final Set<UUID> izinliIsinlanma = new HashSet<>();

    public MacYonetici(ArenaLigi plugin) {
        this.plugin = plugin;
        this.dosya = new YamlDosya(plugin, "aktifmac.yml", this::olustur);
        sonrakiEslestirme = System.currentTimeMillis() + aralikMs();
        kurtar();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private Mesaj m() { return plugin.mesaj(); }

    // ------------------------------------------------------------------ AYARLAR
    private double c(String yol, double v) {
        double d = plugin.getConfig().getDouble("mac." + yol, v);
        return Double.isFinite(d) && d >= 0 ? d : v;
    }
    private long aralikMs() { return (long) (Math.max(1, c("aralik-dakika", 10)) * 60_000L); }
    private long onayMs() { return (long) (c("onay-saniye", 30) * 1000L); }
    private double girisUcreti() { return Math.round(c("giris-ucreti", 500) * 100.0) / 100.0; }
    private double kazananPayi() { return Math.min(1.0, c("kazanan-payi", 0.8)); }
    private long beklemeMs() { return (long) (c("bahis-suresi-saniye", 90) * 1000L); }
    private int geriSayim() { return (int) c("geri-sayim-saniye", 5); }
    private long sureMs() { return (long) (c("sure-dakika", 3) * 60_000L); }
    private long terkMs() { return (long) (c("terk-saniye", 60) * 1000L); }
    private static final int GIRIS_ARALIK = 3; // Dövüşçü anonsları arası saniye

    // ------------------------------------------------------------------ SORGULAR
    public Mac aktifMac() { return mac; }
    public boolean macta(UUID u) { return mac != null && mac.oyuncuMu(u); }
    public long sonrakiEslestirme() { return sonrakiEslestirme; }

    /** Admin: eşleştirmeyi hemen dene. */
    public void hemenEslestir() { sonrakiEslestirme = 0; }

    // ------------------------------------------------------------------ DÖNGÜ
    private void tick() {
        long simdi = System.currentTimeMillis();
        if (mac == null) {
            if (simdi >= sonrakiEslestirme) {
                sonrakiEslestirme = simdi + aralikMs();
                eslestir();
            }
            return;
        }
        long gecen = simdi - mac.durumBaslangic;
        switch (mac.durum) {
            case ONAY -> {
                if (mac.onaylar.containsAll(mac.oyuncular())) odemeAl();
                else if (gecen >= onayMs()) onayZamanAsimi();
            }
            case BEKLEME -> {
                terkKontrol(simdi);
                if (mac != null && gecen >= beklemeMs() && mac.terk.isEmpty()) girisBasla();
            }
            case GIRIS -> {
                terkKontrol(simdi);
                if (mac != null) girisAdimi(gecen / 1000);
            }
            case DOVUS -> {
                terkKontrol(simdi);
                if (mac != null && simdi - mac.dovusBaslangic >= sureMs()) sureDoldu();
            }
        }
    }

    private void durumDegis(Mac.Durum d) {
        mac.durum = d;
        mac.durumBaslangic = System.currentTimeMillis();
        dosya.kaydet();
    }

    // ------------------------------------------------------------------ 1) EŞLEŞTİRME
    private void eslestir() {
        if (!plugin.arena().hazir() || plugin.kitler().bosMu()) return;
        plugin.kuyruk().temizle();
        List<Dovuscu> adaylar = new ArrayList<>();
        for (UUID u : plugin.kuyruk().siradakiler()) {
            Dovuscu d = plugin.dovusculer().get(u);
            if (d != null && Bukkit.getPlayer(u) != null) adaylar.add(d);
        }
        if (adaylar.size() < 2) return;
        boolean komsu = plugin.getConfig().getBoolean("mac.komsu-lig-eslesme", true);
        // Sırada en uzun bekleyenden başlanır; önce aynı lig ve en yakın puan, yoksa komşu lig
        for (Dovuscu a : adaylar) {
            Dovuscu enIyi = null;
            for (int ligFarki = 0; ligFarki <= (komsu ? 1 : 0) && enIyi == null; ligFarki++) {
                for (Dovuscu b : adaylar) {
                    if (b == a || Math.abs(b.lig - a.lig) != ligFarki) continue;
                    if (enIyi == null || Math.abs(b.puan - a.puan) < Math.abs(enIyi.puan - a.puan)) enIyi = b;
                }
            }
            if (enIyi != null) {
                macOlustur(a, enIyi);
                return;
            }
        }
    }

    private void macOlustur(Dovuscu a, Dovuscu b) {
        List<List<UUID>> takimlar = new ArrayList<>();
        takimlar.add(new ArrayList<>(List.of(a.uuid)));
        takimlar.add(new ArrayList<>(List.of(b.uuid)));
        mac = new Mac(Long.toString(System.currentTimeMillis(), 36), MacBicimi.BIRE_BIR, takimlar, girisUcreti());
        for (Dovuscu d : List.of(a, b)) {
            mac.isimler.put(d.uuid, d.isim);
            mac.lakaplar.put(d.uuid, d.lakap);
        }
        mac.durumBaslangic = System.currentTimeMillis();
        for (UUID u : mac.oyuncular()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            UUID rakip = u.equals(a.uuid) ? b.uuid : a.uuid;
            Dovuscu r = plugin.dovusculer().get(rakip);
            m().gonder(p, "onay-soru", "&6&lMAÇ BULUNDU! &fRakibin: &c\"{rakip}\" &7({lig}&7, {puan} puan). &fGiriş ücreti: &e${ucret} &7({sure} sn)",
                    "rakip", r.lakap, "lig", plugin.lig().lig(r).gorunen(), "puan", r.puan, "ucret", para(mac.ucret), "sure", onayMs() / 1000);
            Component hazir = LegacyComponentSerializer.legacySection().deserialize(m().metin("onay-buton-hazir", "&a&l[HAZIRIM]"))
                    .clickEvent(ClickEvent.runCommand("/arena hazir"))
                    .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacySection().deserialize(m().metin("onay-hover-hazir", "&7Maçı kabul et, giriş ücreti bankandan alınır"))));
            Component vazgec = LegacyComponentSerializer.legacySection().deserialize(m().metin("onay-buton-vazgec", "&c&l[VAZGEÇ]"))
                    .clickEvent(ClickEvent.runCommand("/arena vazgec"));
            p.sendMessage(Component.text("   ").append(hazir).append(Component.text("   ")).append(vazgec));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
        }
        dosya.kaydet();
    }

    // ------------------------------------------------------------------ 2) ONAY
    public void hazir(Player p) {
        if (mac == null || mac.durum != Mac.Durum.ONAY || !mac.oyuncuMu(p.getUniqueId())) {
            m().gonder(p, "onay-yok", "&cOnay bekleyen bir maçınız yok.");
            return;
        }
        if (mac.onaylar.add(p.getUniqueId())) m().gonder(p, "onay-verildi", "&aHazırsın! Rakibin bekleniyor...");
    }

    public void vazgec(Player p) {
        if (mac == null || mac.durum != Mac.Durum.ONAY || !mac.oyuncuMu(p.getUniqueId())) {
            m().gonder(p, "onay-yok", "&cOnay bekleyen bir maçınız yok.");
            return;
        }
        plugin.kuyruk().cikar(p.getUniqueId());
        m().gonder(p, "onay-vazgecti", "&eMaçtan vazgeçtiniz ve sıradan çıktınız.");
        onayIptal(List.of(p.getUniqueId()));
    }

    private void onayZamanAsimi() {
        List<UUID> onaylamayan = new ArrayList<>();
        for (UUID u : mac.oyuncular()) if (!mac.onaylar.contains(u)) onaylamayan.add(u);
        for (UUID u : onaylamayan) {
            plugin.kuyruk().cikar(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null) m().gonder(p, "onay-zaman-asimi", "&cMaçı zamanında onaylamadınız, sıradan çıkarıldınız.");
        }
        onayIptal(onaylamayan);
    }

    /** Onay aşamasında maç düşer; onaylayanlar sırada kalır. */
    private void onayIptal(Collection<UUID> sorumlu) {
        for (UUID u : mac.oyuncular()) {
            if (sorumlu.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) m().gonder(p, "onay-rakip-yok", "&eRakibiniz maçı onaylamadı. Sırada kalmaya devam ediyorsunuz.");
        }
        mac = null;
        dosya.kaydet();
    }

    // ------------------------------------------------------------------ 3) ÖDEME VE BEKLEME
    private void odemeAl() {
        // Önce herkes kontrol edilir, sonra ücret alınır; biri ödeyemezse alınanlar iade edilir
        for (UUID u : mac.oyuncular()) {
            Player p = Bukkit.getPlayer(u);
            String engel = p == null ? m().metin("engel-cevrimdisi", "&cÇevrimdışı.") : plugin.kuyruk().engel(p);
            if (engel != null) {
                if (p != null) p.sendMessage(m().onek() + engel);
                plugin.kuyruk().cikar(u);
                onayIptal(List.of(u));
                return;
            }
        }
        for (UUID u : mac.oyuncular()) {
            Player p = Bukkit.getPlayer(u);
            if (mac.ucret > 0 && !plugin.meslek().bankadanCek(u, mac.ucret)) {
                m().gonder(p, "ucret-yetersiz", "&cBanka hesabınızda giriş ücreti (${ucret}) yok. Sıradan çıkarıldınız.", "ucret", para(mac.ucret));
                plugin.kuyruk().cikar(u);
                for (UUID o : mac.odeyenler) iade(o, mac.isimler.get(o), mac.ucret, "giriş ücreti (rakip ödeyemedi)");
                mac.odeyenler.clear();
                onayIptal(List.of(u));
                return;
            }
            if (mac.ucret > 0) {
                mac.odeyenler.add(u);
                plugin.meslek().ekonomiLog("arena-giris", p.getName(), "Maç " + mac.id + " giriş ücreti -$" + para(mac.ucret));
            }
        }
        for (UUID u : mac.oyuncular()) {
            plugin.kuyruk().cikar(u);
            Player p = Bukkit.getPlayer(u);
            mac.donus.put(u, p.getLocation().clone());
            isinla(p, plugin.arena().bekleme());
            m().gonder(p, "bekleme-salonu", "&aÜcret alındı (${ucret}). Bekleme salonundasınız; maç {sure} saniye sonra başlıyor. &7Işınlanma komutları kapalı.",
                    "ucret", para(mac.ucret), "sure", beklemeMs() / 1000);
        }
        durumDegis(Mac.Durum.BEKLEME);
        plugin.bahis().ac(mac);
        bankaVeKayit();
        herkeseDuyur("mac-duyuru", "&6&l⚔ ARENA: &f{baslik} &7- {sure} saniye sonra başlıyor! &7İzlemek için tribüne gelin.",
                "baslik", mac.baslik(), "sure", beklemeMs() / 1000);
        herkeseDuyur("bahis-acildi", "&eBahis penceresi açık! &f/arena bahis <lakap> <miktar> &7ya da Maçı İzle NPC'si &8(${min}-${max})",
                "min", para(plugin.getConfig().getDouble("bahis.min", 50)), "max", para(plugin.getConfig().getDouble("bahis.max-mac", 5000)));
    }

    // ------------------------------------------------------------------ 4) ARENAYA GİRİŞ
    private void girisBasla() {
        for (UUID u : mac.oyuncular()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue; // Terk süresi işler
            if (p.isDead()) return;   // Yeniden doğması beklenir
            String engel = null;
            if (plugin.meslek().hapisteMi(u)) engel = m().metin("engel-hapis", "&cHapisteyken arenaya katılamazsınız.");
            else if (plugin.meslek().durusmadaMi(u)) engel = m().metin("engel-durusma", "&cDuruşmadayken arenaya katılamazsınız.");
            else if (plugin.meslek().agirYaraliMi(u)) engel = m().metin("engel-yarali", "&cAğır yaralıyken arenaya katılamazsınız.");
            else if (plugin.meslek().saglikMuafMi(u)) engel = m().metin("engel-mesgul", "&cŞu an başka bir dövüştesiniz (klan savaşı ya da maç).");
            if (engel != null) {
                macIptal(m().metin("iptal-engel", "{oyuncu} maça çıkamıyor ({sebep})", "oyuncu", mac.lakaplar.get(u), "sebep", org.bukkit.ChatColor.stripColor(engel)));
                return;
            }
        }
        mac.kit = plugin.kitler().rastgele();
        if (mac.kit == null) { macIptal(m().metin("iptal-kit", "kit havuzu boş")); return; }
        mac.girisAdimi = 0;
        durumDegis(Mac.Durum.GIRIS);
        plugin.bahis().kapat(mac);
    }

    /** gecenSn: GIRIS'e geçeli kaç saniye oldu. */
    private void girisAdimi(long gecenSn) {
        List<UUID> sira = mac.oyuncular();
        // Sıra ile: her dövüşçü GIRIS_ARALIK saniyede bir arenaya alınır ve anons edilir
        while (mac.girisAdimi < sira.size() && gecenSn >= (long) mac.girisAdimi * GIRIS_ARALIK) {
            UUID u = sira.get(mac.girisAdimi++);
            Player p = Bukkit.getPlayer(u);
            if (p != null && !arenayaAl(p)) return;
            anons(u);
        }
        // Anonsu kaçıran (o an çevrimdışı olup dönen) dövüşçüler de alınır
        for (UUID u : sira.subList(0, mac.girisAdimi)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !mac.arenada.contains(u) && !mac.elenen.contains(u) && !arenayaAl(p)) return;
        }
        if (mac.girisAdimi < sira.size()) return;
        // Son dövüşçünün anonsundan GIRIS_ARALIK sonra geri sayım başlar: 5, 4, 3, 2, 1, DÖVÜŞ
        long sayimBasi = (long) (sira.size() - 1) * GIRIS_ARALIK + GIRIS_ARALIK;
        if (gecenSn < sayimBasi) return;
        long sayac = geriSayim() - (gecenSn - sayimBasi);
        if (sayac > 0) {
            for (UUID u : sira) titleGonder(Bukkit.getPlayer(u), "&e&l" + sayac, "", Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f);
            return;
        }
        mac.dovusBaslangic = System.currentTimeMillis();
        durumDegis(Mac.Durum.DOVUS);
        for (UUID u : sira) titleGonder(Bukkit.getPlayer(u), m().metin("title-dovus", "&c&lDÖVÜŞ!"), "", Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f);
        herkeseDuyur("dovus-basladi", "&c&l⚔ &f{baslik} &cdövüşü başladı! &7(Kit: {kit}, süre {sure} dk)",
                "baslik", mac.baslik(), "kit", mac.kit, "sure", sureMs() / 60_000);
    }

    /** Envanteri yedekler, kiti verir, sağlıktan muaf tutar ve takımının doğma noktasına ışınlar. */
    private boolean arenayaAl(Player p) {
        UUID u = p.getUniqueId();
        Location donus = mac.donus.getOrDefault(u, p.getLocation());
        if (!plugin.yedek().kitVer(p, mac.kit, donus)) {
            macIptal(m().metin("iptal-yedek", "{oyuncu} için envanter yedeği alınamadı", "oyuncu", mac.lakaplar.get(u)));
            return false;
        }
        tazele(p);
        plugin.meslek().saglikMuafiyeti(u, true);
        isinla(p, mac.takimi(u) == 0 ? plugin.arena().spawn1() : plugin.arena().spawn2());
        mac.arenada.add(u);
        return true;
    }

    private void anons(UUID u) {
        String lakap = mac.lakaplar.getOrDefault(u, "?");
        Dovuscu d = plugin.dovusculer().get(u);
        String ust = m().metin("title-giris-ust", "&6&l\"{lakap}\"", "lakap", lakap);
        String alt = m().metin("title-giris-alt", "&fARENAYA GİRİYOR &7({lig}&7)", "lig", d == null ? "" : plugin.lig().lig(d).gorunen());
        boolean herkese = plugin.getConfig().getBoolean("mac.anons-herkese", false);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (herkese || macta(o.getUniqueId()) || plugin.seyirci().seyirciMi(o.getUniqueId())
                    || plugin.arena().tribundeMi(o.getLocation()) || plugin.arena().arenadaMi(o.getLocation())) {
                titleGonder(o, ust, alt, Sound.ITEM_GOAT_HORN_SOUND_1, 1f);
            }
        }
    }

    // ------------------------------------------------------------------ 5) DÖVÜŞ VE SONUÇ
    private void terkKontrol(long simdi) {
        for (Map.Entry<UUID, Long> e : new HashMap<>(mac.terk).entrySet()) {
            if (simdi - e.getValue() < terkMs()) continue;
            mac.terk.remove(e.getKey());
            mac.elenen.add(e.getKey());
            herkeseDuyur("terk-yenik", "&c\"{lakap}\" maçı terk etti ve yenilmiş sayıldı.", "lakap", mac.lakaplar.get(e.getKey()));
            sonucKontrol();
            if (mac == null) return;
        }
    }

    /** Tek takım ayakta kaldıysa maç biter. */
    private void sonucKontrol() {
        if (mac == null) return;
        List<Integer> ayakta = new ArrayList<>();
        for (int t = 0; t < mac.takimlar.size(); t++) if (mac.takimAyakta(t)) ayakta.add(t);
        if (ayakta.size() == 1) bitir(ayakta.get(0), m().metin("sebep-kazandi", "rakibini yendi"));
        else if (ayakta.isEmpty()) bitir(-1, m().metin("sebep-iki-taraf", "iki taraf da düştü"));
    }

    private void sureDoldu() {
        double h0 = mac.takimHasari(0), h1 = mac.takimHasari(1);
        String sebep = m().metin("sebep-sure", "süre doldu, hasar {h0} - {h1}", "h0", Math.round(h0 * 10) / 10.0, "h1", Math.round(h1 * 10) / 10.0);
        if (Math.abs(h0 - h1) < 0.01) bitir(-1, sebep);
        else bitir(h0 > h1 ? 0 : 1, sebep);
    }

    /** Admin ya da hata: ücretler iade, puan değişmez. */
    public void macIptal(String sebep) {
        if (mac == null) return;
        herkeseDuyur("mac-iptal", "&e⚔ Maç iptal edildi: &f{baslik} &7({sebep}). Giriş ücretleri iade edildi.", "baslik", mac.baslik(), "sebep", sebep);
        sonlandir(-1, true);
    }

    /** kazanan: takım sırası, -1 berabere (ücretler iade). */
    private void bitir(int kazanan, String sebep) {
        if (kazanan < 0) {
            herkeseDuyur("mac-berabere", "&e⚔ {baslik}: &fBERABERE &7({sebep}). Giriş ücretleri iade edildi.", "baslik", mac.baslik(), "sebep", sebep);
            for (UUID u : mac.oyuncular()) {
                Dovuscu d = plugin.dovusculer().get(u);
                if (d != null) d.beraberlik++;
            }
            sonlandir(-1, true);
            return;
        }
        int kaybeden = 1 - kazanan;
        Dovuscu k = plugin.dovusculer().get(mac.takimlar.get(kazanan).get(0));
        Dovuscu y = plugin.dovusculer().get(mac.takimlar.get(kaybeden).get(0));
        // Puanlar değişmeden önce hesaplanır (ligler o anki haliyle)
        int kazanc = plugin.lig().kazancHesapla(k, y), kayip = plugin.lig().kayipHesapla(y, k);
        for (UUID u : mac.takimlar.get(kazanan)) {
            Dovuscu d = plugin.dovusculer().get(u);
            if (d == null) continue;
            d.galibiyet++;
            d.seri++;
            d.enIyiSeri = Math.max(d.enIyiSeri, d.seri);
            ligMesaji(d, plugin.lig().puanEkle(d, kazanc));
        }
        for (UUID u : mac.takimlar.get(kaybeden)) {
            Dovuscu d = plugin.dovusculer().get(u);
            if (d == null) continue;
            d.maglubiyet++;
            d.seri = 0;
            ligMesaji(d, plugin.lig().puanEkle(d, -kayip));
        }
        // Ödül: giriş ücretlerinin "kazanan-payi" kazanana, kalanı belediye kasasına (kasa yoksa o da kazanana)
        double toplam = mac.ucret * mac.odeyenler.size();
        double odul = Math.round(toplam * kazananPayi() * 100.0) / 100.0;
        double kasaPayi = Math.round((toplam - odul) * 100.0) / 100.0;
        if (kasaPayi > 0) {
            if (plugin.meslek().kasayaYatir(kasaPayi)) {
                plugin.meslek().ekonomiLog("arena-giris", "Maç " + mac.id, "Giriş ücretlerinden belediye kasasına +$" + para(kasaPayi));
            } else {
                odul += kasaPayi;
                plugin.meslek().ekonomiLog("arena-giris", "Maç " + mac.id, "Belediye kasası kabul etmedi; kasa payı ($" + para(kasaPayi) + ") kazanana eklendi");
            }
        }
        List<UUID> kazananlar = mac.takimlar.get(kazanan);
        double kisiBasi = Math.round(odul / kazananlar.size() * 100.0) / 100.0;
        for (UUID u : kazananlar) {
            if (kisiBasi > 0 && plugin.meslek().bankayaYatir(u, kisiBasi)) {
                plugin.meslek().ekonomiLog("arena-odul", mac.isimler.get(u), "Maç " + mac.id + " ödülü +$" + para(kisiBasi));
            }
        }
        herkeseDuyur("mac-sonuc", "&6&l⚔ {kazanan} &6kazandı! &7({baslik} - {sebep}) &a+{kazanc} &7/ &c-{kayip} puan",
                "kazanan", "\"" + k.lakap + "\"", "baslik", mac.baslik(), "sebep", sebep, "kazanc", kazanc, "kayip", kayip);
        for (UUID u : kazananlar) {
            titleGonder(Bukkit.getPlayer(u), m().metin("title-kazandin", "&a&lKAZANDIN!"),
                    m().metin("title-kazandin-alt", "&7+{puan} puan, +${odul}", "puan", kazanc, "odul", para(kisiBasi)), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
        for (UUID u : mac.takimlar.get(kaybeden)) {
            titleGonder(Bukkit.getPlayer(u), m().metin("title-kaybettin", "&c&lKAYBETTİN"),
                    m().metin("title-kaybettin-alt", "&7-{puan} puan", "puan", kayip), Sound.ENTITY_VILLAGER_NO, 1f);
        }
        sonlandir(kazanan, false);
    }

    private void ligMesaji(Dovuscu d, LigSistemi.Sonuc s) {
        if (s.atladi()) {
            herkeseDuyur("lig-atladi", "&6&l▲ \"{lakap}\" &6{lig} &6ligine yükseldi!", "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen());
        } else if (s.dustu()) {
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null) m().gonder(p, "lig-dustu", "&c▼ {lig} &cligine düştünüz.", "lig", plugin.lig().lig(d).gorunen());
        }
    }

    /** Maçı kapatır: (iade ise) ücretleri iade eder, herkesi geri gönderir, kaydı siler. */
    private void sonlandir(int kazanan, boolean iade) {
        Mac bitti = mac;
        mac = null;
        if (iade) for (UUID u : bitti.odeyenler) iade(u, bitti.isimler.get(u), bitti.ucret, "giriş ücreti (maç " + bitti.id + ")");
        plugin.bahis().sonuclandir(bitti, iade ? -1 : kazanan);
        for (UUID u : bitti.oyuncular()) geriGonder(u, bitti);
        plugin.seyirci().macBitti();
        plugin.veri().kaydet();
        sonrakiEslestirme = Math.max(sonrakiEslestirme, System.currentTimeMillis() + 30_000L);
        bankaVeKayit();
    }

    /** Oyuncuyu maç öncesi konumuna döndürür, envanterini geri verir, muafiyeti kaldırır. */
    private void geriGonder(UUID u, Mac bitti) {
        Player p = Bukkit.getPlayer(u);
        Location donus = bitti.donus.get(u);
        if (p == null) {
            // Çevrimdışı: yedeği varsa girişte EnvanterYedek geri verir ve ışınlar; yoksa (bekleme salonunda kaldıysa) burada beklenir
            if (!plugin.yedek().yedekVarMi(u) && donus != null) bekleyenDonus.put(u, donus);
            plugin.meslek().saglikMuafiyeti(u, false);
            return;
        }
        if (p.isDead()) {
            if (donus != null) bekleyenDonus.put(u, donus);
            return; // Yeniden doğunca (onRespawn)
        }
        boolean yedekVardi = plugin.yedek().geriVer(p, false);
        tazele(p);
        if (donus != null || yedekVardi) isinla(p, donus != null ? donus : Bukkit.getWorlds().get(0).getSpawnLocation());
        plugin.meslek().saglikMuafiyeti(u, false); // Işınlandıktan sonra (hapis cezası aldıysa Meslek hücreye alsın)
    }

    /**
     * Para hareketinden sonra: önce Meslek bankası, sonra maç kaydı diske yazılır. Böylece çökmede
     * iade edilecek bir ücret, bankadan düşülmüş haliyle kayıtlıdır (para çoğalmaz), ödenen para da kaybolmaz.
     */
    private void bankaVeKayit() {
        if (!plugin.meslek().bankayiHemenKaydet()) plugin.getLogger().severe("[Para] MeslekSistemi bankası diske yazılamadı!");
        plugin.bahis().hemenKaydet();
        dosya.hemenKaydet();
    }

    private void iade(UUID u, String isim, double miktar, String neden) {
        if (miktar <= 0) return;
        if (plugin.meslek().bankayaYatir(u, miktar)) {
            plugin.meslek().ekonomiLog("arena-iade", isim != null ? isim : u.toString(), neden + " +$" + para(miktar));
        } else {
            plugin.getLogger().severe("[Para] " + u + " oyuncusuna $" + miktar + " iade EDİLEMEDİ (" + neden + ")!");
        }
    }

    // ------------------------------------------------------------------ YARDIMCILAR
    private void tazele(Player p) {
        AttributeInstance can = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(can != null ? can.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(10f);
        p.setFireTicks(0);
        p.setFallDistance(0);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    }

    private void isinla(Player p, Location l) {
        if (p == null || l == null) return;
        izinliIsinlanma.add(p.getUniqueId());
        try {
            p.teleport(l, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            izinliIsinlanma.remove(p.getUniqueId());
        }
    }

    private void titleGonder(Player p, String ust, String alt, Sound ses, float perde) {
        if (p == null) return;
        LegacyComponentSerializer s = LegacyComponentSerializer.legacySection();
        p.showTitle(Title.title(s.deserialize(Mesaj.renk(ust)), s.deserialize(Mesaj.renk(alt)),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(300))));
        if (ses != null) p.playSound(p.getLocation(), ses, 1f, perde);
    }

    private void herkeseDuyur(String anahtar, String varsayilan, Object... yt) {
        String mesaj = m().onek() + m().metin(anahtar, varsayilan, yt);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(mesaj);
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(mesaj));
    }

    public static String para(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.US, "%.2f", d);
    }

    // ------------------------------------------------------------------ KAYIT / ÇÖKME KURTARMA
    private YamlConfiguration olustur() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Location> e : bekleyenDonus.entrySet()) y.set("bekleyen-donus." + e.getKey(), Konum.yaz(e.getValue()));
        if (mac == null || mac.durum == Mac.Durum.ONAY) return y; // Onay aşamasında para alınmamıştır
        y.set("mac.id", mac.id);
        y.set("mac.ucret", mac.ucret);
        List<String> odeyen = new ArrayList<>();
        mac.odeyenler.forEach(u -> odeyen.add(u.toString()));
        y.set("mac.odeyenler", odeyen);
        for (UUID u : mac.oyuncular()) {
            y.set("mac.oyuncular." + u + ".isim", mac.isimler.get(u));
            y.set("mac.oyuncular." + u + ".donus", Konum.yaz(mac.donus.get(u)));
        }
        return y;
    }

    /** Açılışta: önceki çalışmada yarım kalan maçın ücretleri iade edilir, oyuncular girişte eski konumlarına döner. */
    private void kurtar() {
        YamlConfiguration y = dosya.oku();
        ConfigurationSection bd = y.getConfigurationSection("bekleyen-donus");
        if (bd != null) for (String k : bd.getKeys(false)) {
            Location l = Konum.oku(bd.getString(k));
            if (l != null) bekleyenDonus.put(UUID.fromString(k), l);
        }
        if (y.contains("mac.id")) {
            String id = y.getString("mac.id");
            double ucret = y.getDouble("mac.ucret");
            plugin.getLogger().warning("[Maç] Önceki çalışmada yarım kalan maç (" + id + ") bulundu: giriş ücretleri iade ediliyor.");
            for (String s : y.getStringList("mac.odeyenler")) iade(UUID.fromString(s), y.getString("mac.oyuncular." + s + ".isim", s), ucret, "giriş ücreti (yarım kalan maç " + id + ")");
            ConfigurationSection o = y.getConfigurationSection("mac.oyuncular");
            if (o != null) for (String k : o.getKeys(false)) {
                UUID u = UUID.fromString(k);
                plugin.meslek().saglikMuafiyeti(u, false);
                Location l = Konum.oku(o.getString(k + ".donus"));
                if (l != null && !plugin.yedek().yedekVarMi(u)) bekleyenDonus.put(u, l);
            }
        }
        bankaVeKayit();
    }

    /** Sunucu kapanırken: süren maç iptal (ücretler iade), herkes geri döner. */
    public void kapanis() {
        if (mac != null) macIptal(m().metin("iptal-kapanis", "sunucu kapanıyor"));
        dosya.hemenKaydet();
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        if (mac == null || !mac.oyuncuMu(u)) return;
        if (mac.durum == Mac.Durum.ONAY) return; // Sıradan düştü; onay zaman aşımı halleder
        mac.terk.put(u, System.currentTimeMillis());
        mac.arenada.remove(u); // Eşyaları çıkışta geri verildi (EnvanterYedek); dönerse yeniden kit alır
        plugin.meslek().saglikMuafiyeti(u, false);
        herkeseDuyur("terk-uyari", "&e\"{lakap}\" oyundan çıktı! &7{sure} saniye içinde dönmezse yenilmiş sayılacak.",
                "lakap", mac.lakaplar.get(u), "sure", terkMs() / 1000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // EnvanterYedek'in giriş işleminden sonra
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            UUID u = p.getUniqueId();
            if (mac != null && mac.terk.remove(u) != null) {
                m().gonder(p, "terk-dondu", "&aMaça geri döndünüz!");
                if (mac.durum == Mac.Durum.BEKLEME || (mac.durum == Mac.Durum.GIRIS && !mac.arenada.contains(u))) {
                    isinla(p, plugin.arena().bekleme());
                } else if (!arenayaAl(p)) {
                    return;
                }
                return;
            }
            Location l = bekleyenDonus.remove(u);
            if (l != null) {
                isinla(p, l);
                plugin.meslek().saglikMuafiyeti(u, false);
                dosya.kaydet();
            }
        }, 3L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (mac == null || !mac.arenada.contains(p.getUniqueId())) return;
        mac.elenen.add(p.getUniqueId());
        mac.arenada.remove(p.getUniqueId());
        event.deathMessage(null);
        Bukkit.getScheduler().runTask(plugin, this::sonucKontrol);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location l = bekleyenDonus.remove(p.getUniqueId());
        if (l == null) return;
        event.setRespawnLocation(l);
        dosya.kaydet();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.yedek().geriVer(p, false);
            tazele(p);
            plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false);
        });
    }

    /** Arenaya giriş/geri sayımda hareket yok; dövüşte arena sınırından çıkılamaz. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (mac == null || !mac.arenada.contains(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (mac.durum == Mac.Durum.GIRIS) {
            event.setTo(from.clone().setDirection(to.getDirection()));
        } else if (mac.durum == Mac.Durum.DOVUS && !plugin.arena().arenadaMi(to)) {
            event.setTo(from.clone().setDirection(to.getDirection()));
            m().gonder(event.getPlayer(), "arena-sinir", "&cArena sınırından çıkamazsınız!");
        }
    }

    /** Maç boyunca (bekleme salonundan itibaren) inci, chorus ve başka eklentilerin ışınlaması engellenir. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        if (mac == null || mac.durum == Mac.Durum.ONAY || !mac.oyuncuMu(u) || izinliIsinlanma.contains(u)) return;
        PlayerTeleportEvent.TeleportCause sebep = event.getCause();
        if (sebep == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || sebep == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
                || sebep == PlayerTeleportEvent.TeleportCause.COMMAND || (mac.arenada.contains(u) && sebep == PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            event.setCancelled(true);
            m().gonder(event.getPlayer(), "isinlanma-yasak", "&cMaç sırasında ışınlanamazsınız!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKomut(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (mac == null || mac.durum == Mac.Durum.ONAY || !mac.oyuncuMu(p.getUniqueId()) || p.hasPermission("arena.admin")) return;
        String govde = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        String komut = govde.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        int onek = komut.lastIndexOf(':');
        if (onek >= 0) komut = komut.substring(onek + 1);
        if (plugin.getConfig().getStringList("mac.yasak-komutlar").contains(komut)) {
            event.setCancelled(true);
            m().gonder(p, "komut-yasak", "&cMaç sırasında bu komutu kullanamazsınız.");
        }
    }

    private static Player saldiran(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Dövüşçüler sadece birbirine (dövüş başlamışken) hasar verebilir; dışarıdan müdahale yok. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHasarFiltre(EntityDamageByEntityEvent event) {
        if (mac == null) return;
        Player vuran = saldiran(event);
        boolean kurbanMacta = event.getEntity() instanceof Player k && mac.arenada.contains(k.getUniqueId());
        boolean vuranMacta = vuran != null && mac.arenada.contains(vuran.getUniqueId());
        if (!kurbanMacta && !vuranMacta) return;
        if (mac.durum != Mac.Durum.DOVUS || kurbanMacta != vuranMacta) {
            event.setCancelled(true);
            return;
        }
        Player kurban = (Player) event.getEntity();
        if (mac.takimi(kurban.getUniqueId()) == mac.takimi(vuran.getUniqueId())) event.setCancelled(true);
    }

    /** Verilen hasar (süre dolunca kazananı belirler): kurbanın kalan canından fazlası sayılmaz. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHasarSay(EntityDamageByEntityEvent event) {
        if (mac == null || mac.durum != Mac.Durum.DOVUS || !(event.getEntity() instanceof Player kurban)) return;
        Player vuran = saldiran(event);
        if (vuran == null || !mac.arenada.contains(vuran.getUniqueId()) || !mac.arenada.contains(kurban.getUniqueId())) return;
        double h = Math.min(event.getFinalDamage(), kurban.getHealth());
        mac.hasar.merge(vuran.getUniqueId(), h, Double::sum);
    }

    /** Giriş ve geri sayımda dövüşçü çevreden de hasar almaz (düşme vb.). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHasarGiris(EntityDamageEvent event) {
        if (mac != null && mac.durum == Mac.Durum.GIRIS && event.getEntity() instanceof Player p && mac.arenada.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Kuyruk/kayıt engeli: zaten maçtaysa ya da hayatta kalma modunda değilse. */
    public String ekEngel(Player p) {
        if (macta(p.getUniqueId()) && mac.durum != Mac.Durum.ONAY) return m().metin("engel-macta", "&cZaten bir maçtasınız.");
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
            return m().metin("engel-mod", "&cArenaya katılmak için hayatta kalma modunda olmalısınız.");
        }
        return null;
    }
}
