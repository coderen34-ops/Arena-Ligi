package me.arenaligi.mac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

/** Süren bir lig maçı. Takımlar sıralıdır: takım 0 spawn1'de, takım 1 spawn2'de doğar. */
public class Mac {

    public enum Durum { ONAY, BEKLEME, GIRIS, DOVUS }

    public final String id;
    public final MacBicimi bicim;
    public final List<List<UUID>> takimlar;
    public final double ucret;
    public Durum durum = Durum.ONAY;
    public long durumBaslangic = System.currentTimeMillis();
    public String kit;

    public final Map<UUID, String> isimler = new HashMap<>();
    public final Map<UUID, String> lakaplar = new HashMap<>();
    public final Set<UUID> onaylar = new HashSet<>();
    public final Set<UUID> odeyenler = new HashSet<>();          // Giriş ücreti alınanlar (iade için)
    public final Map<UUID, Location> donus = new HashMap<>();     // Maç öncesi konum
    public final Map<UUID, Double> hasar = new HashMap<>();       // Rakibe verilen toplam hasar
    public final Map<UUID, Long> terk = new HashMap<>();          // Oyundan çıkan -> çıktığı an
    public final Set<UUID> arenada = new HashSet<>();             // Kit verilip arenaya girenler
    public final Set<UUID> elenen = new HashSet<>();              // Ölen / terk edip süresi dolan
    public int girisAdimi = 0;                                     // Arena giriş anonsu sırası
    public long dovusBaslangic = 0;

    public Mac(String id, MacBicimi bicim, List<List<UUID>> takimlar, double ucret) {
        this.id = id;
        this.bicim = bicim;
        this.takimlar = takimlar;
        this.ucret = ucret;
    }

    public List<UUID> oyuncular() {
        List<UUID> l = new ArrayList<>();
        takimlar.forEach(l::addAll);
        return l;
    }

    public boolean oyuncuMu(UUID u) {
        for (List<UUID> t : takimlar) if (t.contains(u)) return true;
        return false;
    }

    public int takimi(UUID u) {
        for (int i = 0; i < takimlar.size(); i++) if (takimlar.get(i).contains(u)) return i;
        return -1;
    }

    /** Takımda elenmemiş oyuncu kaldı mı? */
    public boolean takimAyakta(int t) {
        for (UUID u : takimlar.get(t)) if (!elenen.contains(u)) return true;
        return false;
    }

    public double takimHasari(int t) {
        double h = 0;
        for (UUID u : takimlar.get(t)) h += hasar.getOrDefault(u, 0.0);
        return h;
    }

    /** "Albert vs Mehmet" biçiminde takım lakapları. */
    public String baslik() {
        List<String> parca = new ArrayList<>();
        for (List<UUID> t : takimlar) {
            List<String> ad = new ArrayList<>();
            for (UUID u : t) ad.add(lakaplar.getOrDefault(u, isimler.getOrDefault(u, "?")));
            parca.add(String.join(" & ", ad));
        }
        return String.join(" vs ", parca);
    }
}
