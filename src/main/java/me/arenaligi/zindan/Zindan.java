package me.arenaligi.zindan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

/**
 * Yer altı Zindanı (Faz 5, Entry #10'un derinleştirilmiş hali). Katman katman aşağı inen, her katta
 * canlıların (zombi/iskelet/vindicator varyantları) güçlendiği, en altta bir boss'un beklediği
 * prosedürel üretilmiş yapı. Vahşi Kale'deki gibi: bellekte tutulur, zindanlar.yml'e (ZindanYoneticisi
 * üzerinden) yazılır/okunur. Kat canlılarının UUID'leri runtime-only (restart'ta bekçi tick'i yeniden
 * doğurur, bkz. ZindanYoneticisi#zindanBekcisi).
 */
public class Zindan {

    public final UUID id;
    public String isim;
    public Location merkez; // 1. katın taban/giriş noktası - yakınlık kontrollerinde (en yakın zindan mesafesi) kullanılır
    public int katSayisi;
    public int katYuksekligi = 7; // bir kattan bir sonrakine Y farkı

    // kat indeksi (0 = en üst/kolay, katSayisi-1 = boss katı) -> o kata ait oda merkezi + yarıçapı.
    // Bekçi tick'i (restart sonrası ya da mob ölünce topla(n)ma) bu bilgiyle yeni spawn noktaları
    // türetir - fiziksel yapı zaten inşa edilmiş durduğu için tekrar inşa etmeye gerek kalmaz.
    public final Map<Integer, Location> katMerkezleri = new LinkedHashMap<>();
    public final Map<Integer, Integer> katYariCaplari = new LinkedHashMap<>();

    public boolean temizlendi; // boss öldürüldü mü - true olunca bir daha hiçbir katta mob/boss doğmaz (vahşi kale mantığı)
    public String bossVaryanti; // "TAS_YURUYEN" | "KAN_CELLAT" | "ISKELET_KRALI"
    public UUID bossUuid;
    public Location bossOduluSandigi; // boss katındaki sandık - fiziksel blok generation'da konur, İÇİ sadece boss öldükten sonra doldurulur
    public long olusturulmaZamani;
    // boss öldüğünde bir kere hesaplanır (bkz. ZindanYoneticisi#bossOlumIsle): "now + rastgele 2-3 Minecraft günü
    // karşılığı ms". Bekçi tick'i bu zamana ulaşınca zindanı fiziksel olarak siler (bkz. zindanBekcisi).
    public long silinmeZamani;

    // Runtime-only (persist edilmez): her kattaki canlıların UUID'leri (bekçi tick'i bunları kontrol eder,
    // eksikse tamamlar) ve boss'a verilen hasarın oyuncu bazında katkısı (ödül paylaşımı için).
    public final Map<Integer, Set<UUID>> katCanlilari = new LinkedHashMap<>();
    public final Map<UUID, Double> bossHasarKatkisi = new LinkedHashMap<>();

    public Zindan(UUID id) {
        this.id = id;
    }

    public Set<UUID> katCanlilari(int kat) {
        return katCanlilari.computeIfAbsent(kat, k -> new LinkedHashSet<>());
    }

    public boolean bossKati(int kat) {
        return kat == katSayisi - 1;
    }
}
