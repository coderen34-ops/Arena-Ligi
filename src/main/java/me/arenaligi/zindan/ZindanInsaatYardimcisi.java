package me.arenaligi.zindan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.PointedDripstone;

/**
 * Yer altı Zindanı'nın fiziksel yapısını (katman katman, yüzeyden aşağı inen) prosedürel olarak inşa
 * eder - Vahşi Kale'deki KaleInsaatYardimcisi'nin aynı ruhu (rastgele ama tutarlı, "labirent" hissi
 * veren) burada yer altına, kat kat derinleşen bir versiyonla uygulanıyor.
 *
 * Her kat: merkezde bir oda + 4 yönde (K/G/D/B) küçük birer çıkma odacık ("+" şeklinde, basit ama
 * gezilecek bir labirent hissi verir), duvarlar/taban/tavan koyu taş/derin taş tuğlası (derin katlarda
 * çatlak/cilalı kara taş - görsel derinlik). Atmosfer kullanıcı isteğiyle (bkz. kullanıcı Ekim 2026
 * mesajı: "çok boş, çok aydınlık... karanlık, ıslak, zincirli olsun") KARANLIK (eskiden 4 köşe meşale +
 * her odacıkta meşale vardı - artık yalnızca giriş yakınında TEK loş kul meşalesi), ISLAK (tavandan
 * sarkan dikenli damla taşı + nemli zemin yaması olarak çürük/küf taşı, gerçek su akıtılmıyor - taşma
 * riski yok) ve ZİNCİRLİ (tavandan sarkan zincir dekorasyonu) olacak şekilde yeniden tasarlandı. Odalar
 * da büyütüldü (daha geniş yarıçap) ve yer yüzünün epey altına, negatif Y'lere kadar inilecek şekilde
 * başlangıç derinliği artırıldı. En alt kat (boss katı) daha büyük tek bir oda.
 */
public final class ZindanInsaatYardimcisi {

    private ZindanInsaatYardimcisi() { }

    public static class KatSonuc {
        public Location merkez;              // odanın taban orta noktası (mob/bekçi referansı)
        public final List<Location> mobNoktalari = new ArrayList<>();
        public Location eliteNoktasi;        // sadece boss katı değilse dolu
        public final List<Location> sandikNoktalari = new ArrayList<>(); // sadece boss katı değilse dolu (kat loot sandıkları - 2 adet)
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
        // Kullanıcı isteğiyle derinlik ciddi artırıldı: eskiden yüzeyin sadece 14-23 altı (hep pozitif Y
        // kalıyordu) - artık 45-80 blok altından başlar, kat kat inildikçe çoğu zindan negatif Y'lere
        // (deniz seviyesinin/0'ın epey altına) ulaşır.
        int baslangicDerinlik = 45 + rng.nextInt(36);
        int minY = w.getMinHeight() + 8; // bedrock'a 8 blok tampon - dünyanın en altına kadar inilebilir (negatif Y dahil)
        int y1 = yuzeyNoktasi.getBlockY() - baslangicDerinlik;

        Sonuc sonuc = new Sonuc();
        int oncekiKatY = yuzeyNoktasi.getBlockY();
        for (int kat = 0; kat < katSayisi; kat++) {
            boolean bossKati = kat == katSayisi - 1;
            int tier = kat + 1;
            int tabanY = y1 - kat * katYuksekligi;
            if (tabanY < minY) tabanY = minY; // çok derin dünyalarda tükenmesin

            int yariCap = (bossKati ? 15 : 10) + Math.min(5, kat); // büyütüldü (kullanıcı isteği) - derinleştikçe de büyür
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
        // Koyu/derin taş paleti (kullanıcı isteği: "çok aydınlık" değil, karanlık bir zindan hissi -
        // normal taş tuğlası yerine deepslate/kara taş, görsel olarak da daha karanlık).
        Material tasTugla = tier >= 4 ? Material.POLISHED_BLACKSTONE_BRICKS
                : (tier >= 2 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
        Material zemin = Material.COBBLED_DEEPSLATE;
        int ic = katYuksekligi - 2; // taban+tavan dışındaki iç yükseklik

        // Ana oda: dış kabuk koyu taş, iç boşluk hava
        kutu(w, cx - yariCap, tabanY, cz - yariCap, cx + yariCap, tabanY + katYuksekligi - 1, cz + yariCap, tasTugla);
        kutuBosalt(w, cx - yariCap + 1, tabanY + 1, cz - yariCap + 1, cx + yariCap - 1, tabanY + ic, cz + yariCap - 1);
        w.getBlockAt(cx, tabanY, cz).setType(zemin); // tam merkez taban (görsel referans, fonksiyonel değil)

        // 4 yönde çıkma odacıklar ("+" labirent hissi) - boss katında yok, tek büyük oda kalır
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
                // dip duvarına köy örümcek ağı (atmosfer + hafif yavaşlatma - zorluk)
                w.getBlockAt(alcoveMerkezX + yon[0], tabanY + 2, alcoveMerkezZ + yon[1]).setType(Material.COBWEB);
                alcoveNoktalari.add(new Location(w, alcoveMerkezX + 0.5, tabanY + 1, alcoveMerkezZ + 0.5));
            }
        }

