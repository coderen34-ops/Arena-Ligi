package me.arenaligi.lig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.model.Dovuscu;

/**
 * Ligler ve puan hesabı (config: ligler, puan).
 * - Lig atlama: puan bir üst ligin eşiğine ulaşınca.
 * - Lig düşme: puan mevcut ligin eşiğinin "dusme-payi" kadar altına inince (anlık dalgalanmaya karşı tampon).
 * - Puan değişimi lig farkına göre ağırlıklı: üst ligi yenen daha çok kazanır, üst lige yenilen daha az kaybeder.
 */
public class LigSistemi {

    public record Lig(String ad, int esik, String renk) {
        public String gorunen() { return Mesaj.renk(renk + ad); }
    }

    /** Puan değişiminin sonucu: lig değiştiyse eski/yeni sıra. */
    public record Sonuc(int puanDegisimi, int eskiLig, int yeniLig) {
        public boolean atladi() { return yeniLig > eskiLig; }
        public boolean dustu() { return yeniLig < eskiLig; }
    }

    private final ArenaLigi plugin;

    public LigSistemi(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    /** Eşiğe göre sıralı ligler (config okunamazsa varsayılan dört lig). */
    public List<Lig> ligler() {
        List<Lig> liste = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("ligler")) {
            Object ad = m.get("ad"), esik = m.get("esik"), renk = m.get("renk");
            if (ad == null || !(esik instanceof Number n)) continue;
            liste.add(new Lig(String.valueOf(ad), Math.max(0, n.intValue()), renk == null ? "&7" : String.valueOf(renk)));
        }
        if (liste.isEmpty()) {
            liste.add(new Lig("Çaylak", 0, "&7"));
            liste.add(new Lig("Savaşçı", 150, "&a"));
            liste.add(new Lig("Usta", 400, "&b"));
            liste.add(new Lig("Efsane", 800, "&6"));
        }
        liste.sort(Comparator.comparingInt(Lig::esik));
        return liste;
    }

    public Lig lig(int sira) {
        List<Lig> l = ligler();
        return l.get(Math.max(0, Math.min(sira, l.size() - 1)));
    }

    public Lig lig(Dovuscu d) { return lig(d.lig); }

    /** Puanın doğrudan karşılık geldiği lig (tampon olmadan; kayıt ve admin puan ayarı için). */
    public int puanaGoreLig(int puan) {
        List<Lig> l = ligler();
        int sira = 0;
        for (int i = 0; i < l.size(); i++) if (puan >= l.get(i).esik()) sira = i;
        return sira;
    }

    private int dusmePayi() { return Math.max(0, plugin.getConfig().getInt("puan.dusme-payi", 30)); }

    /** Puanı değiştikten sonra ligi günceller (atlama ve tamponlu düşme). Eski ligi döner. */
    public int ligGuncelle(Dovuscu d) {
        List<Lig> l = ligler();
        int eski = Math.max(0, Math.min(d.lig, l.size() - 1));
        int yeni = eski;
        while (yeni + 1 < l.size() && d.puan >= l.get(yeni + 1).esik()) yeni++;
        while (yeni > 0 && d.puan < l.get(yeni).esik() - dusmePayi()) yeni--;
        d.lig = yeni;
        return eski;
    }

    private double carpan() { return plugin.getConfig().getDouble("puan.lig-farki-carpani", 0.25); }

    /** Galibiyet puanı: rakip ligi ne kadar yüksekse o kadar fazla (en az 1). */
    public int kazancHesapla(Dovuscu kazanan, Dovuscu kaybeden) {
        int fark = kaybeden.lig - kazanan.lig;
        double p = plugin.getConfig().getInt("puan.galibiyet", 25) * (1 + carpan() * fark);
        return (int) Math.max(1, Math.round(p));
    }

    /** Mağlubiyet puanı: rakip ligi ne kadar yüksekse o kadar az (en az 1). */
    public int kayipHesapla(Dovuscu kaybeden, Dovuscu kazanan) {
        int fark = kazanan.lig - kaybeden.lig;
        double p = plugin.getConfig().getInt("puan.maglubiyet", 20) * (1 - carpan() * fark);
        return (int) Math.max(1, Math.round(p));
    }

    /** Puanı ekler/çıkarır (0'ın altına inmez), en yüksek puanı ve ligi günceller. */
    public Sonuc puanEkle(Dovuscu d, int degisim) {
        int once = d.puan;
        d.puan = Math.max(0, d.puan + degisim);
        d.enYuksekPuan = Math.max(d.enYuksekPuan, d.puan);
        int eskiLig = ligGuncelle(d);
        return new Sonuc(d.puan - once, eskiLig, d.lig);
    }
}
