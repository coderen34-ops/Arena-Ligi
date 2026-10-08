package me.arenaligi.komut;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import me.arenaligi.ArenaLigi;
import me.arenaligi.Mesaj;
import me.arenaligi.lig.LigSistemi;
import me.arenaligi.model.Dovuscu;

/**
 * /arena sira | ayril | istatistik [oyuncu/lakap] | siralama | yardim
 * /arena admin kur npc kayit | npc sil | puan <oyuncu> <miktar|+miktar|-miktar> | lakap <oyuncu> <yeni lakap> | yenile
 */
public class ArenaKomut implements TabExecutor {

    private final ArenaLigi plugin;

    public ArenaKomut(ArenaLigi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        String alt = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "yardim";
        if (alt.equals("admin")) {
            if (!s.hasPermission("arena.admin")) { m().gonder(s, "yetki-yok", "&cBu işlem için yetkiniz yok."); return true; }
            admin(s, args);
            return true;
        }
        if (alt.equals("istatistik") || alt.equals("siralama")) {
            if (alt.equals("istatistik")) istatistik(s, args.length > 1 ? args[1] : null);
            else siralama(s);
            return true;
        }
        if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return true; }
        if (!p.hasPermission("arena.kullan")) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); return true; }
        switch (alt) {
            case "sira", "sıra" -> plugin.kuyruk().gir(p);
            case "ayril", "ayrıl" -> plugin.kuyruk().ayril(p);
            case "hazir", "hazır" -> plugin.mac().hazir(p);
            case "vazgec", "vazgeç" -> plugin.mac().vazgec(p);
            case "mac", "maç", "durum" -> macDurumu(p);
            case "bahis" -> bahis(p, args);
            case "cik", "çık" -> plugin.seyirci().cik(p);
            case "izle" -> plugin.seyirci().tribuneGit(p);
            case "bahismenu" -> {
                try { plugin.seyirci().bahisMenusu(p, Integer.parseInt(args.length > 1 ? args[1] : "-1")); } catch (NumberFormatException e) { yardim(p); }
            }
            default -> yardim(p);
        }
        return true;
    }

    private void yardim(CommandSender s) {
        m().gonder(s, "yardim-baslik", "&6--- Arena Ligi ---");
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena sira", "aciklama", "Maç sırasına gir"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena ayril", "aciklama", "Maç sırasından çık"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena mac", "aciklama", "Süren maç / sonraki eşleştirme"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena bahis <dövüşçü> <miktar>", "aciklama", "Maç öncesi bahis"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena izle | /arena cik", "aciklama", "Tribüne git (izleyici olunca bahis açılır) / tribünden çık"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena istatistik [oyuncu]", "aciklama", "Dövüşçü istatistikleri"));
        s.sendMessage(m().metin("yardim-satir", "&e{komut} &7- {aciklama}", "komut", "/arena siralama", "aciklama", "İlk 10 dövüşçü"));
        s.sendMessage(m().metin("yardim-kayit", "&7Kayıt için Arena Kayıt NPC'sine sağ tıklayın."));
    }

    private void macDurumu(CommandSender s) {
        me.arenaligi.mac.Mac mac = plugin.mac().aktifMac();
        if (mac == null) {
            long kalan = Math.max(0, plugin.mac().sonrakiEslestirme() - System.currentTimeMillis());
            m().gonder(s, "mac-yok", "&7Şu an maç yok. Sırada {sayi} kişi; sonraki eşleştirme ~{dk} dk sonra.",
                    "sayi", plugin.kuyruk().boyut(), "dk", (kalan + 59_999) / 60_000);
            return;
        }
        m().gonder(s, "mac-durum", "&6Maç: &f{baslik} &7| Durum: &e{durum}", "baslik", mac.baslik(), "durum", mac.durum.name());
        if (plugin.bahis().havuz() > 0 || plugin.bahis().acikMi()) {
            m().gonder(s, "mac-bahis-durum", "&7Bahis: {acik} &7| Havuz: &e${havuz}", "acik", plugin.bahis().acikMi() ? "&aaçık" : "&ckapalı",
                    "havuz", me.arenaligi.mac.MacYonetici.para(plugin.bahis().havuz()));
        }
    }

    /** /arena bahis <dövüşçü lakabı ya da adı> <miktar> (lakap boşluk içerebilir; son kelime miktardır). */
    private void bahis(Player p, String[] args) {
        me.arenaligi.mac.Mac mac = plugin.mac().aktifMac();
        if (args.length < 3) { m().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena bahis <dövüşçü> <miktar>"); return; }
        if (mac == null) { m().gonder(p, "bahis-kapali", "&cŞu an bahis penceresi açık değil (maç öncesi bekleme sırasında açılır)."); return; }
        double miktar;
        try { miktar = Double.parseDouble(args[args.length - 1].replace(",", ".")); } catch (NumberFormatException e) { m().gonder(p, "gecersiz-sayi", "&cGeçersiz sayı."); return; }
        String hedef = me.arenaligi.model.DovuscuManager.anahtar(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 1)));
        int takim = -1;
        for (java.util.UUID u : mac.oyuncular()) {
            if (me.arenaligi.model.DovuscuManager.anahtar(mac.lakaplar.getOrDefault(u, "")).equals(hedef)
                    || me.arenaligi.model.DovuscuManager.anahtar(mac.isimler.getOrDefault(u, "")).equals(hedef)) takim = mac.takimi(u);
        }
        if (takim < 0) { m().gonder(p, "bahis-dovuscu-yok", "&cBu maçta böyle bir dövüşçü yok: &f{baslik}", "baslik", mac.baslik()); return; }
        plugin.bahis().oyna(p, takim, miktar);
    }

    private void istatistik(CommandSender s, String hedef) {
        Dovuscu d;
        if (hedef != null) d = plugin.dovusculer().bul(hedef);
        else if (s instanceof Player p) d = plugin.dovusculer().get(p.getUniqueId());
        else { m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena istatistik <oyuncu>"); return; }
        if (d == null) {
            if (hedef == null) m().gonder(s, "kayitli-degil", "&cÖnce Arena Kayıt NPC'sinden dövüşçü olarak kayıt olmalısınız.");
            else m().gonder(s, "dovuscu-yok", "&cBöyle bir dövüşçü bulunamadı.");
            return;
        }
        int sira = plugin.dovusculer().siralama().indexOf(d) + 1;
        double oran = d.macSayisi() == 0 ? 0 : 100.0 * d.galibiyet / d.macSayisi();
        m().gonder(s, "ist-baslik", "&6--- \"{lakap}\" &7({isim}) &6---", "lakap", d.lakap, "isim", d.isim);
        s.sendMessage(m().metin("ist-lig", "&7Lig: {lig} &7| Puan: &f{puan} &7| Sıra: &f#{sira}", "lig", plugin.lig().lig(d).gorunen(), "puan", d.puan, "sira", sira));
        s.sendMessage(m().metin("ist-mac", "&7Galibiyet: &a{g} &7| Mağlubiyet: &c{mg} &7| Berabere: &e{b} &7| Kazanma: &f%{oran}",
                "g", d.galibiyet, "mg", d.maglubiyet, "b", d.beraberlik, "oran", Math.round(oran)));
        s.sendMessage(m().metin("ist-seri", "&7Seri: &f{seri} &7| En iyi seri: &f{eniyi} &7| En yüksek puan: &f{enyuksek}",
                "seri", d.seri, "eniyi", d.enIyiSeri, "enyuksek", d.enYuksekPuan));
    }

    private void siralama(CommandSender s) {
        List<Dovuscu> liste = plugin.dovusculer().siralama();
        m().gonder(s, "siralama-baslik", "&6--- Arena Ligi: İlk 10 ---");
        if (liste.isEmpty()) { s.sendMessage(m().metin("siralama-bos", "&7Henüz kayıtlı dövüşçü yok.")); return; }
        for (int i = 0; i < Math.min(10, liste.size()); i++) {
            Dovuscu d = liste.get(i);
            s.sendMessage(m().metin("siralama-satir", "&e#{sira} &f\"{lakap}\" &7{lig} &7- &f{puan} puan &8({g}G/{mg}M)",
                    "sira", i + 1, "lakap", d.lakap, "lig", plugin.lig().lig(d).gorunen(), "puan", d.puan, "g", d.galibiyet, "mg", d.maglubiyet));
        }
    }

    // ------------------------------------------------------------------ ADMIN
    private void admin(CommandSender s, String[] args) {
        String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (islem) {
            case "kur" -> {
                if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
                if (args.length >= 4 && args[2].equalsIgnoreCase("npc") && args[3].equalsIgnoreCase("kayit")) plugin.kayitNpc().kur(p);
                else if (args.length >= 4 && args[2].equalsIgnoreCase("npc") && args[3].equalsIgnoreCase("izle")) plugin.seyirci().kur(p);
                else if (args.length >= 3 && args[2].equalsIgnoreCase("tablo")) plugin.tablo().kur(p);
                else if (args.length >= 3 && !args[2].equalsIgnoreCase("npc")) plugin.arena().kur(p, args[2]);
                else m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim",
                        "/arena admin kur <" + String.join("|", me.arenaligi.arena.ArenaYonetici.NOKTALAR.keySet()) + "|npc kayit|npc izle|tablo>");
            }
            case "durum" -> plugin.arena().durum(s);
            case "kit" -> {
                if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
                plugin.kitler().komut(p, args);
            }
            case "test" -> {
                if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
                plugin.yedek().test(p, args);
            }
            case "yedek" -> plugin.yedek().yedekKomut(s, args);
            case "npc" -> {
                if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
                if (args.length >= 3 && args[2].equalsIgnoreCase("sil")) {
                    plugin.kayitNpc().sil(p);
                    int izle = plugin.seyirci().sil(p);
                    if (izle > 0) m().gonder(p, "npc-silindi", "&e{sayi} NPC silindi.", "sayi", izle);
                }
                else m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin npc sil");
            }
            case "puan" -> adminPuan(s, args);
            case "tablo" -> {
                if (args.length > 2 && args[2].equalsIgnoreCase("sil")) plugin.tablo().sil(s);
                else { plugin.tablo().guncelle(); m().gonder(s, "tablo-guncellendi", "&aSıralama tablosu güncellendi. &7(Kurmak: /arena admin kur tablo, kaldırmak: /arena admin tablo sil)"); }
            }
            case "etiketyenile" -> m().gonder(s, "etiket-yenilendi", "&a{sayi} dövüşçünün lig etiketi yeniden uygulandı.", "sayi", plugin.etiket().hepsiniGuncelle());
            case "lakap" -> adminLakap(s, args);
            case "mac" -> {
                String m2 = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (m2.equals("iptal")) {
                    if (plugin.mac().aktifMac() == null) m().gonder(s, "mac-yok-admin", "&7Süren maç yok.");
                    else plugin.mac().macIptal(m().metin("iptal-admin", "yetkili iptal etti"));
                } else if (m2.equals("baslat")) {
                    plugin.mac().hemenEslestir();
                    m().gonder(s, "mac-eslestir", "&aEşleştirme hemen deneniyor (sırada {sayi} kişi; arena: {arena}, kit: {kit}).", "sayi", plugin.kuyruk().boyut(),
                            "arena", plugin.arena().hazir() ? "hazır" : "eksik", "kit", plugin.kitler().bosMu() ? "yok" : plugin.kitler().adlar().size());
                } else m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin mac <baslat|iptal>");
            }
            case "yenile" -> {
                plugin.reloadConfig();
                plugin.kitler().hazirKitleriYukle();
                m().gonder(s, "yenilendi", "&aAyarlar yeniden yüklendi.");
            }
            default -> m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim",
                    "/arena admin <kur <nokta|npc kayit|npc izle|tablo> | durum | kit | test | yedek | mac | npc sil | puan | lakap | tablo [sil] | etiketyenile | yenile>");
        }
    }

    /** "150" puanı 150 yapar, "+50"/"-20" ekler/çıkarır. */
    private void adminPuan(CommandSender s, String[] args) {
        if (args.length < 4) { m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin puan <oyuncu> <miktar|+miktar|-miktar>"); return; }
        Dovuscu d = plugin.dovusculer().bul(args[2]);
        if (d == null) { m().gonder(s, "dovuscu-yok", "&cBöyle bir dövüşçü bulunamadı."); return; }
        int deger;
        try { deger = Integer.parseInt(args[3]); } catch (NumberFormatException e) { m().gonder(s, "gecersiz-sayi", "&cGeçersiz sayı."); return; }
        boolean goreli = args[3].startsWith("+") || args[3].startsWith("-");
        int once = d.puan;
        LigSistemi.Sonuc sonuc;
        if (goreli) {
            sonuc = plugin.lig().puanEkle(d, deger);
        } else {
            // Doğrudan ayar: lig tampon olmadan puana göre belirlenir
            d.puan = Math.max(0, deger);
            d.enYuksekPuan = Math.max(d.enYuksekPuan, d.puan);
            int eskiLig = d.lig;
            d.lig = plugin.lig().puanaGoreLig(d.puan);
            sonuc = new LigSistemi.Sonuc(d.puan - once, eskiLig, d.lig);
        }
        plugin.veri().kaydet();
        plugin.mac().ligDegisti(d, sonuc);
        plugin.tablo().guncelle();
        m().gonder(s, "admin-puan", "&a\"{lakap}\" puanı: {once} -> {sonra} &7(Lig: {lig}&7)", "lakap", d.lakap, "once", once, "sonra", d.puan,
                "lig", plugin.lig().lig(d).gorunen());
        plugin.getLogger().info("[Admin] " + s.getName() + " " + d.isim + " puanı " + once + " -> " + d.puan + " (lig " + sonuc.eskiLig() + " -> " + sonuc.yeniLig() + ")");
    }

    private void adminLakap(CommandSender s, String[] args) {
        if (args.length < 4) { m().gonder(s, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/arena admin lakap <oyuncu> <yeni lakap>"); return; }
        Dovuscu d = plugin.dovusculer().bul(args[2]);
        if (d == null) { m().gonder(s, "dovuscu-yok", "&cBöyle bir dövüşçü bulunamadı."); return; }
        String yeni = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
        String hata = plugin.dovusculer().lakapHatasi(yeni, d.uuid);
        if (hata != null) { s.sendMessage(m().onek() + hata); return; }
        String eski = d.lakap;
        plugin.dovusculer().lakapDegistir(d, yeni);
        m().gonder(s, "admin-lakap", "&a\"{eski}\" lakabı \"{yeni}\" olarak değiştirildi.", "eski", eski, "yeni", yeni);
    }

    // ------------------------------------------------------------------ TAB
    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String label, String[] args) {
        List<String> o = new ArrayList<>();
        if (args.length == 1) {
            o.addAll(List.of("sira", "ayril", "hazir", "vazgec", "mac", "bahis", "izle", "cik", "istatistik", "siralama", "yardim"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bahis") && plugin.mac().aktifMac() != null) {
            plugin.mac().aktifMac().lakaplar.values().forEach(o::add);
            if (s.hasPermission("arena.admin")) o.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("istatistik")) {
            plugin.dovusculer().hepsi().forEach(d -> o.add(d.isim));
        } else if (args[0].equalsIgnoreCase("admin") && s.hasPermission("arena.admin")) {
            if (args.length == 2) o.addAll(List.of("kur", "durum", "kit", "test", "yedek", "mac", "npc", "puan", "lakap", "tablo", "etiketyenile", "yenile"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("mac")) o.addAll(List.of("baslat", "iptal"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("kur")) { o.addAll(me.arenaligi.arena.ArenaYonetici.NOKTALAR.keySet()); o.add("npc"); o.add("tablo"); }
            else if (args.length == 3 && args[1].equalsIgnoreCase("tablo")) o.add("sil");
            else if (args.length == 3 && args[1].equalsIgnoreCase("kit")) o.addAll(List.of("liste", "ekle", "sil"));
            else if (args.length == 4 && args[1].equalsIgnoreCase("kit") && args[2].equalsIgnoreCase("sil")) o.addAll(plugin.kitler().adlar());
            else if (args.length == 3 && args[1].equalsIgnoreCase("test")) o.addAll(List.of("kit", "bitir"));
            else if (args.length == 4 && args[1].equalsIgnoreCase("test") && args[2].equalsIgnoreCase("kit")) o.addAll(plugin.kitler().adlar());
            else if (args.length == 3 && args[1].equalsIgnoreCase("yedek")) o.addAll(List.of("liste", "geriver"));
            else if (args.length == 4 && args[1].equalsIgnoreCase("yedek")) plugin.yedek().bekleyenler().values().forEach(o::add);
            else if (args.length == 3 && args[1].equalsIgnoreCase("npc")) o.add("sil");
            else if (args.length == 4 && args[1].equalsIgnoreCase("kur") && args[2].equalsIgnoreCase("npc")) o.addAll(List.of("kayit", "izle"));
            else if (args.length == 3 && (args[1].equalsIgnoreCase("puan") || args[1].equalsIgnoreCase("lakap"))) plugin.dovusculer().hepsi().forEach(d -> o.add(d.isim));
        }
        String yazilan = args[args.length - 1].toLowerCase(Locale.ROOT);
        o.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(yazilan));
        return o;
    }
}