        // ------- ATMOSFER: karanlık, ıslak, zincirli (kullanıcı isteği, Ekim 2026) -------
        // Eskiden 4 köşe + her odacıkta meşale vardı (çok aydınlıktı) - artık SADECE girişe yakın tek
        // loş kül rengi meşale (soul torch) bırakılıyor, geri kalan oda karanlıkta kalıyor.
        Block girisMesalesi = w.getBlockAt(cx - yariCap + 1, tabanY + 2, cz);
        girisMesalesi.setType(Material.SOUL_WALL_TORCH);
        if (girisMesalesi.getBlockData() instanceof Directional dir) {
            dir.setFacing(BlockFace.EAST);
            girisMesalesi.setBlockData(dir);
        }

        // Islak hissi: tavandan sarkan dikenli damla taşı + nemli (çürük/küf) zemin yamaları. Gerçek su
        // akıtılmıyor (taşma/dupe riski olmasın diye) - sadece görsel.
        int damlaSayisi = bossKati ? 6 : 3 + tier;
        for (int i = 0; i < damlaSayisi; i++) {
            int dx = cx - yariCap + 2 + rng.nextInt(Math.max(1, 2 * yariCap - 3));
            int dz = cz - yariCap + 2 + rng.nextInt(Math.max(1, 2 * yariCap - 3));
            Block tavanAlti = w.getBlockAt(dx, tabanY + ic, dz);
            if (tavanAlti.getType() == Material.AIR) {
                tavanAlti.setType(Material.POINTED_DRIPSTONE);
                if (tavanAlti.getBlockData() instanceof PointedDripstone pd) {
                    pd.setVerticalDirection(BlockFace.DOWN);
                    tavanAlti.setBlockData(pd);
                }
            }
        }
        int nemliYamaSayisi = 4 + tier;
        for (int i = 0; i < nemliYamaSayisi; i++) {
            int dx = cx - yariCap + 1 + rng.nextInt(Math.max(1, 2 * yariCap - 1));
            int dz = cz - yariCap + 1 + rng.nextInt(Math.max(1, 2 * yariCap - 1));
            Block taban = w.getBlockAt(dx, tabanY, dz);
            if (taban.getType() == zemin) {
                taban.setType(rng.nextBoolean() ? Material.MUD : Material.MOSS_BLOCK);
            }
        }

        // Zincirli hissi: tavandan sarkan birkaç zincir dekorasyonu. Material.CHAIN sabiti bu sunucunun
        // kullandığı API derlemesinde yok (bkz. derleme hatası) - isme göre runtime'da çözülüyor, yoksa
        // (null) sessizce atlanıyor (diğer atmosfer öğeleri - damla taşı/örümcek ağı/nem - zaten yeterli).
        Material zincirMat = Material.matchMaterial("CHAIN");
        if (zincirMat != null) {
            int zincirSayisi = bossKati ? 4 : 2;
            for (int i = 0; i < zincirSayisi; i++) {
                int dx = cx - yariCap + 2 + rng.nextInt(Math.max(1, 2 * yariCap - 3));
                int dz = cz - yariCap + 2 + rng.nextInt(Math.max(1, 2 * yariCap - 3));
                Block zincirUst = w.getBlockAt(dx, tabanY + ic, dz);
                if (zincirUst.getType() == Material.AIR) {
                    zincirUst.setType(zincirMat);
                    if (zincirUst.getBlockData() instanceof Orientable or) {
                        or.setAxis(Axis.Y);
                        zincirUst.setBlockData(or);
                    }
                }
            }
        }

        KatSonuc ks = new KatSonuc();
        ks.merkez = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);

        if (bossKati) {
            ks.bossNoktasi = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
            ks.oduluSandigi = new Location(w, cx + 0.5, tabanY + 1, cz - yariCap + 1.5);
            w.getBlockAt(cx, tabanY + 1, cz - yariCap + 1).setType(Material.CHEST);
        } else {
            int mobSayisi = 3 + (int) Math.round(tier * 1.4); // kullanıcı isteği: daha zor/kalabalık
            for (int i = 0; i < mobSayisi; i++) {
                double aci = rng.nextDouble() * Math.PI * 2;
                double uzaklik = 1 + rng.nextDouble() * (yariCap - 2);
                ks.mobNoktalari.add(new Location(w, cx + 0.5 + Math.cos(aci) * uzaklik, tabanY + 1, cz + 0.5 + Math.sin(aci) * uzaklik));
            }
            ks.eliteNoktasi = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
            // Kullanıcı isteği: "sandıklar olsun, lootlanabilir eşyalar olsun" - tek yerine 2 odacık sandığı
            List<Location> karisikAlcove = new ArrayList<>(alcoveNoktalari);
            java.util.Collections.shuffle(karisikAlcove, rng);
            for (int i = 0; i < Math.min(2, karisikAlcove.size()); i++) {
                Location sandikAlcove = karisikAlcove.get(i);
                ks.sandikNoktalari.add(sandikAlcove.clone());
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
