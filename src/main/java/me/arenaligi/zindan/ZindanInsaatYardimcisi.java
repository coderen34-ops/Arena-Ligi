package me.arenaligi.zindan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Ladder;

/**
 * Yer altı Zindanı'nın fiziksel yapısını (katman katman, yüzeyden aşağı inen) prosedürel olarak inşa
 * eder - Vahşi Kale'deki KaleInsaatYardimcisi'nin aynı ruhu (rastgele ama tutarlı, "labirent" hissi
 * veren) burada yer altına, kat kat derinleşen bir versiyonla uygulanıyor.
 *
 * Her kat: merkezde bir oda + 4 yönde (K/G/D/B) küçük birer çıkma odacık ("+" şeklinde, basit ama
 * gezilecek bir labirent hissi verir), duvarlar/taban/tavan taş tuğlası (derin katlarda çatlak/misket
 * taş tuğlası - görsel derinlik), merdiven boşluğu bir sonraki kata (merdivenli) iner. En alt kat
 * (boss katı) daha büyük tek bir oda.
 */
public final class ZindanInsaatYardimcisi {

    private ZindanInsaatYardimcisi() { }

    public static class KatSonuc {
        public Location merkez;              // odanın taban orta noktası (mob/bekçi referansı)
        public final List<Location> mobNoktalari = new ArrayList<>();
        public Location eliteNoktasi;        // sadece boss katı değilse dolu
        public Location sandikNoktasi;        // sadece boss katı değilse dolu (kat loot sandığı)
        public Location bossNoktasi;          // sadece boss katında dolu
        public Location oduluSandigi;         // sadece boss katında dolu (boss sonrası ödül sandığı)
    }

    public static class Sonuc {
        public Location girisNoktasi; // yüzeydeki merdiven başlangıcı
        public final List<KatSonuc> katlar = new ArrayList<>();
    }

    public static Sonuc insaEt(Location yuzeyNoktasi, int katSayisi, int katYuksekligi, Random rng) {
        World w = yuzeyNoktasi.getWorld();
        int merkezX = yuzeyNoktasi.getBlockX();
        int merkezZ = yuzeyNoktasi.getBlockZ();
        int baslangicDerinlik = 14 + rng.nextInt(10); // yüzeyin 14-23 blok altı - yer üstünde kalmasın diye
        int minY = Math.max(w.getMinHeight() + 8, 6);
        int y1 = yuzeyNoktasi.getBlockY() - baslangicDerinlik;

        Sonuc sonuc = new Sonuc();
        int oncekiKatY = yuzeyNoktasi.getBlockY();
        for (int kat = 0; kat < katSayisi; kat++) {
            boolean bossKati = kat == katSayisi - 1;
            int tier = kat + 1;
            int tabanY = y1 - kat * katYuksekligi;
            if (tabanY < minY) tabanY = minY; // çok derin dünyalarda tükenmesin

            int yariCap = (bossKati ? 10 : 6) + Math.min(3, kat / 2); // derinleştikçe biraz büyür
            KatSonuc ks = insaKat(w, merkezX, merkezZ, tabanY, yariCap, katYuksekligi, tier, bossKati, rng);
            sonuc.katlar.add(ks);

            // Merdiven: bu katın merkezinden bir önceki seviyeye kadar 1x1 şaft + ladder (ilk kat için yüzeyden iner)
            int ustY = kat == 0 ? yuzeyNoktasi.getBlockY() : oncekiKatY;
            merdivenSaftiKaz(w, merkezX + yariCapKaydirma(kat), merkezZ, ustY, tabanY + 1);
            if (kat == 0) {
                sonuc.girisNoktasi = new Location(w, merkezX + yariCapKaydirma(0) + 0.5, yuzeyNoktasi.getBlockY() + 1, merkezZ + 0.5);
            }
            oncekiKatY = tabanY; // bir sonraki kat bu kattan aşağı inecek şaft için referans olmasın diye alt sınır kullanılmaz; üstten devam
        }
        return sonuc;
    }

    private static int yariCapKaydirma(int kat) {
        return kat % 2 == 0 ? 3 : -3; // şaft her katta hafif kayar, tam üst üste binmesin (daha doğal/labirentik görünüm)
    }

