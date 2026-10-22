package me.arenaligi.zindan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * eder. Kullanıcı isteğiyle (Ekim 2026: "bizim vahşi klanlar pluginde yaptığımız gibi sarmal labirent
 * gibi bir yapı olsun, büyük olsun") her normal kat artık Vahşi Kale'nin KaleInsaatYardimcisi'ndeki
 * AYNI algoritmayla (randomize DFS spanning tree + %18 ekstra kısayol - döngüsüz-ama-tam-boş-değil bir
 * labirent) üretilen, hücre hücre bağlanan BÜYÜK bir ızgara labirenti (önceki basit "+" şeklindeki tek
 * oda + 4 odacık tasarımın yerine geçti). En alt kat (boss katı) hâlâ tek büyük bir oda.
 *
 * Her kattan bir altına inen şaftın GİRİŞİ (bu katın tarafında) varsayılan olarak bir tıkaç bloğuyla
 * KAPALI inşa edilir (bkz. KatSonuc#kilitNoktasi) - ZindanYoneticisi bu katın dalgalarını (bkz.
 * kullanıcı isteği: "bir sonraki katı yaşayabilmek için dalga dalga gelen yaratıkları öldürebilmemiz
 * gereksin") temizlenince tıkacı kaldırır.
 *
 * Atmosfer (karanlık/ıslak/zincirli, önceki kullanıcı isteği) aynen korunuyor: meşale yerine sadece
 * girişte loş kül meşalesi, tavandan damla taşı + nemli taban yamaları, zincir dekorasyonu.
 */
public final class ZindanInsaatYardimcisi {

    private ZindanInsaatYardimcisi() { }

    public static class KatSonuc {
        public Location merkez;              // girişe en yakın hücrenin orta noktası (mob/bekçi referansı)
        public final List<Location> mobNoktalari = new ArrayList<>();
        public Location eliteNoktasi;        // sadece boss katı değilse dolu (girişten en uzak hücre)
        public final List<Location> sandikNoktalari = new ArrayList<>(); // sadece boss katı değilse dolu (kat loot sandıkları)
        public Location bossNoktasi;          // sadece boss katında dolu
        public Location oduluSandigi;         // sadece boss katında dolu (boss sonrası ödül sandığı)
        public Location kilitNoktasi;          // bu kattan bir alt kata inen şaftın (bu kat tarafındaki) tıkaç bloğu - null ise yok (son kat)
        public Material tikacMalzemesi;        // bu katın taş-tuğla malzemesi - tıkaç bloğu bununla aynı görünsün diye saklanıyor
    }

    public static class Sonuc {
        public Location girisNoktasi; // ilk katın giriş hücresi
        public final List<KatSonuc> katlar = new ArrayList<>();
    }

    /**
     * ZindanYoneticisi'nin de (z.katYariCaplari - ambient mob yayılma yarıçapı VE zindaniFizikselSil'in
     * temizlik marjı için) AYNI yarıçapı bilmesi gerekiyor - kopya/çatallanmış formül yerine tek bir
     * yerden hesaplanıp paylaşılıyor, böylece labirent katları (büyütülmüş ızgara) büyüyünce temizlik
     * marjı eski/dar kalıp arkada yıkılmamış duvar parçası bırakmaz.
     */
    public static int yariCapHesapla(boolean bossKati, int kat) {
        if (bossKati) return 15 + Math.min(5, kat);
        int tier = kat + 1;
        int gridBoyutu = Math.min(5, 3 + tier / 2);
        return (gridBoyutu * 9) / 2 + 1; // labirent ızgarasının yarı-genişliği + küçük bir tampon
    }

    public static Sonuc insaEt(Location yuzeyNoktasi, int katSayisi, int katYuksekligi, Random rng) {
        World w = yuzeyNoktasi.getWorld();
        int merkezX = yuzeyNoktasi.getBlockX();
        int merkezZ = yuzeyNoktasi.getBlockZ();
        // Derinlik: yüzeyin 45-80 blok altından başlar, kat kat inildikçe çoğu zindan negatif Y'lere iner.
        int baslangicDerinlik = 45 + rng.nextInt(36);
        int minY = w.getMinHeight() + 8; // bedrock'a 8 blok tampon - dünyanın en altına kadar inilebilir
        int y1 = yuzeyNoktasi.getBlockY() - baslangicDerinlik;

        Sonuc sonuc = new Sonuc();
        int oncekiKatY = -1;
        KatSonuc oncekiKs = null;
        for (int kat = 0; kat < katSayisi; kat++) {
            boolean bossKati = kat == katSayisi - 1;
            int tier = kat + 1;
            int tabanY = y1 - kat * katYuksekligi;
            if (tabanY < minY) tabanY = minY; // çok derin dünyalarda tükenmesin

            KatSonuc ks = bossKati
                    ? insaBossKati(w, merkezX, merkezZ, tabanY, yariCapHesapla(true, kat), katYuksekligi, tier, rng)
                    : insaKatLabirent(w, merkezX, merkezZ, tabanY, katYuksekligi, tier, rng);
            sonuc.katlar.add(ks);

            if (kat == 0) {
                // Kullanıcı isteği: yer yüzüyle bağlantı YOK (trial chamber gibi izole) - ilk kat için
                // yüzeye çıkan bir şaft açılmıyor. Giriş noktası ilk katın kendi (giriş hücresi) merkezi.
                sonuc.girisNoktasi = ks.merkez.clone();
            } else {
                // Kat-kat inme hissi iç bağlantıda korunuyor: bu kattan bir önceki kata merdivenli şaft -
                // ama kilitli (bkz. kilitNoktasi) açılana kadar bu şaftın GİRİŞİ üstteki katta kapalı durur.
                int saftX = merkezX + yariCapKaydirma(kat);
                merdivenSaftiKaz(w, saftX, merkezZ, oncekiKatY, tabanY + 1);
                if (oncekiKs != null) {
                    Block tikac = w.getBlockAt(saftX, oncekiKatY, merkezZ);
                    tikac.setType(oncekiKs.tikacMalzemesi != null ? oncekiKs.tikacMalzemesi : Material.COBBLED_DEEPSLATE);
                    oncekiKs.kilitNoktasi = new Location(w, saftX + 0.5, oncekiKatY, merkezZ + 0.5);
                }
            }
            oncekiKatY = tabanY;
            oncekiKs = ks;
        }
        return sonuc;
    }

    private static int yariCapKaydirma(int kat) {
        return kat % 2 == 0 ? 3 : -3; // şaft her katta hafif kayar, tam üst üste binmesin
    }

    // ------------------------------------------------------------------ LABİRENT KATI (normal katlar)

    private static Material tasTuglaSec(int tier) {
        return tier >= 4 ? Material.POLISHED_BLACKSTONE_BRICKS
                : (tier >= 2 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
    }

    /**
     * Vahşi Kale'deki KaleInsaatYardimcisi#labirentUret ile AYNI algoritma (randomize DFS spanning tree
     * + %18 ekstra kısayol) - büyük bir ızgara (3x3 .. 5x5 hücre, her hücre 7x7 blok) labirent üretir.
     * Giriş hücresi ızgaranın ortası (merdiven oraya bağlanıyor); girişten en uzak hücre elit/mini-boss
     * noktası olur (bkz. Vahşi Kale'deki "lider en uzak hücrede" deseniyle aynı mantık).
     */
    private static KatSonuc insaKatLabirent(World w, int cx, int cz, int tabanY, int katYuksekligi, int tier, Random rng) {
        Material tasTugla = tasTuglaSec(tier);
        Material zemin = Material.COBBLED_DEEPSLATE;
        int ic = katYuksekligi - 2;

        int gridBoyutu = Math.min(5, 3 + tier / 2); // büyütüldü (kullanıcı isteği): 3x3 .. 5x5 hücre
        int hucreBoyutu = 9; // her hücre 9x9 blok (büyük)
        int toplamGenislik = gridBoyutu * hucreBoyutu;
        int x0 = cx - toplamGenislik / 2;
        int z0 = cz - toplamGenislik / 2;
        int x1 = x0 + toplamGenislik - 1;
        int z1 = z0 + toplamGenislik - 1;

        // Önce tüm alanı taş kabukla kapla, sonra hepsini boşalt - iç duvarları ayrı ayrı (gap'li) öreceğiz.
        kutu(w, x0, tabanY, z0, x1, tabanY + katYuksekligi - 1, z1, tasTugla);
        kutuBosalt(w, x0 + 1, tabanY + 1, z0 + 1, x1 - 1, tabanY + ic, z1 - 1);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) w.getBlockAt(x, tabanY, z).setType(zemin);

        int girisCx = gridBoyutu / 2, girisCz = gridBoyutu / 2;
        boolean[][] baglantiDogu = new boolean[gridBoyutu][gridBoyutu];
        boolean[][] baglantiGuney = new boolean[gridBoyutu][gridBoyutu];
        labirentUret(gridBoyutu, girisCx, girisCz, rng, baglantiDogu, baglantiGuney);

        for (int gx = 0; gx < gridBoyutu; gx++) {
            for (int gz = 0; gz < gridBoyutu; gz++) {
                int hx0 = x0 + gx * hucreBoyutu, hz0 = z0 + gz * hucreBoyutu;
                int hx1 = hx0 + hucreBoyutu - 1, hz1 = hz0 + hucreBoyutu - 1;
                if (gx < gridBoyutu - 1) {
                    gapliDuvarCiz(w, true, hx1, hz0 + 1, hz1 - 1, tabanY, katYuksekligi, baglantiDogu[gx][gz], tasTugla);
                }
                if (gz < gridBoyutu - 1) {
                    gapliDuvarCiz(w, false, hz1, hx0 + 1, hx1 - 1, tabanY, katYuksekligi, baglantiGuney[gx][gz], tasTugla);
                }
            }
        }

        int[] enUzak = enUzakHucre(gridBoyutu, girisCx, girisCz, baglantiDogu, baglantiGuney);

        KatSonuc ks = new KatSonuc();
        ks.tikacMalzemesi = tasTugla;
        ks.merkez = hucreMerkezi(x0, z0, hucreBoyutu, girisCx, girisCz, tabanY);

        // Girişe yakın loş kül meşalesi (atmosfer - "çok aydınlık olmasın" kullanıcı isteği).
        Block girisMesalesi = w.getBlockAt(x0 + 1, tabanY + 2, cz);
        girisMesalesi.setType(Material.SOUL_WALL_TORCH);
        if (girisMesalesi.getBlockData() instanceof Directional dir) {
            dir.setFacing(BlockFace.EAST);
            girisMesalesi.setBlockData(dir);
        }
        islakVeZincirAtmosferiEkle(w, x0, z0, x1, z1, tabanY, ic, tier, false, rng);

        // Her hücre (giriş hariç) bir mob doğum noktası - dalgalar bu noktalardan seçilerek doğurulur.
        List<Location> sandikAdaylari = new ArrayList<>();
        for (int gx = 0; gx < gridBoyutu; gx++) {
            for (int gz = 0; gz < gridBoyutu; gz++) {
                Location merkezNokta = hucreMerkezi(x0, z0, hucreBoyutu, gx, gz, tabanY);
                if (gx == girisCx && gz == girisCz) continue;
                ks.mobNoktalari.add(merkezNokta);
                sandikAdaylari.add(merkezNokta);
            }
        }
        ks.eliteNoktasi = hucreMerkezi(x0, z0, hucreBoyutu, enUzak[0], enUzak[1], tabanY);

        java.util.Collections.shuffle(sandikAdaylari, rng);
        for (int i = 0; i < Math.min(2, sandikAdaylari.size()); i++) {
            Location sandikNoktasi = sandikAdaylari.get(i);
            ks.sandikNoktalari.add(sandikNoktasi.clone());
            w.getBlockAt(sandikNoktasi.getBlockX(), sandikNoktasi.getBlockY(), sandikNoktasi.getBlockZ()).setType(Material.CHEST);
        }
        return ks;
    }

    private static Location hucreMerkezi(int x0, int z0, int hucreBoyutu, int gx, int gz, int tabanY) {
        int merkezX = x0 + gx * hucreBoyutu + hucreBoyutu / 2;
        int merkezZ = z0 + gz * hucreBoyutu + hucreBoyutu / 2;
        return new Location(null, merkezX + 0.5, tabanY + 1, merkezZ + 0.5);
    }

    /** Randomize edilmiş DFS ile bir yayılma ağacı (spanning tree) üretir - Vahşi Kale'deki
     *  KaleInsaatYardimcisi#labirentUret ile birebir aynı algoritma. */
    private static void labirentUret(int gridBoyutu, int baslaCx, int baslaCz, Random rng,
            boolean[][] baglantiDogu, boolean[][] baglantiGuney) {
        boolean[][] ziyaret = new boolean[gridBoyutu][gridBoyutu];
        Deque<int[]> yigin = new ArrayDeque<>();
        yigin.push(new int[]{baslaCx, baslaCz});
        ziyaret[baslaCx][baslaCz] = true;
        while (!yigin.isEmpty()) {
            int[] simdiki = yigin.peek();
            int cx = simdiki[0], cz = simdiki[1];
            List<int[]> komsular = new ArrayList<>();
            if (cx > 0 && !ziyaret[cx - 1][cz]) komsular.add(new int[]{cx - 1, cz, 0});
            if (cx < gridBoyutu - 1 && !ziyaret[cx + 1][cz]) komsular.add(new int[]{cx + 1, cz, 1});
            if (cz > 0 && !ziyaret[cx][cz - 1]) komsular.add(new int[]{cx, cz - 1, 2});
            if (cz < gridBoyutu - 1 && !ziyaret[cx][cz + 1]) komsular.add(new int[]{cx, cz + 1, 3});
            if (komsular.isEmpty()) { yigin.pop(); continue; }
            int[] secilen = komsular.get(rng.nextInt(komsular.size()));
            int ncx = secilen[0], ncz = secilen[1], yon = secilen[2];
            switch (yon) {
                case 0 -> baglantiDogu[ncx][ncz] = true;
                case 1 -> baglantiDogu[cx][cz] = true;
                case 2 -> baglantiGuney[ncx][ncz] = true;
                default -> baglantiGuney[cx][cz] = true;
            }
            ziyaret[ncx][ncz] = true;
            yigin.push(new int[]{ncx, ncz});
        }
        for (int cx = 0; cx < gridBoyutu - 1; cx++) {
            for (int cz = 0; cz < gridBoyutu; cz++) {
                if (!baglantiDogu[cx][cz] && rng.nextDouble() < 0.18) baglantiDogu[cx][cz] = true;
            }
        }
        for (int cx = 0; cx < gridBoyutu; cx++) {
            for (int cz = 0; cz < gridBoyutu - 1; cz++) {
                if (!baglantiGuney[cx][cz] && rng.nextDouble() < 0.18) baglantiGuney[cx][cz] = true;
            }
        }
    }

    /** BFS ile girişten grafik (hücre-adımı) mesafesi en uzak hücreyi bulur - elit/mini-boss noktası. */
    private static int[] enUzakHucre(int gridBoyutu, int baslaCx, int baslaCz,
            boolean[][] baglantiDogu, boolean[][] baglantiGuney) {
        int[][] mesafe = new int[gridBoyutu][gridBoyutu];
        for (int[] satir : mesafe) java.util.Arrays.fill(satir, -1);
        Deque<int[]> kuyruk = new ArrayDeque<>();
        mesafe[baslaCx][baslaCz] = 0;
        kuyruk.add(new int[]{baslaCx, baslaCz});
        int[] enUzak = {baslaCx, baslaCz};
        while (!kuyruk.isEmpty()) {
            int[] simdiki = kuyruk.poll();
            int cx = simdiki[0], cz = simdiki[1];
            if (mesafe[cx][cz] > mesafe[enUzak[0]][enUzak[1]]) enUzak = simdiki;
            if (cx > 0 && baglantiDogu[cx - 1][cz] && mesafe[cx - 1][cz] < 0) {
                mesafe[cx - 1][cz] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx - 1, cz});
            }
            if (cx < gridBoyutu - 1 && baglantiDogu[cx][cz] && mesafe[cx + 1][cz] < 0) {
                mesafe[cx + 1][cz] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx + 1, cz});
            }
            if (cz > 0 && baglantiGuney[cx][cz - 1] && mesafe[cx][cz - 1] < 0) {
                mesafe[cx][cz - 1] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx, cz - 1});
            }
            if (cz < gridBoyutu - 1 && baglantiGuney[cx][cz] && mesafe[cx][cz + 1] < 0) {
                mesafe[cx][cz + 1] = mesafe[cx][cz] + 1; kuyruk.add(new int[]{cx, cz + 1});
            }
        }
        return enUzak;
    }

    /** Bir labirent duvar hattını çizer - bağlantılıysa ortalanmış bir geçit (gap) bırakır, değilse
     *  tüm hat boyunca katın yüksekliğince dolu duvar örer. Vahşi Kale'deki gapliDuvarCiz'in sadeleştirilmiş hali. */
    private static void gapliDuvarCiz(World w, boolean dikey, int sabitKoordinat, int aralikBaslangic,
            int aralikBitis, int katY0, int katYuksekligi, boolean baglantili, Material malzeme) {
        int uzunluk = aralikBitis - aralikBaslangic + 1;
        if (uzunluk <= 0) return;
        int bosGenislik = baglantili ? Math.min(3, Math.max(1, uzunluk - 2)) : 0;
        int bosBaslangic = aralikBaslangic + Math.max(0, (uzunluk - bosGenislik) / 2);
        int bosBitis = bosBaslangic + bosGenislik - 1;
        for (int degisken = aralikBaslangic; degisken <= aralikBitis; degisken++) {
            boolean bosluk = baglantili && degisken >= bosBaslangic && degisken <= bosBitis;
            if (bosluk) continue;
            for (int y = katY0; y <= katY0 + katYuksekligi - 1; y++) {
                if (dikey) {
                    w.getBlockAt(sabitKoordinat, y, degisken).setType(malzeme);
                } else {
                    w.getBlockAt(degisken, y, sabitKoordinat).setType(malzeme);
                }
            }
        }
    }

    // ------------------------------------------------------------------ BOSS KATI (tek büyük oda, değişmedi)

    private static KatSonuc insaBossKati(World w, int cx, int cz, int tabanY, int yariCap, int katYuksekligi, int tier, Random rng) {
        Material tasTugla = tasTuglaSec(tier);
        Material zemin = Material.COBBLED_DEEPSLATE;
        int ic = katYuksekligi - 2;

        kutu(w, cx - yariCap, tabanY, cz - yariCap, cx + yariCap, tabanY + katYuksekligi - 1, cz + yariCap, tasTugla);
        kutuBosalt(w, cx - yariCap + 1, tabanY + 1, cz - yariCap + 1, cx + yariCap - 1, tabanY + ic, cz + yariCap - 1);
        w.getBlockAt(cx, tabanY, cz).setType(zemin);

        Block girisMesalesi = w.getBlockAt(cx - yariCap + 1, tabanY + 2, cz);
        girisMesalesi.setType(Material.SOUL_WALL_TORCH);
        if (girisMesalesi.getBlockData() instanceof Directional dir) {
            dir.setFacing(BlockFace.EAST);
            girisMesalesi.setBlockData(dir);
        }
        islakVeZincirAtmosferiEkle(w, cx - yariCap, cz - yariCap, cx + yariCap, cz + yariCap, tabanY, ic, tier, true, rng);

        KatSonuc ks = new KatSonuc();
        ks.merkez = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
        ks.bossNoktasi = new Location(w, cx + 0.5, tabanY + 1, cz + 0.5);
        ks.oduluSandigi = new Location(w, cx + 0.5, tabanY + 1, cz - yariCap + 1.5);
        w.getBlockAt(cx, tabanY + 1, cz - yariCap + 1).setType(Material.CHEST);
        return ks;
    }

    // ------------------------------------------------------------------ ATMOSFER (ortak)

    /** Islak hissi (damla taşı + nemli taban yamaları) ve zincir dekorasyonu - hem labirent hem boss katında kullanılır. */
    private static void islakVeZincirAtmosferiEkle(World w, int x0, int z0, int x1, int z1, int tabanY, int ic, int tier, boolean bossKati, Random rng) {
        int genislik = Math.max(1, x1 - x0 - 1);
        int derinlik = Math.max(1, z1 - z0 - 1);
        int damlaSayisi = bossKati ? 8 : 4 + tier * 2;
        for (int i = 0; i < damlaSayisi; i++) {
            int dx = x0 + 1 + rng.nextInt(genislik);
            int dz = z0 + 1 + rng.nextInt(derinlik);
            Block tavanAlti = w.getBlockAt(dx, tabanY + ic, dz);
            if (tavanAlti.getType() == Material.AIR) {
                tavanAlti.setType(Material.POINTED_DRIPSTONE);
                if (tavanAlti.getBlockData() instanceof PointedDripstone pd) {
                    pd.setVerticalDirection(BlockFace.DOWN);
                    tavanAlti.setBlockData(pd);
                }
            }
        }
        int nemliYamaSayisi = bossKati ? 10 : 6 + tier * 2;
        for (int i = 0; i < nemliYamaSayisi; i++) {
            int dx = x0 + 1 + rng.nextInt(genislik);
            int dz = z0 + 1 + rng.nextInt(derinlik);
            Block taban = w.getBlockAt(dx, tabanY, dz);
            if (taban.getType() == Material.COBBLED_DEEPSLATE) {
                taban.setType(rng.nextBoolean() ? Material.MUD : Material.MOSS_BLOCK);
            }
        }
        // Material.CHAIN sabiti bu sunucunun API derlemesinde yok - isme göre runtime'da çözülüyor, yoksa (null) atlanır.
        Material zincirMat = Material.matchMaterial("CHAIN");
        if (zincirMat != null) {
            int zincirSayisi = bossKati ? 6 : 3 + tier;
            for (int i = 0; i < zincirSayisi; i++) {
                int dx = x0 + 1 + rng.nextInt(genislik);
                int dz = z0 + 1 + rng.nextInt(derinlik);
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
