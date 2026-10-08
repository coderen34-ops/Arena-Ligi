package me.arenaligi.model;

import java.util.UUID;

/** Kayıtlı arena dövüşçüsü ve istatistikleri. */
public class Dovuscu {
    public final UUID uuid;
    public String isim;          // Oyuncu adı (son görülen)
    public String lakap;         // "Karın Deşen Albert"
    public int puan;
    public int lig;              // LigSistemi'ndeki sıra (0 = en alt). Düşme tamponu yüzünden puandan ayrı tutulur.
    public int galibiyet, maglubiyet, beraberlik;
    public int seri;             // Şu anki galibiyet serisi
    public int enIyiSeri;
    public int enYuksekPuan;
    public long kayitTarihi;

    public Dovuscu(UUID uuid, String isim, String lakap, long kayitTarihi) {
        this.uuid = uuid;
        this.isim = isim;
        this.lakap = lakap;
        this.kayitTarihi = kayitTarihi;
    }

    public int macSayisi() { return galibiyet + maglubiyet + beraberlik; }
}