    private static KatSonuc insaKat(World w, int cx, int cz, int tabanY, int yariCap, int katYuksekligi, int tier, boolean bossKati, Random rng) {
        Material tasTugla = tier >= 4 ? Material.CRACKED_STONE_BRICKS : (tier >= 2 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
        Material zemin = Material.STONE_BRICKS;
        int ic = katYuksekligi - 2; // taban+tavan dışındaki iç yükseklik

        // Ana oda: dış kabuk taş tuğla, iç boşluk hava
        kutu(w, cx - yariCap, tabanY, cz - yariCap, cx + yariCap, tabanY + katYuksekligi - 1, cz + yariCap, tasTugla);
        kutuBosalt(w, cx - yariCap + 1, tabanY + 1, cz - yariCap + 1, cx + yariCap - 1, tabanY + ic, cz + yariCap - 1);
        w.getBlockAt(cx, tabanY, cz).setType(zemin); // tam merkez taban (görsel referans, fonksiyonel değil)

        // 4 yönde küçük çıkma odacıklar ("+" labirent hissi) - boss katında yok, tek büyük oda kalır
        List<Location> alcoveNoktalari = new ArrayList<>();
        if (!bossKati) {
            int[][] yonler = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] yon : yonler) {
                int kapiX = cx + yon[0] * yariCap;
                int kapiZ = cz + yon[1] * yariCap;
                // kapı (2 yüksek, 1 geniş) duvara delik
                for (int dy = 1; dy <= 2; dy++) w.getBlockAt(kapiX, tabanY + dy, kapiZ).setType(Material.AIR);
                int alcoveMerkezX = cx + yon[0] * (yariCap + 3);
                int alcoveMerkezZ = cz + yon[1] * (yariCap + 3);
                kutu(w, alcoveMerkezX - 2, tabanY, alcoveMerkezZ - 2, alcoveMerkezX + 2, tabanY + katYuksekligi - 1, alcoveMerkezZ + 2, tasTugla);
                kutuBosalt(w, alcoveMerkezX - 1, tabanY + 1, alcoveMerkezZ - 1, alcoveMerkezX + 1, tabanY + ic, alcoveMerkezZ + 1);
                // alcove'un dip duvarına meşale
                mesaleKoy(w, alcoveMerkezX + yon[0] * 2, tabanY + 2, alcoveMerkezZ + yon[1] * 2, yon);
                alcoveNoktalari.add(new Location(w, alcoveMerkezX + 0.5, tabanY + 1, alcoveMerkezZ + 0.5));
            }
        }

        // Ana odanın köşelerine meşale (ışık + atmosfer)
        int[][] koseler = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] k : koseler) {
            w.getBlockAt(cx + k[0] * (yariCap - 1), tabanY + 2, cz + k[1] * (yariCap - 1)).setType(Material.TORCH);
        }

        KatSonuc ks = new KatSonuc();
        ks.merkez = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);

        if (bossKati) {
            ks.bossNoktasi = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
            ks.oduluSandigi = new Location(w, cx + 0.5, tabanY + 1, cz - yariCap + 1.5);
            w.getBlockAt(cx, tabanY + 1, cz - yariCap + 1).setType(Material.CHEST);
        } else {
            int mobSayisi = 2 + tier; // derinleştikçe daha kalabalık
            for (int i = 0; i < mobSayisi; i++) {
                double aci = rng.nextDouble() * Math.PI * 2;
                double uzaklik = 1 + rng.nextDouble() * (yariCap - 2);
                ks.mobNoktalari.add(new Location(w, cx + 0.5 + Math.cos(aci) * uzaklik, tabanY + 1, cz + 0.5 + Math.sin(aci) * uzaklik));
            }
            ks.eliteNoktasi = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
            if (!alcoveNoktalari.isEmpty()) {
                Location sandikAlcove = alcoveNoktalari.get(rng.nextInt(alcoveNoktalari.size()));
                ks.sandikNoktasi = sandikAlcove.clone();
                w.getBlockAt(sandikAlcove.getBlockX(), sandikAlcove.getBlockY(), sandikAlcove.getBlockZ()).setType(Material.CHEST);
            }
        }
        return ks;
    }

    private static void merdivenSaftiKaz(World w, int x, int z, int ustY, int altY) {
        if (ustY <= altY) return;
        for (int y = altY; y <= ustY; y++) {
            Block b = w.getBlockAt(x, y, z);
            b.setType(Material.AIR);
            Block merdiven = w.getBlockAt(x, y, z - 1); // -Z yüzüne monte, +Z'ye bakar
            merdiven.setType(Material.LADDER);
            if (merdiven.getBlockData() instanceof Ladder ladder) {
                ladder.setFacing(BlockFace.SOUTH);
                merdiven.setBlockData(ladder);
            }
        }
    }

    private static void mesaleKoy(World w, int x, int y, int z, int[] yon) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(Material.WALL_TORCH);
        if (b.getBlockData() instanceof Directional dir) {
            BlockFace yuz = yon[0] > 0 ? BlockFace.WEST : yon[0] < 0 ? BlockFace.EAST : (yon[1] > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
            dir.setFacing(yuz);
            b.setBlockData(dir);
        }
    }

    private static void kutu(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material mat) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    w.getBlockAt(x, y, z).setType(mat);
                }
            }
        }
    }

    private static void kutuBosalt(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        kutu(w, x1, y1, z1, x2, y2, z2, Material.AIR);
    }
}
