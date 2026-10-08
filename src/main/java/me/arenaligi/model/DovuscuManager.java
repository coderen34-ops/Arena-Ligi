package me.arenaligi.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;

/** Dövüşçü kaydı, lakap kontrolü ve aramalar. */
public class DovuscuManager {

    private final ArenaLigi plugin;
    private final Map<UUID, Dovuscu> dovusculer = new HashMap<>();
    private final Map<String, UUID> lakapIndeksi = new HashMap<>(); // normalize lakap -> dövüşçü

    public DovuscuManager(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    public Dovuscu get(UUID uuid) { return dovusculer.get(uuid); }
    public Collection<Dovuscu> hepsi() { return dovusculer.values(); }

    public Dovuscu lakapla(String lakap) {
        UUID u = lakapIndeksi.get(anahtar(lakap));
        return u == null ? null : dovusculer.get(u);
    }

    /** Oyuncu adı ya da lakapla arar. */
    public Dovuscu bul(String metin) {
        Dovuscu d = lakapla(metin);
        if (d != null) return d;
        for (Dovuscu x : dovusculer.values()) if (x.isim != null && x.isim.equalsIgnoreCase(metin)) return x;
        return null;
    }

    /** Yükleme sırasında (doğrulamasız). */
    public void ekle(Dovuscu d) {
        dovusculer.put(d.uuid, d);
        lakapIndeksi.put(anahtar(d.lakap), d.uuid);
    }

    /** Puana göre sıralı (eşitlikte galibiyet). */
    public List<Dovuscu> siralama() {
        List<Dovuscu> liste = new ArrayList<>(dovusculer.values());
        liste.sort(Comparator.comparingInt((Dovuscu d) -> d.puan).thenComparingInt(d -> d.galibiyet).reversed());
        return liste;
    }

    // ------------------------------------------------------------------ LAKAP
    /** Büyük/küçük harf, Türkçe karakter ve boşluk farkı gözetmeyen karşılaştırma anahtarı. */
    public static String anahtar(String s) {
        String t = s.toLowerCase(Locale.forLanguageTag("tr"))
                .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ş', 's').replace('ö', 'o').replace('ç', 'c');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("[^a-z0-9]", "");
    }

    /** Geçerliyse null, değilse gösterilecek hata. */
    public String lakapHatasi(String lakap, UUID sahip) {
        int min = plugin.getConfig().getInt("lakap.min", 3), max = plugin.getConfig().getInt("lakap.max", 24);
        if (lakap.length() < min || lakap.length() > max) {
            return m().metin("lakap-uzunluk", "&cLakap {min}-{max} karakter olmalı.", "min", min, "max", max);
        }
        if (!lakap.matches("[A-Za-z0-9ÇĞİÖŞÜçğıöşü' ]+") || lakap.contains("  ") || anahtar(lakap).isEmpty()) {
            return m().metin("lakap-karakter", "&cLakapta sadece harf, rakam, boşluk ve kesme işareti olabilir.");
        }
        String norm = anahtar(lakap);
        for (String kelime : plugin.getConfig().getStringList("yasakli-kelimeler")) {
            if (!kelime.isBlank() && norm.contains(anahtar(kelime))) {
                return m().metin("lakap-yasakli", "&cBu lakap uygunsuz bir ifade içeriyor.");
            }
        }
        UUID var = lakapIndeksi.get(norm);
        if (var != null && !var.equals(sahip)) return m().metin("lakap-alinmis", "&cBu lakap başka bir dövüşçüye ait.");
        return null;
    }

    /** Yeni dövüşçü kaydı. Lakap doğrulanmış olmalı. */
    public Dovuscu kaydet(Player p, String lakap) {
        Dovuscu d = new Dovuscu(p.getUniqueId(), p.getName(), lakap.trim(), System.currentTimeMillis());
        d.lig = plugin.lig().puanaGoreLig(0);
        ekle(d);
        plugin.veri().kaydet();
        plugin.meslek().ekonomiLog("arena-kayit", p.getName(), "Dövüşçü kaydı: \"" + d.lakap + "\"");
        return d;
    }

    /** Lakabı değiştirir (doğrulanmış olmalı). */
    public void lakapDegistir(Dovuscu d, String yeni) {
        lakapIndeksi.remove(anahtar(d.lakap));
        d.lakap = yeni.trim();
        lakapIndeksi.put(anahtar(d.lakap), d.uuid);
        plugin.veri().kaydet();
    }

    /** Oyuncunun adı değiştiyse günceller. */
    public void isimGuncelle(Player p) {
        Dovuscu d = dovusculer.get(p.getUniqueId());
        if (d != null && !p.getName().equals(d.isim)) {
            d.isim = p.getName();
            plugin.veri().kaydet();
        }
    }
}
